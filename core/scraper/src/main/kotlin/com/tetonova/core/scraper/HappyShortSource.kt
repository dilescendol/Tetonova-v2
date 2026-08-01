package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for HappyShort's GoodBos API documented at `/api`. */
object HappyShortSource {
    private const val BASE = "https://happyshort.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_SIZE = 20
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isHappyShort(url: String): Boolean =
        hostOf(url)?.contains("happyshort") == true

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
        return getJson("$BASE/api/hs/search?q=${enc(cleanQuery)}&lang=${enc(lang)}", needsCode = false)
            ?.let(::listRows)
            .orEmpty()
            .mapNotNull { dramaToItem(it, lang) }
            .toList()
            .dedupeByTitle()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val hints = queryMap(url)
        val detail = getJson("$BASE/api/hs/detail?id=${enc(id)}&lang=${enc(lang)}", needsCode = false)
        val video = detail?.optJSONObject("video")
        val epsJson = getJson("$BASE/api/hs/episodes?id=${enc(id)}&lang=${enc(lang)}", needsCode = false)
        val episodeRows = epsJson?.optJSONArray("episodes").objects()
        val total = epsJson?.intAny("total")
            ?: detail?.optJSONObject("episode_stats")?.intAny("total")
            ?: video?.intAny("episode_num")
            ?: hints["total"]?.toIntOrNull()
            ?: episodeRows.size
        val episodes = episodeRows
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                (1..total.coerceAtLeast(0)).map { number ->
                    LiveEpisode(number, "Episode $number", watchUrl(id, number, lang))
                }
            }
        if (video == null && episodes.isEmpty()) return null
        return LiveDetail(
            title = video?.name() ?: hints["title"] ?: "HappyShort",
            cover = video?.cover() ?: hints["cover"],
            synopsis = video?.strAny("introduce", "description", "summary") ?: hints["intro"],
            status = total.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "HappyShort",
            released = video?.strAny("created_at"),
            genres = video?.labels().orEmpty(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val episodeId = getJson("$BASE/api/hs/episodes?id=${enc(id)}&lang=${enc(lang)}", needsCode = false)
            ?.optJSONArray("episodes")
            .objects()
            .firstOrNull { it.intAny("order") == episode }
            ?.strAny("id")
        val epParam = episodeId ?: episode.toString()
        val play = getJson("$BASE/api/hs/play?id=${enc(id)}&ep=${enc(epParam)}&lang=${enc(lang)}", needsCode = true)
            ?: return emptyList()
        val streamUrl = play.strAny("cdn_url", "url", "play_url", "video_url")
            ?: play.strAny("stream_url")?.let { absoluteUrl(it) }
            ?: return emptyList()
        val variants = listOf(ServerVariant(qualityLabel(streamUrl), streamUrl))
        return listOf(VideoServer("HappyShort", streamUrl, variants))
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
        return when (path) {
            "/api/hs/foryou" -> "$BASE/api/hs/foryou?lang=${enc(lang)}"
            "/api/hs/search" -> {
                val q = query["q"].orEmpty()
                "$BASE/api/hs/search?q=${enc(q)}&lang=${enc(lang)}"
            }
            else -> {
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val size = query["size"]?.toIntOrNull()?.coerceIn(1, 50) ?: DEFAULT_SIZE
                "$BASE/api/hs/home?page=$page&size=$size&lang=${enc(lang)}"
            }
        }
    }

    private fun listRows(json: JSONObject): List<JSONObject> =
        json.optJSONArray("dramas").objects()
            .ifEmpty {
                val data = json.optJSONObject("data") ?: return@ifEmpty emptyList()
                data.optJSONArray("list").objects()
                    .flatMap { section -> section.optJSONArray("find").objects() }
            }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems || canonicalPath(endpoint).lowercase() != "/api/hs/home") return null
        if (!json.optBoolean("has_next", false)) return null
        val direct = json.strAny("next_url")
        if (!direct.isNullOrBlank()) return absoluteUrl(direct)
        val query = queryMap(endpoint).toMutableMap()
        val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        if (page >= 200) return null
        query["page"] = (page + 1).toString()
        query["size"] = query["size"] ?: DEFAULT_SIZE.toString()
        query["lang"] = query["lang"] ?: DEFAULT_LANG
        return "$BASE/api/hs/home${query.toQuery()}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("id", "video_id") ?: return null
        val title = row.name()?.stripHtmlTags() ?: return null
        val cover = row.cover()
        val total = row.intAny("episode_num", "episodeCount", "totalEpisode", "total")
        return LiveItem(
            title = title,
            url = detailUrl(
                id = id,
                lang = lang,
                title = title,
                cover = cover,
                intro = row.strAny("introduce", "description", "summary"),
                total = total,
            ),
            cover = cover,
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("order", "episode", "number", "num") ?: return null
        if (number <= 0) return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("cover", "thumb"),
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
        return "$BASE/happyshort/detail/${encPath(id)}" + query.toQuery()
    }

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/happyshort/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: queryMap(url)["video_id"]
            ?: Regex("/happyshort/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/hs/(?:detail|episodes|play|stream)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: queryMap(url)["bookId"]

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/api/hs/stream/[^/?#]+/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun List<LiveItem>.dedupeByTitle(): List<LiveItem> =
        distinctBy { cleanTitleKey(it.title).ifBlank { it.url.lowercase() } }

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

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() }
            ?: LiveRuntimeConfig.accessCodeFor(url)
            ?: DEFAULT_ACCESS_CODE

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: DEFAULT_LANG

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).path.ifBlank { "/" } }.getOrDefault("/")

    private fun absoluteUrl(url: String): String =
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("/") -> BASE + url
            else -> "$BASE/$url"
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
    private fun String.stripHtmlTags(): String =
        replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").trim()

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).stripHtmlTags().ifBlank { null }
        }

    private fun JSONObject.name(): String? = strAny("name", "video_name", "title")
    private fun JSONObject.cover(): String? = strAny("cover", "big_cover", "gif_cover")

    private fun JSONObject.labels(): List<String> =
        (optJSONArray("labels").strings() +
            optString("spoken_language").trim().takeIf { it.isNotBlank() }.let { listOfNotNull(it) })
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
