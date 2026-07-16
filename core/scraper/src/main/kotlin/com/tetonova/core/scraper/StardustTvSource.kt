package com.tetonova.core.scraper

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for StardustTV's GoodBos API documented at `/api.html`. */
object StardustTvSource {
    private const val BASE = "https://stardusttv2.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"
    private val apiMutex = Mutex()

    fun isStardustTv(url: String): Boolean =
        hostOf(url)?.contains("stardusttv") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = true) ?: return LivePage(emptyList())
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
        val json = getJson(
            "$BASE/api/v1/search?q=${enc(cleanQuery)}&page=1&per_page=30&lang=${enc(lang)}",
            needsCode = false,
        ) ?: return emptyList()
        return listRows(json)
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .toList()
            .dedupeByTitle()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val info = videoInfo(id, lang)
        val episodeRows = episodeRows(id, lang).ifEmpty {
            info?.optJSONArray("episodes").objects()
        }
        val total = info?.intAny("episode_total", "episode_count")
            ?: queryMap(url)["total"]?.toIntOrNull()
            ?: episodeRows.size
        val episodes = episodeRows
            .mapIndexedNotNull { index, row -> episodeToLive(id, lang, row, index + 1) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                (1..total.coerceAtLeast(0)).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang))
                }
            }
        return LiveDetail(
            title = info?.name() ?: queryMap(url)["title"] ?: "StardustTV",
            cover = info?.cover() ?: queryMap(url)["cover"],
            synopsis = info?.strAny("intro", "description", "summary") ?: queryMap(url)["intro"],
            status = total.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "StardustTV",
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
        val row = episodeRows(id, lang)
            .firstOrNull { it.intAny("sort", "episode", "number") == episode }
            ?: return emptyList()
        val variants = buildList {
            row.strAny("filepath", "play_url", "url")?.let { add("Auto (H.264)" to it) }
            row.strAny("auto_filepath")?.let { add("Auto (H.265)" to it) }
        }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("StardustTV", variants.first().embedUrl, variants))
    }

    private suspend fun videoInfo(id: String, lang: String): JSONObject? =
        getJson("$BASE/api/v1/video/${encPath(id)}?lang=${enc(lang)}", needsCode = true)
            ?.optJSONObject("data")

    private suspend fun episodeRows(id: String, lang: String): List<JSONObject> =
        getJson("$BASE/api/v1/video/${encPath(id)}/episodes?lang=${enc(lang)}", needsCode = true)
            ?.optJSONArray("data")
            .objects()

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        return apiMutex.withLock {
            repeat(3) { attempt ->
                val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") }
                val json = body
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.takeIf { it.optString("error").isBlank() && it.optInt("code", 0) == 0 }
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
        return when {
            path == "/api/v1/trending" ->
                "$BASE/api/v1/trending?lang=${enc(lang)}"
            path == "/api/v1/videos" -> {
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val perPage = query["per_page"]?.toIntOrNull()?.coerceIn(1, 30) ?: 18
                "$BASE/api/v1/videos?page=$page&per_page=$perPage&lang=${enc(lang)}"
            }
            else -> "$BASE/api/v1/videos?page=1&per_page=18&lang=${enc(lang)}"
        }
    }

    private fun listRows(json: JSONObject): List<JSONObject> {
        val data = json.opt("data")
        return when (data) {
            is JSONArray -> data.objects()
            is JSONObject -> data.optJSONArray("data").objects()
            else -> emptyList()
        }.filter { it.idOrCode() != null && it.name() != null }
    }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems || canonicalPath(endpoint).lowercase() != "/api/v1/videos") return null
        val data = json.optJSONObject("data") ?: return null
        val current = data.optInt("current_page", queryMap(endpoint)["page"]?.toIntOrNull() ?: 1)
        val last = data.optInt("last_page", current)
        if (current >= last || current >= 200) return null
        val perPage = data.optInt("per_page", queryMap(endpoint)["per_page"]?.toIntOrNull() ?: 18)
        val lang = langOf(endpoint)
        return "$BASE/api/v1/videos?page=${current + 1}&per_page=$perPage&lang=${enc(lang)}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.idOrCode() ?: return null
        val title = row.name()?.stripHtmlTags() ?: return null
        val cover = row.cover()
        val intro = row.strAny("intro", "description", "summary")
        val total = row.intAny("episode_total", "episode_count")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang, title, cover, intro, total),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(
        id: String,
        lang: String,
        row: JSONObject,
        fallbackNumber: Int,
    ): LiveEpisode? {
        val number = row.intAny("sort", "episode", "number") ?: fallbackNumber
        if (number <= 0) return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("snapshot_url", "cover", "thumb"),
        )
    }

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
        return "$BASE/stardusttv/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/stardusttv/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/stardusttv/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/v1/video/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun matchesQuery(title: String, query: String): Boolean {
        val haystack = cleanTitleKey(title)
        val needle = cleanTitleKey(query)
        if (needle.isBlank()) return false
        if (needle in haystack) return true
        val words = needle.split(' ').filter { it.length >= 3 }
        return words.isNotEmpty() && words.all { it in haystack }
    }

    private fun List<LiveItem>.dedupeByTitle(): List<LiveItem> =
        distinctBy { cleanTitleKey(it.title).ifBlank { it.url.lowercase() } }

    private fun cleanTitleKey(value: String): String =
        value.stripHtmlTags()
            .lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

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

    private fun JSONObject.idOrCode(): String? =
        strAny("id", "code")?.takeIf { it.any(Char::isDigit) }

    private fun JSONObject.name(): String? = strAny("english_name", "title", "name")

    private fun JSONObject.cover(): String? =
        strAny("cover_path", "cover_snapshot_path", "alioss_cover", "cover")

    private fun JSONObject?.labels(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            listOf("labels", "label", "search_label").forEach { key ->
                optJSONArray(key).objects().forEach { row ->
                    row.strAny("english_name", "name", "title")?.let(::add)
                }
            }
        }.distinct()
    }

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
