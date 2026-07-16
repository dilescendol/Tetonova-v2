package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for Melolo's GoodBos API documented at `/api.html`. */
object MeloloSource {
    private const val BASE = "https://melolo.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val PAGE_SIZE = 18
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isMelolo(url: String): Boolean =
        hostOf(url)?.contains("melolo") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = true) ?: return LivePage(emptyList())
        val rows = json.optJSONArray("data").objects()
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.distinctBy { it.url },
            nextUrl = nextUrl(endpoint, rows.size),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val json = getJson("$BASE/api/search?lang=${enc(lang)}&q=${enc(cleanQuery)}", needsCode = false)
            ?: return emptyList()
        return json.optJSONArray("data").objects()
            .asSequence()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
            .mapNotNull { dramaToItem(it, lang) }
            .distinctBy { it.url }
            .toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = dramaIdOf(url) ?: return null
        val lang = langOf(url)
        val json = getJson("$BASE/api/detail/${encPath(id)}?lang=${enc(lang)}", needsCode = true)
            ?: return null
        val videos = json.optJSONArray("videos").objects()
        val episodes = videos
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
            .ifEmpty {
                val total = json.intAny("episodes", "total", "episode_count") ?: 0
                (1..total).map { number -> LiveEpisode(number, "Episode $number", watchUrl(id, number, lang)) }
            }
        val total = json.intAny("episodes", "total", "episode_count") ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = json.name() ?: "Melolo",
            cover = json.strAny("cover"),
            synopsis = json.strAny("intro", "desc", "description"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "Melolo",
            released = null,
            genres = emptyList(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val json = getJson("$BASE/api/video?id=${enc(id)}&ep=$episode", needsCode = true)
            ?: return emptyList()
        if (json.optBoolean("locked", false)) return emptyList()
        val rows = json.optJSONArray("qualityList").objects()
        // The MP4 samples are AES-CTR encrypted; the key is a plain GET keyed by the per-episode `kid`.
        // Fetch it once and carry it to the player as a `#tnk=<hex>` URL fragment so ExoStage can decrypt
        // in-place via MeloloDecryptor (kept out of the HTTP request — fragments aren't sent upstream).
        val keyHex = rows.firstNotNullOfOrNull { it.strAny("kid") }?.let { fetchKey(it) }
        val variants = rows
            .mapNotNull { row ->
                val streamUrl = row.strAny("url") ?: return@mapNotNull null
                val label = row.strAny("label") ?: qualityLabel(streamUrl)
                ServerVariant(label, if (keyHex != null) "$streamUrl#tnk=$keyHex" else streamUrl)
            }
            .distinctBy { it.embedUrl }
            .sortedByDescending { resoRank(it.label) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("Melolo", variants.first().embedUrl, variants))
    }

    /** AES key for an encrypted Melolo video, keyed by its `kid` (from the /api/video qualityList). */
    private suspend fun fetchKey(kid: String): String? {
        val body = LiveClient.getHtml("https://melolo-api.dramabos.fun/api/melolo/key?vid=${enc(kid)}") { it.trimStart().startsWith("{") }
            ?: return null
        return runCatching { JSONObject(body).str("key") }.getOrNull()
    }

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { json ->
                val code = json.optInt("code", 0)
                code == 0 || code == 200
            }
    }

    private fun withCode(url: String): String? {
        if (queryMap(url)["code"].orEmpty().isNotBlank()) return url
        val code = accessCode(BASE) ?: return null
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}code=${enc(code)}"
    }

    private fun listEndpoint(url: String): String {
        val query = queryMap(url)
        val offset = query["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val lang = query["lang"] ?: DEFAULT_LANG
        return "$BASE/api/home?lang=${enc(lang)}&offset=$offset"
    }

    private fun nextUrl(endpoint: String, rowCount: Int): String? {
        if (rowCount < PAGE_SIZE) return null
        val query = queryMap(endpoint)
        val offset = query["offset"]?.toIntOrNull() ?: 0
        if (offset >= 3000) return null
        return "$BASE/api/home?lang=${enc(langOf(endpoint))}&offset=${offset + rowCount}"
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = row.strAny("id") ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("episodes", "episode_count", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("cover"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("episode", "ep", "number") ?: return null
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
        "$BASE/melolo/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/melolo/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/melolo/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/api/detail/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        queryMap(url)["ep"]?.toIntOrNull()
            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

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

    private fun qualityLabel(url: String): String =
        Regex("[./](\\d{3,4})p(?:[?./]|$)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "Auto"

    private fun resoRank(label: String): Int =
        Regex("\\d+").find(label)?.value?.toIntOrNull() ?: -1

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
}
