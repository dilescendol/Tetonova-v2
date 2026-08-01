package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for FlickReels' GoodBos API documented at `/api.html`. */
object FlickReelsSource {
    private const val BASE = "https://flickreels.goodbos.online"
    private const val DEFAULT_LANG = "6"
    private const val DEFAULT_LIST_PATH = "/api/home"
    private const val DEFAULT_PAGE_SIZE = 12
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isFlickReels(url: String): Boolean =
        hostOf(url)?.contains("flickreels") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = false) ?: return LivePage(emptyList())
        val rows = dramaRows(json, endpoint)
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
        val json = getJson("$BASE/search?q=${enc(cleanQuery)}&lang=${enc(lang)}", needsCode = false)
            ?: return emptyList()
        return json.optJSONArray("data").objects()
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val data = batchLoad(id, lang) ?: return null
        val episodes = data.optJSONArray("list").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        return LiveDetail(
            title = queryMap(url)["title"] ?: data.name() ?: "FlickReels",
            cover = data.strAny("cover") ?: queryMap(url)["cover"],
            synopsis = data.strAny("introduce", "intro", "description") ?: queryMap(url)["intro"],
            status = episodes.size.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "FlickReels",
            released = null,
            genres = emptyList(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = batchLoad(id, lang)
            ?.optJSONArray("list")
            .objects()
            .firstOrNull { it.intAny("chapter_num", "episode", "num") == episode }
            ?: return emptyList()
        val variants = buildList {
            row.strAny("hls_url")?.let { add(ServerVariant("HLS", it)) }
            row.strAny("play_url")?.let { add(ServerVariant("Auto", it)) }
        }.distinctBy { it.embedUrl }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("FlickReels", variants.first().embedUrl, variants))
    }

    private suspend fun batchLoad(id: String, lang: String): JSONObject? =
        getJson("$BASE/batchload/${encPath(id)}?lang=${enc(lang)}", needsCode = true)
            ?.optJSONObject("data")

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { json ->
                json.optString("error").isBlank() &&
                    (json.optInt("status_code", 1) != 0 || json.has("data"))
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
            "/api/home",
            "/api/list",
            "/trending",
            -> path
            else -> DEFAULT_LIST_PATH
        }
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        out["lang"] = upstreamLang(query["lang"] ?: DEFAULT_LANG)
        if (cleanPath == "/api/home" || cleanPath == "/api/list") {
            out["page"] = query["page"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: "1"
            if (cleanPath == "/api/list") {
                out["page_size"] = query["page_size"]?.toIntOrNull()?.coerceAtLeast(1)?.toString()
                    ?: DEFAULT_PAGE_SIZE.toString()
                out["category_id"] = query["category_id"] ?: "0"
            }
        }
        return BASE + cleanPath + out.toQuery()
    }

    private fun dramaRows(json: JSONObject, endpoint: String): List<JSONObject> =
        when (canonicalPath(endpoint).lowercase()) {
            "/trending" -> json.optJSONArray("data").objects()
            "/search" -> json.optJSONArray("data").objects()
            else -> json.optJSONObject("data")?.optJSONArray("data").objects()
        }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val path = canonicalPath(endpoint).lowercase()
        if (path != "/api/home" && path != "/api/list") return null
        val pageInfo = json.optJSONObject("data")?.optJSONObject("page_info") ?: return null
        val page = pageInfo.intAny("page") ?: queryMap(endpoint)["page"]?.toIntOrNull() ?: 1
        val pageSize = pageInfo.intAny("page_size") ?: queryMap(endpoint)["page_size"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE
        val total = pageInfo.intAny("total") ?: return null
        if (page * pageSize >= total || page >= 100) return null
        val query = queryMap(endpoint).toMutableMap()
        query["page"] = (page + 1).toString()
        if (path == "/api/list") query.putIfAbsent("page_size", pageSize.toString())
        return BASE + path + query.toQuery()
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("playlet_id", "id") ?: return null
        val title = row.name() ?: return null
        val cover = row.strAny("cover")
        val intro = row.strAny("introduce", "intro", "description")
        val total = row.intAny("upload_num", "episode_count", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang, title, cover, intro),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("chapter_num", "episode", "num") ?: return null
        return LiveEpisode(
            num = number,
            title = row.strAny("chapter_title", "title", "name") ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("chapter_cover", "cover", "thumb"),
        )
    }

    private fun matchesQuery(title: String, query: String): Boolean =
        LiveSource.matchesSearchQuery(title, query)

    private fun detailUrl(id: String, lang: String, title: String? = null, cover: String? = null, intro: String? = null): String {
        val query = LinkedHashMap<String, String>()
        query["lang"] = lang
        title?.takeIf { it.isNotBlank() }?.let { query["title"] = it }
        cover?.takeIf { it.isNotBlank() }?.let { query["cover"] = it }
        intro?.takeIf { it.isNotBlank() }?.take(500)?.let { query["intro"] = it }
        return "$BASE/flickreels/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/flickreels/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/flickreels/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/batchload/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() }
            ?: LiveRuntimeConfig.accessCodeFor(url)
            ?: DEFAULT_ACCESS_CODE

    private fun upstreamLang(lang: String): String =
        when (lang.lowercase()) {
            "id", "in" -> DEFAULT_LANG
            "en" -> "1"
            "ja" -> "2"
            "ko" -> "3"
            "tc" -> "4"
            "es" -> "5"
            "th" -> "7"
            "de" -> "8"
            "pt" -> "10"
            "fr" -> "11"
            "ar" -> "12"
            else -> lang
        }

    private fun langOf(url: String): String = upstreamLang(queryMap(url)["lang"] ?: DEFAULT_LANG)

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

    private fun JSONObject.name(): String? = strAny("title", "name")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }
}
