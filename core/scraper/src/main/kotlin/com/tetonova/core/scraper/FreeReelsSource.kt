package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * FreeReels GoodBos API adapter.
 *
 * The API documented at /freereels-api.html exposes separate New, For You, Popular, search,
 * detail, and per-episode playback endpoints. Every endpoint requires the panel-published code.
 */
object FreeReelsSource {
    private const val BASE = "https://drakula.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_LIST_PATH = "/api/freereels/new"
    private const val PAGE_SIZE = 10

    fun isFreeReels(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val lower = url.lowercase()
        return "freereels" in host ||
            "freereels" in lower ||
            (host == "drakula.goodbos.online" && "fundrama" !in lower)
    }

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val rows = seriesRows(getJson(endpoint) ?: return LivePage(emptyList()))
        val lang = langOf(endpoint)
        val items = rows.mapNotNull { seriesToItem(it, lang) }
        return LivePage(items, nextUrl(endpoint, rows.size))
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val direct = getJson("$BASE/api/freereels/search?q=${enc(cleanQuery)}&lang=${enc(lang)}")
            ?.let(::seriesRows)
            .orEmpty()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
        if (direct.isNotEmpty()) {
            return direct.mapNotNull { row ->
                val id = row.strAny("key", "id") ?: return@mapNotNull null
                val info = detailInfo(id, lang)
                seriesToItem(info ?: row, lang)
            }.distinctBy { it.url }
        }

        // The upstream search occasionally returns unrelated, cover-less placeholders. Search the
        // real catalog in that case so exact title searches still work in the app.
        val candidates = LinkedHashMap<String, JSONObject>()
        catalogRows("/api/freereels/popular?page=1&lang=${enc(lang)}").forEach { row ->
            row.strAny("key", "id")?.let { candidates.putIfAbsent(it, row) }
        }
        for (page in 1..6) {
            catalogRows("/api/freereels/new?page=$page&lang=${enc(lang)}").forEach { row ->
                row.strAny("key", "id")?.let { candidates.putIfAbsent(it, row) }
            }
        }
        return candidates.values
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { seriesToItem(it, lang) }
            .take(30)
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = seriesIdOf(url) ?: return null
        val lang = langOf(url)
        val info = detailInfo(id, lang) ?: return null
        val episodes = info.optJSONArray("episode_list").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = info.intAny("episode_count", "update_count", "total")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: "FreeReels",
            cover = info.strAny("cover", "poster", "image"),
            synopsis = info.strAny("desc", "description", "summary"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "FreeReels",
            released = null,
            genres = info.arrayStrings("content_tags", "tag").distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = seriesIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val json = getJson(
            "$BASE/api/freereels/drama/${encPath(id)}/play/$episode?lang=${enc(lang)}",
        ) ?: return emptyList()
        val data = payload(json)
        val variants = buildList {
            data.str("m3u8_url")?.let { add("H.264" to it) }
            data.str("external_audio_h264_m3u8")?.let { add("H.264" to it) }
            data.str("external_audio_h265_m3u8")?.let { add("H.265" to it) }
            data.str("video_url")?.let { add("Auto" to it) }
        }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(
            VideoServer(name = "FreeReels", embedUrl = variants.first().embedUrl, variants = variants),
        )
    }

    private suspend fun detailInfo(id: String, lang: String): JSONObject? {
        val json = getJson(
            "$BASE/api/freereels/drama/${encPath(id)}?lang=${enc(lang)}",
        ) ?: return null
        return payload(json).optJSONObject("info") ?: payload(json)
    }

    private suspend fun catalogRows(path: String): List<JSONObject> =
        getJson(BASE + path)?.let(::seriesRows).orEmpty()

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
        val path = canonicalPath(url)
        val cleanPath = when (path.lowercase()) {
            "/api/freereels/new",
            "/api/freereels/foryou",
            "/api/freereels/popular",
            -> path.lowercase()
            else -> DEFAULT_LIST_PATH
        }
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        query.forEach { (key, value) ->
            if (!key.equals("code", true)) out[key] = value
        }
        out.putIfAbsent("page", "1")
        out.putIfAbsent("lang", query["lang"] ?: DEFAULT_LANG)
        return BASE + cleanPath + out.toQuery()
    }

    private fun seriesRows(json: JSONObject): List<JSONObject> =
        payload(json).optJSONArray("items").objects().mapNotNull { row ->
            row.optJSONObject("series") ?: row
        }

    private fun payload(json: JSONObject): JSONObject {
        var current = json
        repeat(2) {
            current = current.optJSONObject("data") ?: return current
        }
        return current
    }

    private fun nextUrl(endpoint: String, rowCount: Int): String? {
        val path = canonicalPath(endpoint).lowercase()
        if (path == "/api/freereels/popular" || rowCount < PAGE_SIZE) return null
        val query = queryMap(endpoint)
        val page = query["page"]?.toIntOrNull() ?: 1
        if (page >= 100) return null
        val next = query.toMutableMap()
        next["page"] = (page + 1).toString()
        return BASE + path + next.toQuery()
    }

    private fun seriesToItem(series: JSONObject, lang: String): LiveItem? {
        val id = series.strAny("key", "id") ?: return null
        val title = series.name() ?: return null
        val total = series.intAny("episode_count", "update_count", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = series.strAny("cover", "poster", "image"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("index", "serial_number", "episode_number", "number") ?: return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("cover", "thumb", "image"),
        )
    }

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

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/freereels/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/freereels/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun seriesIdOf(url: String): String? {
        val query = queryMap(url)
        return query["id"]
            ?: Regex("/freereels/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/freereels/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
    }

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/play/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

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
