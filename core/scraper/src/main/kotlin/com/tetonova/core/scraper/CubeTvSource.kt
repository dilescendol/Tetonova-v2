package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for CubeTV's GoodBos API documented at `/api.html`. */
object CubeTvSource {
    private const val BASE = "https://cubetv.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_MODULE_ID = "PaEpZ7"
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isCubeTv(url: String): Boolean =
        hostOf(url)?.contains("cubetv") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val rows = listRows(json, endpoint)
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
        return getJson("$BASE/api/search?lang=${enc(lang)}&q=${enc(cleanQuery)}&page=1")
            ?.let { listRows(it, "$BASE/api/search") }
            .orEmpty()
            .mapNotNull { dramaToItem(it, lang) }
            .dedupeByTitle()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val hints = queryMap(url)
        val info = getJson("$BASE/api/detail/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONObject("data")
        val episodesJson = getJson("$BASE/api/episodes/${encPath(id)}?lang=${enc(lang)}")
        val rows = episodesJson?.optJSONArray("rows").objects()
        val total = info?.intAny("totalEpisodeNum", "latestEpisodeNumber")
            ?: episodesJson?.intAny("total")
            ?: hints["total"]?.toIntOrNull()
            ?: rows.size
        val episodes = rows
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                (1..total.coerceAtLeast(0)).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang), info?.cover())
                }
            }
        if (info == null && episodes.isEmpty()) return null
        return LiveDetail(
            title = info?.name() ?: hints["title"] ?: "CubeTV",
            cover = info?.cover() ?: hints["cover"],
            synopsis = info?.strAny("summary", "description") ?: hints["intro"],
            status = total.takeIf { it > 0 }?.let { "$it episode" },
            type = if (info?.intAny("showType") == 2) "Anime" else "Drama",
            studio = "CubeTV",
            released = info?.strAny("releaseDate"),
            genres = info?.optJSONArray("tagInfo").strings().distinct(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = getJson("$BASE/api/episodes/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONArray("rows")
            .objects()
            .firstOrNull { it.intAny("episodeNumber", "episode", "num") == episode }
            ?: return emptyList()
        val variants = row.optJSONArray("videoUrls").objects()
            .mapNotNull { video ->
                val streamUrl = video.strAny("url", "playUrl", "videoUrl") ?: return@mapNotNull null
                val label = video.strAny("quality", "label")?.uppercase() ?: qualityLabel(streamUrl)
                label to streamUrl
            }
            .distinctBy { it.second }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(
            VideoServer(
                name = "CubeTV",
                embedUrl = variants.first().embedUrl,
                variants = variants,
                subtitles = row.subtitleTracks(),
            ),
        )
    }

    private suspend fun getJson(url: String): JSONObject? {
        val endpoint = withCode(url) ?: return null
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
        val out = LinkedHashMap<String, String>()
        query.forEach { (key, value) ->
            if (!key.equals("code", true) && key != "q") out[key] = value
        }
        out["lang"] = query["lang"] ?: DEFAULT_LANG
        out["page"] = query["page"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: "1"
        return when {
            path.startsWith("/api/home") -> {
                out.putIfAbsent("navid", query["navid"].orEmpty())
                "$BASE/api/home${out.toQuery()}"
            }
            path.startsWith("/api/anime") -> {
                out.putIfAbsent("moduleid", query["moduleid"] ?: "D0RxZA")
                "$BASE/api/anime${out.toQuery()}"
            }
            path.startsWith("/api/search") -> {
                query["q"]?.let { out["q"] = it }
                "$BASE/api/search${out.toQuery()}"
            }
            else -> {
                out.putIfAbsent("moduleid", query["moduleid"] ?: DEFAULT_MODULE_ID)
                "$BASE/api/module${out.toQuery()}"
            }
        }
    }

    private fun listRows(json: JSONObject, endpoint: String): List<JSONObject> =
        when (canonicalPath(endpoint).lowercase()) {
            "/api/home" -> json.optJSONArray("rows").objects().flatMap { it.optJSONArray("videos").objects() }
            else -> json.optJSONArray("rows").objects()
        }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val path = canonicalPath(endpoint).lowercase()
        if (path == "/api/home") return null
        val rows = json.optJSONArray("rows").objects()
        if (rows.isEmpty()) return null
        val query = queryMap(endpoint).toMutableMap()
        val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val total = json.optInt("total", 0)
        if (total > 0 && page * rows.size >= total) return null
        if (page >= 200) return null
        query.remove("code")
        query["page"] = (page + 1).toString()
        query["lang"] = query["lang"] ?: DEFAULT_LANG
        return "$BASE$path${query.toQuery()}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("videoid", "id") ?: return null
        val title = row.name() ?: return null
        val cover = row.cover()
        val total = row.intAny("totalEpisodeNum", "episodeCount", "episodes")
        return LiveItem(
            title = title,
            url = detailUrl(
                id = id,
                lang = lang,
                title = title,
                cover = cover,
                intro = row.strAny("summary", "description"),
                total = total,
            ),
            cover = cover,
            type = if (row.intAny("showType") == 2) "Anime" else "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("episodeNumber", "episode", "num") ?: return null
        if (number <= 0) return null
        return LiveEpisode(
            num = number,
            title = row.strAny("episodeTitle", "title", "name") ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.cover(),
        )
    }

    private fun JSONObject.subtitleTracks(): List<SubtitleTrack> =
        optJSONArray("subtitles").objects()
            .mapNotNull { row ->
                val url = absoluteUrl(row.strAny("url", "src", "file")) ?: return@mapNotNull null
                val language = row.strAny("lang", "language")?.normalizeLanguage()
                SubtitleTrack(
                    label = language?.languageLabel() ?: row.strAny("name", "title") ?: "Subtitle",
                    url = url,
                    language = language,
                )
            }
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<SubtitleTrack> { it.language == "id" }
                    .thenBy { it.label.lowercase() },
            )

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
        return "$BASE/cubetv/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/cubetv/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: queryMap(url)["videoid"]
            ?: Regex("/cubetv/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/(?:detail|episodes)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun List<LiveItem>.dedupeByTitle(): List<LiveItem> =
        distinctBy { cleanTitleKey(it.title).ifBlank { it.url.lowercase() } }

    private fun cleanTitleKey(value: String): String =
        value.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun qualityLabel(url: String): String =
        Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "HLS"

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
        trim().lowercase().replace('_', '-').let {
            when (it) {
                "in", "ind", "id-id" -> "id"
                "zh-hans", "zh_cn", "zh-cn" -> "zh-Hans"
                else -> it
            }
        }

    private fun String.languageLabel(): String =
        when (lowercase()) {
            "id", "in" -> "Indonesia"
            "en" -> "English"
            "es" -> "Espanol"
            "pt" -> "Portugues"
            "ko" -> "Korean"
            "ja" -> "Japanese"
            "vi" -> "Vietnamese"
            "fr" -> "French"
            "th" -> "Thai"
            "de" -> "German"
            "ms" -> "Malay"
            "zh-hans", "zh" -> "Chinese"
            "ar" -> "Arabic"
            else -> uppercase()
        }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).trim().ifBlank { null }
        }

    private fun JSONObject.name(): String? = strAny("videoName", "title", "name")

    private fun JSONObject.cover(): String? =
        absoluteUrl(strAny("cover", "bannerImg", "thumbnail", "poster"))

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
