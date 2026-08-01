package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for NetShort's GoodBos API documented at `/api.html`. */
object NetShortSource {
    private const val BASE = "https://netshort.goodbos.online"
    private const val DEFAULT_LANG = "in"
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isNetShort(url: String): Boolean =
        hostOf(url)?.contains("netshort") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = false) ?: return LivePage(emptyList())
        val rows = listRows(json)
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.dedupeByTitle(),
            nextUrl = nextUrl(endpoint, json, rows.isNotEmpty()),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val direct = getJson("$BASE/api/search?lang=${enc(lang)}&q=${enc(cleanQuery)}&page=1", needsCode = false)
            ?.let(::listRows)
            .orEmpty()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
        if (direct.isNotEmpty()) return direct.mapNotNull { dramaToItem(it, lang) }.dedupeByTitle()

        val matches = LinkedHashMap<String, LiveItem>()
        for (page in 1..5) {
            val rows = getJson("$BASE/api/home/$page?lang=${enc(lang)}", needsCode = false)
                ?.let(::listRows)
                .orEmpty()
            if (rows.isEmpty()) break
            rows.asSequence()
                .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
                .mapNotNull { dramaToItem(it, lang) }
                .forEach { matches.putIfAbsent(titleDedupeKey(it), it) }
            if (matches.size >= 30) return matches.values.toList()
        }
        return matches.values.toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val info = getJson("$BASE/api/drama/${encPath(id)}?lang=${enc(lang)}", needsCode = false)
            ?.optJSONObject("data")
            ?: return null
        val rows = info.optJSONArray("shortPlayEpisodeList").objects()
        val total = info.intAny("totalEpisode", "episodeCount", "maxEps", "total")
        val episodes = rows
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                (1..(total ?: 0)).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang), coverUrl(info.strAny("shortPlayCover")))
                }
            }
        val resolvedTotal = total ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: queryMap(url)["title"] ?: "NetShort",
            cover = coverUrl(info.strAny("shortPlayCover", "cover", "episodeCover") ?: queryMap(url)["cover"]),
            synopsis = info.strAny("introduction", "description", "summary"),
            status = resolvedTotal?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "NetShort",
            released = null,
            genres = info.labels(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val data = getJson("$BASE/api/watch/${encPath(id)}/$episode?lang=${enc(lang)}", needsCode = true)
            ?.optJSONObject("data")
            ?: return emptyList()
        val variants = buildList {
            data.strAny("videoUrl", "playUrl", "url")?.let { add("Auto" to it) }
            data.optJSONArray("videos").objects().forEach { video ->
                val streamUrl = video.strAny("url", "playUrl", "videoUrl") ?: return@forEach
                add((video.strAny("quality", "label") ?: qualityLabel(streamUrl)) to streamUrl)
            }
        }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("NetShort", variants.first().embedUrl, variants, data.subtitleTracks(lang)))
    }

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf {
                it.optString("error").isBlank() &&
                    it.optString("message").lowercase() != "invalid or expired code"
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
            path == "/api/banner" -> "$BASE/api/banner?lang=${enc(lang)}"
            path.startsWith("/api/list/") -> {
                val page = path.substringAfterLast('/').toIntOrNull()?.coerceAtLeast(1)
                    ?: query["page"]?.toIntOrNull()?.coerceAtLeast(1)
                    ?: 1
                "$BASE/api/list/$page" + query.filterKeys { !it.equals("page", true) && !it.equals("code", true) }
                    .toMutableMap()
                    .apply { put("lang", lang) }
                    .toQuery()
            }
            path.startsWith("/api/home/") -> {
                val page = path.substringAfterLast('/').toIntOrNull()?.coerceAtLeast(1)
                    ?: query["page"]?.toIntOrNull()?.coerceAtLeast(1)
                    ?: 1
                "$BASE/api/home/$page?lang=${enc(lang)}"
            }
            else -> "$BASE/api/home/1?lang=${enc(lang)}"
        }
    }

    private fun listRows(json: JSONObject): List<JSONObject> {
        val data = json.optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("contentInfos").objects()
            .ifEmpty { data.optJSONArray("dataList").objects() }
            .ifEmpty { data.optJSONArray("searchCodeSearchResult").objects() }
            .ifEmpty { data.optJSONArray("searchResult").objects() }
            .ifEmpty { data.optJSONArray("records").objects() }
    }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val path = canonicalPath(endpoint).lowercase()
        if (!path.startsWith("/api/home/") && !path.startsWith("/api/list/")) return null
        val data = json.optJSONObject("data") ?: return null
        if (data.optBoolean("completed", false)) return null
        val page = path.substringAfterLast('/').toIntOrNull() ?: 1
        if (page >= 100) return null
        val query = queryMap(endpoint).toMutableMap()
        query["lang"] = query["lang"] ?: DEFAULT_LANG
        val nextPath = path.substringBeforeLast('/') + "/" + (page + 1)
        return BASE + nextPath + query.toQuery()
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("shortPlayId", "id") ?: return null
        val title = row.name()?.stripHtmlTags() ?: return null
        val cover = coverUrl(row.strAny("shortPlayCover", "episodeCover", "cover", "highImage"))
        val total = row.intAny("totalEpisode", "episodeCount", "shortPlayEpisodeCount", "maxEpisode")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang, title, cover),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("episodeNo", "episode", "number", "num") ?: return null
        return LiveEpisode(
            num = number,
            title = row.strAny("title", "episodeName") ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = coverUrl(row.strAny("episodeCover", "cover", "thumb")),
        )
    }

    private fun detailUrl(id: String, lang: String, title: String? = null, cover: String? = null): String {
        val query = LinkedHashMap<String, String>()
        query["lang"] = lang
        title?.takeIf { it.isNotBlank() }?.let { query["title"] = it }
        cover?.takeIf { it.isNotBlank() }?.let { query["cover"] = it }
        return "$BASE/netshort/detail/${encPath(id)}" + query.toQuery()
    }

    private fun coverUrl(url: String?): String? {
        val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return if (clean.startsWith("https://awscover.netshort.com/", ignoreCase = true)) {
            "http://" + clean.removePrefix("https://")
        } else {
            clean
        }
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/netshort/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: queryMap(url)["bookId"]
            ?: Regex("/netshort/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/(?:drama|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/api/watch/[^/?#]+/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun matchesQuery(title: String, query: String): Boolean =
        LiveSource.matchesSearchQuery(title, query)

    private fun List<LiveItem>.dedupeByTitle(): List<LiveItem> =
        distinctBy(::titleDedupeKey)

    private fun titleDedupeKey(item: LiveItem): String =
        cleanTitleKey(item.title).ifBlank { item.url.lowercase() }

    private fun cleanTitleKey(value: String): String =
        value.stripHtmlTags()
            .lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun qualityLabel(url: String): String =
        Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "Auto"

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
    private fun String.stripHtmlTags(): String =
        replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).stripHtmlTags().ifBlank { null }
        }

    private fun JSONObject.name(): String? = strAny("shortPlayName", "title", "name")

    private fun JSONObject.labels(): List<String> =
        optJSONArray("shortPlayLabels").strings()
            .ifEmpty {
                optJSONArray("labelArray").objects().mapNotNull {
                    it.strAny("tagName", "labelName", "name")?.stripHtmlTags()
                }
            }
            .distinct()

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

    private fun JSONObject.subtitleTracks(lang: String): List<SubtitleTrack> = buildList {
        strAny("subtitle", "subtitleUrl", "vtt", "srt")?.let { add(SubtitleTrack("Indonesia", it, normalizeLang(lang))) }
        val arr = optJSONArray("subtitles") ?: optJSONArray("subtitleTracks") ?: return@buildList
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i)
            val url = row?.strAny("url", "file", "src", "subtitle", "subtitleUrl")
                ?: arr.optString(i).trim().takeIf { it.startsWith("http", true) }
                ?: continue
            add(SubtitleTrack(row?.strAny("label", "name", "language") ?: "Indonesia", url, normalizeLang(lang)))
        }
    }.distinctBy { it.url }

    private fun normalizeLang(lang: String): String =
        if (lang.equals("in", true)) "id" else lang.lowercase()
}
