package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for Velolo's GoodBos API documented at `/api.html`. */
object VeloloSource {
    private const val BASE = "https://velolo.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val PAGE_SIZE = 18

    fun isVelolo(url: String): Boolean =
        hostOf(url)?.contains("velolo") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val rows = json.optJSONArray("rows").objects()
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
        val json = getJson("$BASE/search?q=${enc(cleanQuery)}&lang=${enc(lang)}")
            ?: return emptyList()
        return json.optJSONArray("rows").objects()
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .toList()
            .dedupeByTitle()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val hints = queryMap(url)
        val data = detailData(id, lang)
        if (data == null) {
            val totalHint = hints["total"]?.toIntOrNull() ?: 0
            if (totalHint <= 0) return null
            return LiveDetail(
                title = hints["title"] ?: "Velolo",
                cover = hints["cover"],
                synopsis = hints["intro"],
                status = "$totalHint episode",
                type = "Drama",
                studio = "Velolo",
                released = null,
                genres = emptyList(),
                episodes = (1..totalHint).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang))
                },
                url = detailUrl(id, lang),
            )
        }
        val info = data.optJSONObject("videoInfo")
        val episodeInfo = data.optJSONObject("episodesInfo")
        val rows = episodeInfo?.optJSONArray("rows").objects()
        val total = episodeInfo?.intAny("total")
            ?: info?.intAny("episode")
            ?: hints["total"]?.toIntOrNull()
            ?: rows.size
        val episodes = rows
            .mapIndexedNotNull { index, row -> episodeToLive(id, lang, row, index + 1) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                (1..total.coerceAtLeast(0)).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang))
                }
            }
        return LiveDetail(
            title = info?.name() ?: hints["title"] ?: "Velolo",
            cover = info?.cover() ?: hints["cover"],
            synopsis = info?.strAny("introduction", "intro", "description") ?: hints["intro"],
            status = total.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "Velolo",
            released = info?.strAny("shelfTime"),
            genres = info.labels(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val rows = detailData(id, lang)
            ?.optJSONObject("episodesInfo")
            ?.optJSONArray("rows")
            .objects()
        val row = rows.firstOrNull { (it.intAny("orderNumber") ?: -1) + 1 == episode }
            ?: rows.firstOrNull { it.intAny("episode", "number") == episode }
            ?: return emptyList()
        val streamUrl = row.strAny("videoAddress", "url", "playUrl") ?: return emptyList()
        val variants = listOf(ServerVariant(qualityLabel(streamUrl), streamUrl))
        return listOf(VideoServer("Velolo", streamUrl, variants))
    }

    private suspend fun detailData(id: String, lang: String): JSONObject? =
        getJson("$BASE/drama/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONObject("data")

    private suspend fun getJson(url: String): JSONObject? {
        repeat(3) { attempt ->
            val body = LiveClient.getHtml(url) { it.trimStart().startsWith("{") }
            val json = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optInt("code", 200) == 200 || it.optInt("code", 0) == 0 }
            if (json != null) return json
            if (attempt < 2) delay(250L * (attempt + 1))
        }
        return null
    }

    private fun listEndpoint(url: String): String {
        val path = canonicalPath(url).lowercase()
        val query = queryMap(url)
        val lang = query["lang"] ?: DEFAULT_LANG
        return when (path) {
            "/hot" -> "$BASE/hot?lang=${enc(lang)}"
            "/new" -> {
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = query["page_size"]?.toIntOrNull()?.coerceIn(1, 50) ?: PAGE_SIZE
                "$BASE/new?lang=${enc(lang)}&page=$page&page_size=$size"
            }
            "/category" -> {
                val labelId = query["labelId"].orEmpty()
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = query["page_size"]?.toIntOrNull()?.coerceIn(1, 50) ?: PAGE_SIZE
                "$BASE/category?labelId=${enc(labelId)}&lang=${enc(lang)}&page=$page&page_size=$size"
            }
            else -> {
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = query["page_size"]?.toIntOrNull()?.coerceIn(1, 50) ?: PAGE_SIZE
                "$BASE/home?lang=${enc(lang)}&page=$page&page_size=$size"
            }
        }
    }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val path = canonicalPath(endpoint).lowercase()
        if (path == "/hot") return null
        val query = queryMap(endpoint).toMutableMap()
        val current = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val size = query["page_size"]?.toIntOrNull()?.coerceAtLeast(1) ?: PAGE_SIZE
        val total = json.optInt("total", 0)
        if (total > 0 && current * size >= total) return null
        if (current >= 200) return null
        query["page"] = (current + 1).toString()
        query["page_size"] = size.toString()
        return "$BASE$path${query.toQuery()}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("id") ?: return null
        val title = row.name()?.stripHtmlTags() ?: return null
        val cover = row.cover()
        val total = row.intAny("episode")
        return LiveItem(
            title = title,
            url = detailUrl(
                id = id,
                lang = lang,
                title = title,
                cover = cover,
                intro = row.strAny("introduction", "intro", "description"),
                total = total,
            ),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject, fallbackNumber: Int): LiveEpisode? {
        val number = (row.intAny("orderNumber")?.let { it + 1 }
            ?: row.intAny("episode", "number")
            ?: fallbackNumber)
        if (number <= 0) return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = null,
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
        return "$BASE/velolo/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/velolo/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/velolo/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun matchesQuery(title: String, query: String): Boolean =
        LiveSource.matchesSearchQuery(title, query)

    private fun List<LiveItem>.dedupeByTitle(): List<LiveItem> =
        distinctBy { cleanTitleKey(it.title).ifBlank { it.url.lowercase() } }

    private fun cleanTitleKey(value: String): String =
        value.stripHtmlTags()
            .lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

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

    private fun qualityLabel(url: String): String =
        Regex("[./](\\d{3,4})p(?:[?./]|$)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "Auto"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")
    private fun String.dec(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    private fun String.stripHtmlTags(): String =
        replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.name(): String? = strAny("name", "title")

    private fun JSONObject.cover(): String? = strAny("cover", "poster", "image")

    private fun JSONObject?.labels(): List<String> {
        if (this == null) return emptyList()
        val raw = opt("label")
        return when (raw) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).trim().ifBlank { null } }
            is String -> raw.split(',', '/', '|').map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
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
