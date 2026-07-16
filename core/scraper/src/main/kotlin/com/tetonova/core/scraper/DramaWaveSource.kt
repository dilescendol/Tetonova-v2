package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for DramaWave's GoodBos API documented at `/api.html`. */
object DramaWaveSource {
    private const val BASE = "https://dramawave.goodbos.online"
    private const val DEFAULT_LANG = "in"
    private const val DEFAULT_LIST_PATH = "/api/recommend"
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isDramaWave(url: String): Boolean =
        hostOf(url)?.contains("dramawave") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = needsCode(endpoint)) ?: return LivePage(emptyList())
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
        val json = getJson(
            "$BASE/api/search?q=${enc(cleanQuery)}&lang=${enc(lang)}&next=1",
            needsCode = true,
        ) ?: return emptyList()
        return json.optJSONArray("list").objects()
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val data = detailData(id, lang) ?: return null
        val episodes = data.optJSONArray("items").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val first = data.optJSONArray("items").objects().firstOrNull()
        val total = data.intAny("episode_count") ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = first?.name() ?: "DramaWave",
            cover = data.strAny("cover") ?: first?.strAny("cover"),
            synopsis = data.strAny("desc", "description") ?: first?.strAny("desc", "description"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "DramaWave",
            released = null,
            genres = first?.arrayStrings("series_tag").orEmpty().distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = detailData(id, lang)
            ?.optJSONArray("items")
            .objects()
            .firstOrNull { it.intAny("serial_number") == episode }
            ?: return emptyList()
        val rawVariants = buildList {
            row.str("m3u8_path")?.let { add("HLS" to it) } ?: run {
                row.str("1080p_mp4")?.let { add("1080p" to it) }
                row.str("720p_mp4")?.let { add("720p" to it) }
                row.str("540p_mp4")?.let { add("540p" to it) }
            }
        }
        val variants = rawVariants
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("DramaWave", variants.first().embedUrl, variants, row.subtitleTracks()))
    }

    private suspend fun detailData(id: String, lang: String): JSONObject? =
        getJson("$BASE/api/drama/${encPath(id)}?lang=${enc(lang)}", needsCode = true)
            ?.optJSONObject("data")

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { it.optInt("code", 200) in listOf(0, 200) && it.optString("error").isBlank() }
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
            "/api/recommend",
            "/api/anime",
            "/api/new-drama",
            -> path
            else -> DEFAULT_LIST_PATH
        }
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        query.forEach { (key, value) ->
            if (!key.equals("code", true)) out[key] = value
        }
        out["lang"] = upstreamLang(out["lang"] ?: query["lang"] ?: DEFAULT_LANG)
        if (cleanPath != "/api/home") out.putIfAbsent("next", "1")
        return BASE + cleanPath + out.toQuery()
    }

    private fun dramaRows(json: JSONObject, endpoint: String): List<JSONObject> =
        when (canonicalPath(endpoint).lowercase()) {
            "/api/home" -> json.optJSONArray("data").objects().flatMap { it.optJSONArray("items").objects() }
            "/api/search" -> json.optJSONArray("list").objects()
            else -> json.optJSONArray("items").objects()
        }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val pageInfo = json.optJSONObject("page_info") ?: return null
        if (!pageInfo.optBoolean("has_more", false)) return null
        val next = pageInfo.strAny("next") ?: return null
        val query = queryMap(endpoint).toMutableMap()
        query["next"] = next
        return BASE + canonicalPath(endpoint).lowercase() + query.toQuery()
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("playlet_id", "id") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("episode_count")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("cover"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("serial_number") ?: return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("cover"),
        )
    }

    private fun JSONObject.subtitleTracks(): List<SubtitleTrack> =
        (optJSONArray("subtitle_list").objects() + optJSONArray("subtitles").objects() + optJSONArray("subtitleTracks").objects())
            .mapNotNull { row ->
                val url = absoluteUrl(row.strAny("subtitle", "url", "src", "file")) ?: return@mapNotNull null
                val language = row.strAny("language", "lang")?.normalizeLanguage()
                SubtitleTrack(
                    label = row.strAny("display_name", "name", "title")
                        ?: language?.languageLabel()
                        ?: "Subtitle",
                    url = url,
                    language = language,
                )
            }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<SubtitleTrack> { it.language in listOf("id", "in") }
                    .thenBy { it.label.lowercase() },
            )

    private fun needsCode(url: String): Boolean =
        canonicalPath(url).lowercase() in setOf("/api/new-drama", "/api/search", "/api/drama")

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
        "$BASE/dramawave/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/dramawave/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/dramawave/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() }
            ?: LiveRuntimeConfig.accessCodeFor(url)
            ?: DEFAULT_ACCESS_CODE

    private fun upstreamLang(lang: String): String = if (lang.equals("id", true)) DEFAULT_LANG else lang
    private fun langOf(url: String): String = upstreamLang(queryMap(url)["lang"] ?: DEFAULT_LANG)

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).rawPath.orEmpty().ifBlank { "/" } }.getOrDefault("/")

    private fun absoluteUrl(url: String?): String? {
        val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("/") -> BASE + clean
            else -> "$BASE/$clean"
        }
    }

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

    private fun String.normalizeLanguage(): String =
        trim().lowercase().replace('_', '-').substringBefore('-').let {
            when (it) {
                "in", "ind" -> "id"
                else -> it
            }
        }

    private fun String.languageLabel(): String =
        when (lowercase()) {
            "id", "in" -> "Indonesia"
            "en" -> "English"
            else -> uppercase()
        }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).trim().ifBlank { null }
        }

    private fun JSONObject.name(): String? = strAny("name", "title")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JSONObject.arrayStrings(vararg keys: String): List<String> =
        keys.flatMap { key ->
            optJSONArray(key)?.strings()
                ?: str(key)?.split(',', '·')?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
        }
}
