package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for FunDrama's GoodBos API documented at `/fundrama-api.html`. */
object FunDramaSource {
    private const val BASE = "https://drakula.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_LIMIT = 20
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isFunDrama(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val lower = url.lowercase()
        return "fundrama" in host || (host == "drakula.goodbos.online" && "fundrama" in lower)
    }

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val rows = listRows(json)
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
        val json = getJson("$BASE/api/fundrama/search?q=${enc(cleanQuery)}&lang=${enc(lang)}")
            ?: return emptyList()
        return searchRows(json).mapNotNull { dramaToItem(it, lang) }.distinctBy { it.url }
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val info = detailInfo(id, lang) ?: return null
        val episodes = episodeRows(id, lang)
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = info.intAny("eshe", "total") ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: "FunDrama",
            cover = info.strAny("ptear", "cover", "image"),
            synopsis = info.strAny("dentra", "description", "summary"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "FunDrama",
            released = null,
            genres = info.arrayStrings("sgui").distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = episodeRows(id, lang).firstOrNull { episodeNumber(it) == episode } ?: return emptyList()
        val variants = row.videoVariants()
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { it.first.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("FunDrama", variants.first().embedUrl, variants))
    }

    private suspend fun detailInfo(id: String, lang: String): JSONObject? {
        val json = getJson("$BASE/api/fundrama/drama/${encPath(id)}?lang=${enc(lang)}") ?: return null
        val data = json.optJSONObject("data") ?: return null
        return data.optJSONObject("ddriv")?.optJSONObject("btra")
            ?: data.optJSONObject("btra")
            ?: data
    }

    private suspend fun episodeRows(id: String, lang: String): List<JSONObject> {
        val json = getJson("$BASE/api/fundrama/drama/${encPath(id)}/episodes?lang=${enc(lang)}")
            ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("episodes").objects()
            .ifEmpty { data.optJSONObject("ddriv")?.optJSONArray("eclim").objects() }
            .ifEmpty { data.optJSONArray("eclim").objects() }
    }

    private suspend fun getJson(url: String): JSONObject? {
        val endpoint = withCode(url) ?: return null
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { it.optBoolean("success", true) && it.optString("error").isBlank() }
    }

    private fun withCode(url: String): String? {
        if (queryMap(url)["code"].orEmpty().isNotBlank()) return url
        val code = accessCode(BASE) ?: return null
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}code=${enc(code)}"
    }

    private fun listEndpoint(url: String): String {
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        out["lang"] = query["lang"] ?: DEFAULT_LANG
        out["page"] = query["page"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: "1"
        out["limit"] = query["limit"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: DEFAULT_LIMIT.toString()
        return "$BASE/api/fundrama/dramas${out.toQuery()}"
    }

    private fun listRows(json: JSONObject): List<JSONObject> {
        val data = json.optJSONObject("data") ?: return emptyList()
        return data.optJSONObject("ddriv")?.optJSONArray("lsumm").objects()
            .ifEmpty { data.optJSONArray("lsumm").objects() }
            .ifEmpty { data.optJSONArray("items").objects() }
    }

    private fun searchRows(json: JSONObject): List<JSONObject> {
        val data = json.optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("results").objects()
            .ifEmpty { data.optJSONObject("ddriv")?.optJSONArray("lsumm").objects() }
            .ifEmpty { data.optJSONArray("items").objects() }
    }

    private fun nextUrl(endpoint: String, rowCount: Int): String? {
        val query = queryMap(endpoint)
        val limit = query["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        if (rowCount < limit) return null
        val page = query["page"]?.toIntOrNull() ?: 1
        if (page >= 100) return null
        val next = query.toMutableMap()
        next["page"] = (page + 1).toString()
        return "$BASE/api/fundrama/dramas${next.toQuery()}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("dshame", "id") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("eshe", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("ptear", "cover", "image"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = episodeNumber(row) ?: return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = null,
        )
    }

    private fun episodeNumber(row: JSONObject): Int? =
        row.intAny("episode", "erev", "number", "num")

    private fun JSONObject.videoVariants(): List<Pair<String, String>> =
        optJSONArray("videos").objects().mapNotNull { video ->
            val url = video.strAny("url", "Mbrie", "Bcance") ?: return@mapNotNull null
            (video.strAny("quality", "Dspee") ?: qualityLabel(url)) to url
        }.ifEmpty {
            optJSONArray("ptitl").objects().mapNotNull { video ->
                val url = video.strAny("Mbrie", "Bcance", "url") ?: return@mapNotNull null
                (video.strAny("Dspee", "quality") ?: qualityLabel(url)) to url
            }
        }

    private fun uniqueLabels(rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val counts = HashMap<String, Int>()
        return rows.map { (label, streamUrl) ->
            val n = (counts[label] ?: 0) + 1
            counts[label] = n
            (if (n == 1) label else "$label #$n") to streamUrl
        }
    }

    private fun qualityLabel(url: String): String =
        Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}P" }
            ?: "Auto"

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/fundrama/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/fundrama/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/fundrama/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/fundrama/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() }
            ?: LiveRuntimeConfig.accessCodeFor(url)
            ?: DEFAULT_ACCESS_CODE

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

    private fun JSONObject.name(): String? = strAny("nsin", "title", "name")

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
