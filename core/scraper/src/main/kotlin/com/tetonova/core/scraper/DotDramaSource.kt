package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for the DotDrama GoodBos API documented at `/api.html`. */
object DotDramaSource {
    private const val BASE = "https://dotdrama.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_LIMIT = 18

    fun isDotDrama(url: String): Boolean =
        hostOf(url)?.contains("dotdrama") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val data = json.optJSONObject("dgiv") ?: JSONObject()
        val rows = data.optJSONArray("lint").objects()
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) },
            nextUrl = nextUrl(endpoint, data, rows.isNotEmpty()),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val json = getJson("$BASE/api/search?q=${enc(cleanQuery)}&lang=${enc(lang)}")
            ?: return emptyList()
        return json.optJSONArray("results").objects()
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val root = getJson("$BASE/api/drama/${encPath(id)}")?.optJSONObject("dgiv") ?: return null
        val info = root.optJSONObject("bswitc") ?: return null
        val episodes = root.optJSONArray("ebeer").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = info.intAny("ewood") ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: "DotDrama",
            cover = info.strAny("pday"),
            synopsis = info.strAny("dwill"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "DotDrama",
            released = null,
            genres = emptyList(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val root = getJson("$BASE/api/drama/${encPath(id)}")?.optJSONObject("dgiv") ?: return emptyList()
        val row = root.optJSONArray("ebeer").objects()
            .firstOrNull { it.intAny("ewheel") == episode }
            ?: return emptyList()
        val variants = row.optJSONArray("pphys").objects()
            .mapNotNull { stream ->
                val streamUrl = stream.strAny("Mopp", "Bcold") ?: return@mapNotNull null
                val label = stream.strAny("Dbag")
                    ?: stream.intAny("Wroll")?.let { "${it}p" }
                    ?: "Auto"
                label to streamUrl
            }
            .distinctBy { it.second }
            .sortedByDescending { it.first.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("DotDrama", variants.first().embedUrl, variants))
    }

    private suspend fun getJson(url: String): JSONObject? {
        val body = LiveClient.getHtml(url) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { it.optInt("squa", 1) != 0 && it.optString("error").isBlank() }
    }

    private fun listEndpoint(url: String): String {
        val query = queryMap(url)
        val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val limit = query["limit"]?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_LIMIT
        val lang = query["lang"] ?: DEFAULT_LANG
        return "$BASE/api/drama/list?lang=${enc(lang)}&page=$page&limit=$limit"
    }

    private fun nextUrl(endpoint: String, data: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val query = queryMap(endpoint)
        val current = data.intAny("pdirec") ?: query["page"]?.toIntOrNull() ?: 1
        val pages = data.intAny("pglas") ?: return null
        if (current >= pages) return null
        return "$BASE/api/drama/list?lang=${enc(langOf(endpoint))}&page=${current + 1}" +
            "&limit=${query["limit"] ?: DEFAULT_LIMIT.toString()}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("dcup") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("ewood")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("pday"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("ewheel") ?: return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = null,
        )
    }

    private fun matchesQuery(title: String, query: String): Boolean =
        LiveSource.matchesSearchQuery(title, query)

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/dotdrama/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/dotdrama/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/dotdrama/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: DEFAULT_LANG

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")
    private fun String.dec(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.name(): String? = strAny("nseri")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }
}
