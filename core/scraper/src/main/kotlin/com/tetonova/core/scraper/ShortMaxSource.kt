package com.tetonova.core.scraper

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for ShortMax's GoodBos API documented at `/api.html`. */
object ShortMaxSource {
    private const val BASE = "https://shortmax.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"
    private val apiMutex = Mutex()

    fun isShortMax(url: String): Boolean =
        hostOf(url)?.contains("shortmax") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val rows = listRows(json)
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.dedupeByTitle(),
            nextUrl = null,
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val direct = getJson("$BASE/api/v1/search?lang=${enc(lang)}&q=${enc(cleanQuery)}")
            ?.let(::listRows)
            .orEmpty()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
        if (direct.isNotEmpty()) return direct.mapNotNull { dramaToItem(it, lang) }.dedupeByTitle()

        val matches = LinkedHashMap<String, LiveItem>()
        listOf("/api/v1/new", "/api/v1/popular", "/api/v1/hot", "/api/v1/ranking").forEach { path ->
            val endpoint = if (path.endsWith("ranking")) {
                "$BASE$path?lang=${enc(lang)}&type=monthly"
            } else {
                "$BASE$path?lang=${enc(lang)}&page=1"
            }
            getJson(endpoint)
                ?.let(::listRows)
                .orEmpty()
                .asSequence()
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
        val detail = detailInfo(id, lang)
        val epsData = allEpisodesData(id, lang)
        val info = detail ?: epsData
        val total = info?.intAny("episodes", "totalEpisodes", "episodeCount")
            ?: queryMap(url)["total"]?.toIntOrNull()
            ?: 0
        val episodes = epsData?.optJSONArray("episodes").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                (1..total.coerceAtLeast(0)).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang), info?.strAny("cover"))
                }
            }
        val resolvedTotal = total.takeIf { it > 0 } ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info?.name() ?: queryMap(url)["title"] ?: "ShortMax",
            cover = info?.strAny("cover") ?: queryMap(url)["cover"],
            synopsis = info?.strAny("summary", "description") ?: queryMap(url)["summary"],
            status = resolvedTotal?.let { "$it episode" },
            type = "Drama",
            studio = "ShortMax",
            released = null,
            genres = info?.labels().orEmpty(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = allEpisodesData(id, lang)
            ?.optJSONArray("episodes")
            .objects()
            .firstOrNull { it.intAny("episode", "number", "num") == episode }
            ?: return emptyList()
        val variants = row.optJSONObject("video").videoVariants()
            .ifEmpty { row.videoVariants() }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { resoRank(it.first) }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("ShortMax", variants.first().embedUrl, variants))
    }

    private suspend fun detailInfo(id: String, lang: String): JSONObject? =
        getJson("$BASE/api/v1/detail/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONObject("data")

    private suspend fun allEpisodesData(id: String, lang: String): JSONObject? =
        getJson("$BASE/api/v1/alleps/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONObject("data")

    private suspend fun getJson(url: String): JSONObject? {
        val endpoint = withCode(url) ?: return null
        return apiMutex.withLock {
            repeat(3) { attempt ->
                val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") }
                val json = body
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.takeIf { it.optString("error").isBlank() }
                if (json != null) return@withLock json
                if (attempt < 2) delay(350L * (attempt + 1))
            }
            null
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
        val out = LinkedHashMap<String, String>()
        query.forEach { (key, value) ->
            if (!key.equals("code", true)) out[key] = value
        }
        out["lang"] = lang
        return when {
            path.startsWith("/api/v1/popular") -> {
                out.putIfAbsent("page", "1")
                "$BASE/api/v1/popular${out.toQuery()}"
            }
            path.startsWith("/api/v1/new") -> {
                out.putIfAbsent("page", "1")
                "$BASE/api/v1/new${out.toQuery()}"
            }
            path.startsWith("/api/v1/hot") -> {
                out.putIfAbsent("page", "1")
                "$BASE/api/v1/hot${out.toQuery()}"
            }
            path.startsWith("/api/v1/ranking") -> {
                out.putIfAbsent("type", "monthly")
                "$BASE/api/v1/ranking${out.toQuery()}"
            }
            path.startsWith("/api/v1/category/") -> {
                out.putIfAbsent("pageSize", "20")
                "$BASE$path${out.toQuery()}"
            }
            else -> "$BASE/api/v1/new?lang=${enc(lang)}&page=1"
        }
    }

    private fun listRows(json: JSONObject): List<JSONObject> =
        json.optJSONArray("data").objects()
            .filter { it.idOrCode() != null && it.name() != null }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.idOrCode() ?: return null
        val title = row.name()?.stripHtmlTags() ?: return null
        val total = row.intAny("episodes", "totalEpisodes", "episodeCount")
        val cover = row.strAny("cover")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang, title, cover, row.strAny("summary"), total),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("episode", "number", "num") ?: return null
        return LiveEpisode(
            num = number,
            title = row.strAny("title", "episodeName", "name")?.let { "$it Episode $number" }
                ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("cover"),
        )
    }

    private fun JSONObject?.videoVariants(): List<Pair<String, String>> {
        if (this == null) return emptyList()
        return buildList {
            strAny("video_1080", "1080", "url_1080")?.let { add("1080p" to it) }
            strAny("video_720", "720", "url_720")?.let { add("720p" to it) }
            strAny("video_480", "480", "url_480")?.let { add("480p" to it) }
            strAny("videoUrl", "playUrl", "url", "m3u8")?.let { add(qualityLabel(it) to it) }
        }
    }

    private fun detailUrl(
        id: String,
        lang: String,
        title: String? = null,
        cover: String? = null,
        summary: String? = null,
        total: Int? = null,
    ): String {
        val query = LinkedHashMap<String, String>()
        query["lang"] = lang
        title?.takeIf { it.isNotBlank() }?.let { query["title"] = it }
        cover?.takeIf { it.isNotBlank() }?.let { query["cover"] = it }
        summary?.takeIf { it.isNotBlank() }?.take(500)?.let { query["summary"] = it }
        total?.takeIf { it > 0 }?.let { query["total"] = it.toString() }
        return "$BASE/shortmax/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/shortmax/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/shortmax/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/v1/(?:detail|alleps)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
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

    private fun resoRank(label: String): Int =
        Regex("\\d+").find(label)?.value?.toIntOrNull() ?: 0

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
    private fun String.stripHtmlTags(): String =
        replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).stripHtmlTags().ifBlank { null }
        }

    private fun JSONObject.idOrCode(): String? =
        strAny("id", "code")?.takeIf { it.any(Char::isDigit) }

    private fun JSONObject.name(): String? = strAny("name", "title")

    private fun JSONObject.labels(): List<String> =
        optJSONArray("tags").strings()
            .ifEmpty {
                optJSONArray("tags").objects().mapNotNull {
                    it.strAny("labelName", "name", "tagName")?.stripHtmlTags()
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
}
