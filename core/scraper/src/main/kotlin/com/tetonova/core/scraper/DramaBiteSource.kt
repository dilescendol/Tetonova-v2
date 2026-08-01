package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for DramaBite's GoodBos API documented at `/api.html`. */
object DramaBiteSource {
    private const val BASE = "https://dramabite.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_LIST_PATH = "/home"
    private const val PAGE_SIZE = 10
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isDramaBite(url: String): Boolean =
        hostOf(url)?.contains("dramabite") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val body = getBody(endpoint, needsCode = false) ?: return LivePage(emptyList())
        val rows = dramaRows(body, endpoint)
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.distinctBy { it.url },
            nextUrl = nextUrl(endpoint, rows.size),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val body = getBody("$BASE/search?q=${enc(cleanQuery)}&lang=${enc(lang)}", needsCode = false)
            ?: return emptyList()
        return jsonArray(body).objects()
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val info = getBody("$BASE/drama/${encPath(id)}?lang=${enc(lang)}", needsCode = false)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val episodeRows = episodes(id, lang)
        val episodes = episodeRows.mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                val total = info.intAny("episodes", "total_episode", "total") ?: 0
                (1..total).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang), null)
                }
            }
        val total = info.intAny("episodes", "total_episode", "total") ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: "DramaBite",
            cover = info.strAny("cover", "cover_url"),
            synopsis = info.strAny("desc", "description"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "DramaBite",
            released = null,
            genres = info.arrayStrings("tags", "label_list").distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val body = getBody("$BASE/play/${encPath(id)}/$episode?lang=${enc(lang)}", needsCode = true)
            ?: return emptyList()
        val streamUrl = runCatching { JSONObject(body).strAny("url") }.getOrNull()
            ?: return emptyList()
        val variant = ServerVariant(qualityLabel(streamUrl), streamUrl)
        return listOf(VideoServer("DramaBite", streamUrl, listOf(variant)))
    }

    private suspend fun episodes(id: String, lang: String): List<JSONObject> {
        val body = getBody("$BASE/episodes/${encPath(id)}?lang=${enc(lang)}", needsCode = true)
            ?: return emptyList()
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
        val path = canonicalPath(url).lowercase()
        val cleanPath = when (path) {
            "/home",
            "/module",
            -> path
            else -> DEFAULT_LIST_PATH
        }
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        query.forEach { (key, value) ->
            if (!key.equals("code", true)) out[key] = value
        }
        out.putIfAbsent("lang", query["lang"] ?: DEFAULT_LANG)
        if (cleanPath == "/module") out.putIfAbsent("page", "1")
        return BASE + cleanPath + out.toQuery()
    }

    private fun dramaRows(body: String, endpoint: String): List<JSONObject> {
        val path = canonicalPath(endpoint).lowercase()
        if (path == "/module") {
            return runCatching { JSONObject(body) }.getOrNull()
                ?.optJSONArray("videos")
                .objects()
        }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        return root.optJSONArray("module_list").objects().flatMap { module ->
            val videoList = module.optJSONArray("video_list").objects()
            if (videoList.isNotEmpty()) {
                videoList
            } else {
                module.optJSONArray("module_item_list").objects().mapNotNull {
                    it.optJSONObject("Item")?.optJSONObject("VideoInfo")
                }
            }
        }
    }

    private fun nextUrl(endpoint: String, rowCount: Int): String? {
        val path = canonicalPath(endpoint).lowercase()
        val query = queryMap(endpoint).toMutableMap()
        val lang = query["lang"] ?: DEFAULT_LANG
        if (path == "/home") return "$BASE/module?page=1&lang=${enc(lang)}"
        if (path != "/module" || rowCount < PAGE_SIZE) return null
        val page = query["page"]?.toIntOrNull() ?: 1
        if (page >= 100) return null
        query["page"] = (page + 1).toString()
        return BASE + path + query.toQuery()
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("id", "cid") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("episodes", "total_episode", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("cover", "cover_url"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("vid", "episode", "number", "num") ?: return null
        return LiveEpisode(
            num = number,
            title = row.strAny("title", "name") ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("cover", "thumb", "image"),
        )
    }

    private fun matchesQuery(title: String, query: String): Boolean =
        LiveSource.matchesSearchQuery(title, query)

    private fun qualityLabel(url: String): String =
        Regex("[./](\\d{3,4})p(?:[?./]|$)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "Auto"

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/dramabite/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/dramabite/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: queryMap(url)["cid"]
            ?: Regex("/dramabite/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/(?:drama|episodes|play)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/play/[^/?#]+/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun jsonArray(body: String): JSONArray =
        runCatching { JSONArray(body) }.getOrElse { JSONArray() }

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() }
            ?: LiveRuntimeConfig.accessCodeFor(url)
            ?: DEFAULT_ACCESS_CODE

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: DEFAULT_LANG

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).rawPath.orEmpty().ifBlank { "/" } }.getOrDefault("/")

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

    private fun JSONObject.name(): String? = strAny("title", "name")

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
