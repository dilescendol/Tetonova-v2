package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for the BiliTV GoodBos API documented at `/api.html`. */
object BiliTvSource {
    private const val BASE = "https://bilitv.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val PAGE_SIZE = 10

    fun isBiliTv(url: String): Boolean =
        hostOf(url)?.contains("bilitv") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val rows = getJson(endpoint)?.optJSONObject("data")?.optJSONArray("dramas").objects()
        val lang = langOf(endpoint)
        val items = rows.mapNotNull { dramaToItem(it, lang) }
        return LivePage(items, nextUrl(endpoint, rows.size))
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val direct = getJson("$BASE/api/search?q=${enc(cleanQuery)}&lang=${enc(lang)}")
            ?.optJSONObject("data")
            ?.optJSONArray("records")
            .objects()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
        if (direct.isNotEmpty()) return direct.mapNotNull { dramaToItem(it, lang) }

        // The documented search currently returns records:null. Walk the real paged catalog until
        // an exact/relevant title is found so Search remains useful without inventing results.
        val matches = LinkedHashMap<String, LiveItem>()
        for (page in 1..25) {
            val rows = getJson("$BASE/api/home?page=$page&limit=20&lang=${enc(lang)}")
                ?.optJSONObject("data")
                ?.optJSONArray("dramas")
                .objects()
            if (rows.isEmpty()) break
            rows.asSequence()
                .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
                .mapNotNull { dramaToItem(it, lang) }
                .forEach { matches.putIfAbsent(it.url, it) }
            if (matches.values.any { cleanTitleKey(it.title) == cleanTitleKey(cleanQuery) }) break
        }
        return matches.values.toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val info = getJson("$BASE/api/short/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONObject("data")
            ?: return null
        val episodeData = getJson("$BASE/api/short/${encPath(id)}/episode")
            ?.optJSONObject("data")
        val episodes = episodeData?.optJSONArray("list").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = info.intAny("total_num", "episode_count", "total")
            ?: episodeData?.intAny("total")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: "BiliTV",
            cover = info.strAny("cover_img", "cover", "poster", "image"),
            synopsis = info.strAny("desc", "description", "summary"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "BiliTV",
            released = null,
            genres = listOfNotNull(info.strAny("cate_name")).distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val json = getJson(
            "$BASE/api/stream/${encPath(id)}/$episode?quality=720&lang=${enc(lang)}",
            needsCode = true,
        ) ?: return emptyList()
        val data = json.optJSONObject("data") ?: return emptyList()
        val variants = buildList {
            val qualities = data.optJSONObject("allQualities")
            qualities?.keys()?.forEach { quality ->
                qualities.str(quality)?.let { add("${quality}p" to it) }
            }
            data.str("url")?.let { add("Auto" to it) }
        }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { it.first.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("BiliTV", variants.first().embedUrl, variants, data.subtitleTracks(lang)))
    }

    private suspend fun getJson(url: String, needsCode: Boolean = false): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { it.optString("error").isBlank() && it.optInt("status", 1) != 0 }
    }

    private fun withCode(url: String): String? {
        if (queryMap(url)["code"].orEmpty().isNotBlank()) return url
        val code = accessCode(BASE) ?: return null
        return "$url${if ('?' in url) '&' else '?'}code=${enc(code)}"
    }

    private fun listEndpoint(url: String): String {
        val query = queryMap(url)
        val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val limit = query["limit"]?.toIntOrNull()?.coerceAtLeast(1) ?: 20
        val lang = query["lang"] ?: DEFAULT_LANG
        return "$BASE/api/home?page=$page&limit=$limit&lang=${enc(lang)}"
    }

    private fun nextUrl(endpoint: String, rowCount: Int): String? {
        if (rowCount < PAGE_SIZE) return null
        val query = queryMap(endpoint)
        val page = query["page"]?.toIntOrNull() ?: 1
        if (page >= 100) return null
        return "$BASE/api/home?page=${page + 1}&limit=${query["limit"] ?: "20"}&lang=${enc(langOf(endpoint))}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("id", "shortId") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("total_num", "episode_count", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("cover_img", "cover", "poster", "image"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("episode", "number", "num") ?: return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = null,
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
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/(?:detail|watch|short|stream)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() } ?: LiveRuntimeConfig.accessCodeFor(url)

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

    private fun JSONObject.name(): String? = strAny("title", "name")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JSONObject.subtitleTracks(lang: String): List<SubtitleTrack> =
        listOfNotNull(
            strAny("subtitle", "subtitleUrl", "subTitle", "vtt", "srt")
                ?.let { SubtitleTrack("Indonesia", it, if (lang.equals("in", true)) "id" else lang) },
        )
}
