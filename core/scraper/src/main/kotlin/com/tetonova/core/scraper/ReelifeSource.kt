package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for Reelife's GoodBos API documented at `/api.html`. */
object ReelifeSource {
    private const val BASE = "https://reelife.goodbos.online"
    private const val DEFAULT_LANG = "in"
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isReelife(url: String): Boolean =
        hostOf(url)?.contains("reelife") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = false) ?: return LivePage(emptyList())
        val rows = dramaRows(json)
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.distinctBy { it.url },
            nextUrl = nextUrl(endpoint, json, rows.isNotEmpty()),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val json = getJson("$BASE/api/v1/search?q=${enc(cleanQuery)}&page=1&lang=${enc(lang)}", needsCode = false)
            ?: return emptyList()
        return dramaRows(json)
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val data = getJson("$BASE/api/v1/book/${encPath(id)}?lang=${enc(lang)}", needsCode = false)
            ?.optJSONObject("data")
            ?: return null
        val book = data.optJSONObject("bookVo") ?: data
        val contentRows = data.optJSONArray("chapterContentList").objects()
        val allRows = chapters(id, lang)
        val chapterRows = if (allRows.size > contentRows.size) allRows else contentRows
        val episodes = chapterRows
            .mapIndexedNotNull { index, row -> episodeToLive(id, lang, row, index + 1) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                val total = book.intAny("lastChapterId", "chapterCount", "totalChapter")
                    ?: queryMap(url)["total"]?.toIntOrNull()
                    ?: 0
                (1..total).map { number -> LiveEpisode(number, "Episode $number", watchUrl(id, number.toString(), lang)) }
            }
        val total = book.intAny("lastChapterId", "chapterCount", "totalChapter")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = book.name() ?: queryMap(url)["title"] ?: "Reelife",
            cover = book.strAny("coverWap", "cover", "coverUrl") ?: queryMap(url)["cover"],
            synopsis = book.strAny("introduction", "description", "summary") ?: queryMap(url)["intro"],
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "Reelife",
            released = null,
            genres = book.optJSONArray("bookTags").strings(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val chapterId = chapterIdOf(url) ?: return emptyList()
        val lang = langOf(url)
        val json = getJson("$BASE/api/v1/play/${encPath(id)}/${encPath(chapterId)}?lang=${enc(lang)}", needsCode = true)
            ?: return emptyList()
        val variants = buildList {
            json.strAny("videoUrl", "mp4720p", "playUrl")?.let { add("Auto" to it) }
            json.optJSONArray("standbyUrls").strings().forEachIndexed { index, streamUrl ->
                add("Standby ${index + 1}" to streamUrl)
            }
        }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("Reelife", variants.first().embedUrl, variants))
    }

    private suspend fun chapters(id: String, lang: String): List<JSONObject> =
        getJson("$BASE/api/v1/book/${encPath(id)}/chapters?lang=${enc(lang)}", needsCode = false)
            ?.optJSONObject("data")
            ?.optJSONArray("chapterList")
            .objects()

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { json ->
                json.optString("error").isBlank() &&
                    (!json.has("code") || json.optInt("code", 0) == 0)
            }
    }

    private fun withCode(url: String): String? {
        if (queryMap(url)["code"].orEmpty().isNotBlank()) return url
        val code = accessCode(BASE) ?: return null
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}code=${enc(code)}"
    }

    private fun listEndpoint(url: String): String {
        val path = canonicalPath(url).lowercase()
        val query = queryMap(url)
        val lang = query["lang"] ?: DEFAULT_LANG
        return when {
            path == "/api/v1/rank" -> {
                val type = query["type"] ?: "trending"
                "$BASE/api/v1/rank?type=${enc(type)}&lang=${enc(lang)}"
            }
            path == "/api/v1/browse" -> {
                val out = LinkedHashMap<String, String>()
                out["page"] = query["page"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: "1"
                query["letter"]?.takeIf { it.isNotBlank() }?.let { out["letter"] = it }
                out["lang"] = lang
                "$BASE/api/v1/browse${out.toQuery()}"
            }
            path == "/api/v1/home" -> {
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                "$BASE/api/v1/home?page=$page&lang=${enc(lang)}"
            }
            else -> "$BASE/api/v1/home?page=1&lang=${enc(lang)}"
        }
    }

    private fun dramaRows(json: JSONObject): List<JSONObject> =
        json.optJSONArray("dramas").objects()
            .ifEmpty { json.optJSONObject("data")?.optJSONArray("dramas").objects() }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems || !json.optBoolean("hasMore", false)) return null
        val path = canonicalPath(endpoint).lowercase()
        if (path != "/api/v1/home" && path != "/api/v1/browse") return null
        val query = queryMap(endpoint).toMutableMap()
        val page = query["page"]?.toIntOrNull() ?: json.optInt("page", 1)
        if (page >= 100) return null
        query["page"] = (page + 1).toString()
        query["lang"] = query["lang"] ?: DEFAULT_LANG
        return BASE + path + query.toQuery()
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("bookId", "id") ?: return null
        val title = row.name() ?: return null
        val cover = row.strAny("coverWap", "cover", "coverUrl")
        val intro = row.strAny("introduction", "description", "summary")
        val total = row.intAny("lastChapterId", "chapterCount", "totalChapter")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang, title, cover, intro, total),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject, fallback: Int): LiveEpisode? {
        val chapterId = row.strAny("chapterId", "id") ?: fallback.toString()
        val number = chapterId.toIntOrNull()
            ?: row.intAny("chapterIndex", "episode", "number", "chapterNo")
            ?: fallback
        return LiveEpisode(
            num = number,
            title = row.strAny("chapterName", "title", "name") ?: "Episode $number",
            url = watchUrl(id, chapterId, lang),
            thumb = row.strAny("chapterImg", "cover", "thumb"),
        )
    }

    private fun detailUrl(
        id: String,
        lang: String,
        title: String? = null,
        cover: String? = null,
        intro: String? = null,
        total: Int? = null,
    ): String {
        val query = LinkedHashMap<String, String>()
        query["lang"] = lang
        title?.takeIf { it.isNotBlank() }?.let { query["title"] = it }
        cover?.takeIf { it.isNotBlank() }?.let { query["cover"] = it }
        intro?.takeIf { it.isNotBlank() }?.take(500)?.let { query["intro"] = it }
        total?.takeIf { it > 0 }?.let { query["total"] = it.toString() }
        return "$BASE/reelife/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, chapterId: String, lang: String): String =
        "$BASE/reelife/watch/${encPath(id)}/episode/${encPath(chapterId)}?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: queryMap(url)["bookId"]
            ?: Regex("/reelife/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/v1/book/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/v1/(?:play|book)/([^/?#]+)/", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun chapterIdOf(url: String): String? =
        queryMap(url)["ep"]?.takeIf { it.isNotBlank() }
            ?: Regex("/episode/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/v1/play/[^/?#]+/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun matchesQuery(title: String, query: String): Boolean {
        val haystack = cleanTitleKey(title)
        val needle = cleanTitleKey(query)
        if (needle.isBlank()) return false
        if (needle in haystack) return true
        val words = needle.split(' ').filter { it.length >= 3 }
        return words.isNotEmpty() && words.all { it in haystack }
    }

    private fun cleanTitleKey(value: String): String =
        value.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun uniqueLabels(rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val counts = HashMap<String, Int>()
        return rows.map { (label, streamUrl) ->
            val n = (counts[label] ?: 0) + 1
            counts[label] = n
            (if (n == 1) label else "$label #$n") to streamUrl
        }
    }

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() }
            ?: LiveRuntimeConfig.accessCodeFor(url)
            ?: DEFAULT_ACCESS_CODE

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: DEFAULT_LANG

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).path.ifBlank { "/" } }.getOrDefault("/")

    private fun queryMap(url: String): LinkedHashMap<String, String> {
        val raw = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        val out = LinkedHashMap<String, String>()
        raw.split('&').filter { it.isNotBlank() }.forEach { part ->
            val key = part.substringBefore('=').dec()
            val value = part.substringAfter('=', "").dec()
            if (key.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun Map<String, String>.toQuery(): String =
        if (isEmpty()) "" else entries.joinToString("&", prefix = "?") { (key, value) ->
            "${enc(key)}=${enc(value)}"
        }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")
    private fun String.dec(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).trim().ifBlank { null }
        }

    private fun JSONObject.name(): String? = strAny("bookName", "title", "name")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            when {
                !has(key) || isNull(key) -> null
                opt(key) is Number -> optInt(key)
                else -> optString(key).filter(Char::isDigit).toIntOrNull()
            }
        }
}
