package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for MicroDrama's GoodBos API documented at `/microdrama-api.html`. */
object MicroDramaSource {
    private const val BASE = "https://drakula.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_LIMIT = 20
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isMicroDrama(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val lower = url.lowercase()
        return "microdrama" in host || (host == "drakula.goodbos.online" && "microdrama" in lower)
    }

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val rows = listRows(json)
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.distinctBy { it.url },
            nextUrl = nextUrl(endpoint, json, rows.size),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val json = getJson("$BASE/api/microdrama/search?q=${enc(cleanQuery)}&lang=${enc(lang)}")
            ?: return emptyList()
        return listRows(json)
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val info = getJson("$BASE/api/microdrama/drama/${encPath(id)}?lang=${enc(lang)}")
            ?.optJSONObject("data")
            ?: return null
        val total = info.intAny("total_episodes", "total", "episode_count") ?: 0
        val episodes = (1..total.coerceAtLeast(0)).map { number ->
            LiveEpisode(
                num = number,
                title = "Episode $number",
                url = watchUrl(id, number, lang),
                thumb = info.strAny("cover"),
            )
        }
        return LiveDetail(
            title = info.name() ?: "MicroDrama",
            cover = info.strAny("cover"),
            synopsis = info.strAny("description", "summary"),
            status = total.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "MicroDrama",
            released = null,
            genres = emptyList(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val json = getJson("$BASE/api/microdrama/play/${encPath(id)}/$episode?lang=${enc(lang)}")
            ?: return emptyList()
        val variants = json.optJSONObject("data")
            ?.optJSONArray("videos")
            .objects()
            .mapNotNull { row ->
                val streamUrl = row.strAny("url") ?: return@mapNotNull null
                val label = row.strAny("quality") ?: qualityLabel(streamUrl)
                label to streamUrl
            }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { resoRank(it.first) }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("MicroDrama", variants.first().embedUrl, variants))
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
        return "$BASE/api/microdrama/list${out.toQuery()}"
    }

    private fun listRows(json: JSONObject): List<JSONObject> {
        val data = json.optJSONObject("data") ?: return emptyList()
        return data.optJSONArray("data").objects()
            .ifEmpty { data.optJSONArray("items").objects() }
    }

    private fun nextUrl(endpoint: String, json: JSONObject, rowCount: Int): String? {
        if (rowCount <= 0) return null
        val data = json.optJSONObject("data") ?: return null
        val query = queryMap(endpoint)
        val page = data.optInt("page", query["page"]?.toIntOrNull() ?: 1)
        val limit = query["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        val total = data.optInt("total", 0)
        if ((total > 0 && page * limit >= total) || page >= 100) return null
        val next = query.toMutableMap()
        next["page"] = (page + 1).toString()
        return "$BASE/api/microdrama/list${next.toQuery()}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("id") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("total_episodes", "total", "episode_count")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("cover"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/microdrama/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/microdrama/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/microdrama/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/microdrama/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/microdrama/play/([^/?#]+)/", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/api/microdrama/play/[^/?#]+/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

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

    private fun qualityLabel(url: String): String =
        Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}P" }
            ?: "Auto"

    private fun resoRank(label: String): Int =
        Regex("\\d+").find(label)?.value?.toIntOrNull() ?: -1

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

    private fun JSONObject.name(): String? = strAny("title", "name")

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
