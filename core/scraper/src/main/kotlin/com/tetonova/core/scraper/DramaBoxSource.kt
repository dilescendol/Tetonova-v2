package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for the DramaBox GoodBos JSON API documented at `/api.html`. */
object DramaBoxSource {
    private const val BASE = "https://dramabox.goodbos.online"
    private const val DEFAULT_LANG = "in"
    private const val DEFAULT_LIST_PATH = "/api/v1/homepage"

    fun isDramaBox(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val lower = url.lowercase()
        return "dramabox" in host || ("drakula" in host && "/dramabox" in lower)
    }

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val body = getBody(endpoint, needsCode = true) ?: return LivePage(emptyList())
        val rows = bookRows(body, canonicalPath(endpoint))
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { bookToItem(it, lang) },
            nextUrl = nextUrl(endpoint, body, rows.size),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val body = getBody(
            "$BASE/api/v1/search?query=${enc(query.trim())}&lang=${enc(lang)}",
            needsCode = false,
        ) ?: return emptyList()
        return jsonArray(body).objects().mapNotNull { bookToItem(it, lang) }
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = bookIdOf(url) ?: return null
        val lang = langOf(url)
        val detailBody = getBody(
            "$BASE/api/v1/detail?bookId=${enc(id)}&lang=${enc(lang)}",
            needsCode = true,
        ) ?: return null
        val book = runCatching { JSONObject(detailBody) }.getOrNull() ?: return null
        val episodes = episodeRows(id, lang)
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = book.intAny("chapterCount", "episodeCount", "total")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = book.name() ?: "DramaBox",
            cover = book.strAny("cover", "coverWap", "image"),
            synopsis = book.strAny("introduction", "description", "synopsis"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "DramaBox",
            released = null,
            genres = book.arrayStrings("tags", "labels", "typeTwoNames").distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = bookIdOf(url) ?: return emptyList()
        val number = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = episodeRows(id, lang).firstOrNull {
            it.intAny("chapterIndex", "episode", "number", "num") == number
        } ?: return emptyList()
        val streamUrl = row.strAny("videoUrl", "playUrl", "url", "stream") ?: return emptyList()
        val label = qualityLabel(streamUrl)
        val variant = ServerVariant(label, streamUrl)
        return listOf(VideoServer(name = "DramaBox", embedUrl = streamUrl, variants = listOf(variant)))
    }

    private suspend fun episodeRows(id: String, lang: String): List<JSONObject> {
        val body = getBody(
            "$BASE/api/v1/allepisode?bookId=${enc(id)}&lang=${enc(lang)}",
            needsCode = true,
        ) ?: return emptyList()
        return jsonArray(body).objects()
    }

    private suspend fun getBody(url: String, needsCode: Boolean): String? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        return LiveClient.getHtml(endpoint) { body ->
            val trimmed = body.trimStart()
            trimmed.startsWith("{") || trimmed.startsWith("[")
        }
    }

    private fun withCode(url: String): String? {
        if (queryMap(url)["code"].orEmpty().isNotBlank()) return url
        val code = accessCode(BASE) ?: return null
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}code=${enc(code)}"
    }

    private fun listEndpoint(url: String): String {
        val path = canonicalPath(url)
        val cleanPath = when (path.lowercase()) {
            "/api/v1/homepage",
            "/api/v1/dubbed",
            "/api/v1/foryou",
            "/api/v1/latest",
            -> path.lowercase()
            else -> DEFAULT_LIST_PATH
        }
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        query.forEach { (key, value) ->
            if (!key.equals("code", true)) out[key] = value
        }
        out.putIfAbsent("lang", query["lang"] ?: DEFAULT_LANG)
        if (cleanPath == "/api/v1/homepage") out.putIfAbsent("page", "1")
        if (cleanPath == "/api/v1/dubbed") {
            out.putIfAbsent("classify", "terpopuler")
            out.putIfAbsent("page", "1")
        }
        return BASE + cleanPath + out.toQuery()
    }

    private fun bookRows(body: String, path: String): List<JSONObject> {
        if (path.equals("/api/v1/homepage", true)) {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            return root.optJSONObject("recommendList")?.optJSONArray("records").objects()
        }
        return jsonArray(body).objects()
    }

    private fun nextUrl(endpoint: String, body: String, rowCount: Int): String? {
        val path = canonicalPath(endpoint).lowercase()
        if (path != "/api/v1/homepage" || rowCount == 0) return null
        val query = queryMap(endpoint)
        val page = query["page"]?.toIntOrNull() ?: 1
        val totalPages = runCatching { JSONObject(body) }.getOrNull()
            ?.optJSONObject("recommendList")
            ?.optInt("totalPages", 0)
            ?: 0
        if (totalPages <= page) return null
        val next = query.toMutableMap()
        next["page"] = (page + 1).toString()
        return BASE + path + next.toQuery()
    }

    private fun bookToItem(book: JSONObject, lang: String): LiveItem? {
        val id = book.strAny("bookId", "id") ?: return null
        val title = book.name() ?: return null
        val total = book.intAny("chapterCount", "episodeCount", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = book.strAny("cover", "coverWap", "image"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("chapterIndex", "episode", "number", "num") ?: return null
        return LiveEpisode(
            num = number,
            title = row.strAny("chapterName", "title", "name") ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("cover", "thumb", "image"),
        )
    }

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/drama/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/drama/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun bookIdOf(url: String): String? {
        val query = queryMap(url)
        return query["bookId"]
            ?: Regex("/drama/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
    }

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun qualityLabel(url: String): String =
        Regex("[./](\\d{3,4})p(?:[?./]|$)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "Auto"

    private fun jsonArray(body: String): JSONArray =
        runCatching { JSONArray(body) }.getOrElse { JSONArray() }

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() } ?: LiveRuntimeConfig.accessCodeFor(url)

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).rawPath.orEmpty().ifBlank { "/" } }.getOrDefault("/")

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: DEFAULT_LANG

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

    private fun queryMap(url: String): LinkedHashMap<String, String> {
        val raw = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        val out = LinkedHashMap<String, String>()
        if (raw.isBlank()) return out
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
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

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JSONObject.arrayStrings(vararg keys: String): List<String> =
        keys.flatMap { key -> optJSONArray(key)?.strings().orEmpty() }
}
