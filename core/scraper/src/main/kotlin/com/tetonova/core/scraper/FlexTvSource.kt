package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * FlexTV GoodBos API adapter.
 *
 * Public UI uses:
 *   /api/drama/tabs
 *   /api/drama/list?classify_id=...
 *   /api/drama/search?keyword=...
 *   /api/seriesInfo?series_id=...
 *   /api/allepisodes?id=...
 *
 * Most catalog/detail/playback calls require the panel-published access code. Search is public in
 * the web UI, but this adapter tolerates either path.
 */
object FlexTvSource {
    private const val BASE = "https://flextv.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_CLASSIFY_ID = "18" // Baru

    fun isFlexTv(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return "flextv" in host || ("drakula" in host && "/flextv" in url.lowercase())
    }

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = !isSearchEndpoint(endpoint)) ?: return LivePage(emptyList())
        val data = dataOf(json)
        val rows = dramaRows(data, endpoint)
        val lang = langOf(endpoint)
        val items = rows.mapNotNull { dramaToItem(it, lang) }
        return LivePage(items, nextUrl(endpoint, data, items.isNotEmpty()))
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val endpoint = "$BASE/api/drama/search?keyword=${enc(query)}&page=1&lang=${enc(lang)}"
        val json = getJson(endpoint, needsCode = false) ?: return emptyList()
        val data = dataOf(json)
        return dramaRows(data, endpoint).mapNotNull { dramaToItem(it, lang) }
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = seriesIdOf(url) ?: return null
        val lang = langOf(url)
        val info = seriesInfo(id, lang)
        val episodeData = allEpisodesData(id, lang)
        val series = info?.let(::seriesObject) ?: episodeData
        val episodes = episodeRows(episodeData).mapNotNull { episodeToLive(id, lang, it) }
        val title = series?.name() ?: episodes.firstOrNull()?.title?.substringBefore(" Episode ") ?: "FlexTV"
        val total = series?.intAny("sections", "section_count", "episode_count", "total")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = title,
            cover = series?.strAny("cover", "poster", "image", "thumb"),
            synopsis = series?.strAny("description", "desc", "summary", "intro"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "FlexTV",
            released = series?.strAny("year", "release_year", "release_time"),
            genres = series?.arrayStrings("tags", "classify", "genres", "theme").orEmpty(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = seriesIdOf(url) ?: return emptyList()
        val n = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = allEpisodes(id, lang).firstOrNull { it.int("no") == n || it.int("episode") == n }
            ?: allEpisodes(id, lang).getOrNull(n - 1)
            ?: return emptyList()
        val variants = buildList {
            row.str("video_1080")?.let { add("1080p" to it) }
            row.str("video_720")?.let { add("720p" to it) }
            row.str("video_url")?.let { add(qualityLabel(it) to it) }
            row.str("url")?.let { add(qualityLabel(it) to it) }
        }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { resoRank(it.first) }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer(name = "FlexTV", embedUrl = variants.first().embedUrl, variants = variants))
    }

    private suspend fun seriesInfo(id: String, lang: String): JSONObject? =
        getJson("$BASE/api/seriesInfo?series_id=${enc(id)}&lang=${enc(lang)}", needsCode = true)?.let(::dataOf)

    private suspend fun allEpisodes(id: String, lang: String): List<JSONObject> {
        val data = allEpisodesData(id, lang)
        return episodeRows(data)
    }

    private suspend fun allEpisodesData(id: String, lang: String): JSONObject {
        val json = getJson("$BASE/api/allepisodes?id=${enc(id)}&lang=${enc(lang)}", needsCode = true)
            ?: return JSONObject()
        return dataOf(json)
    }

    private fun episodeRows(data: JSONObject): List<JSONObject> =
        data.optJSONArray("episodes").objects()
            .ifEmpty { data.optJSONArray("sections").objects() }
            .ifEmpty { data.optJSONArray("list").objects() }

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf { it.optInt("code", 0) == 0 && it.optString("error").isBlank() }
    }

    private fun withCode(url: String): String? {
        if (queryMap(url)["code"].orEmpty().isNotBlank()) return url
        val code = accessCode(BASE) ?: return null
        val sep = if ('?' in url) '&' else '?'
        return "$url${sep}code=${enc(code)}"
    }

    private fun listEndpoint(url: String): String {
        val path = canonicalPath(url)
        val query = queryMap(url)
        val lang = query["lang"] ?: query["_lang"] ?: DEFAULT_LANG
        val cleanPath = when {
            path.isBlank() || path == "/" || path.equals("/api.html", true) -> "/api/drama/list"
            path.startsWith("/flextv/api/") -> path.removePrefix("/flextv")
            path.startsWith("/api/") -> path
            else -> "/api/drama/list"
        }
        val out = LinkedHashMap<String, String>()
        query.forEach { (k, v) ->
            if (!k.equals("code", true)) out[k] = v
        }
        when (cleanPath) {
            "/api/drama/list" -> {
                out.putIfAbsent("classify_id", DEFAULT_CLASSIFY_ID)
                out.putIfAbsent("page", "1")
                out.putIfAbsent("lang", lang)
            }
            "/api/indexFloor", "/api/rankingList" -> {
                out.putIfAbsent("page_no", "1")
                out.putIfAbsent("lang", lang)
            }
            "/api/drama/ranking" -> {
                out.putIfAbsent("rank_tab_id", "1")
                out.putIfAbsent("page", "1")
                out.putIfAbsent("lang", lang)
            }
            "/api/drama/popular", "/api/drama/newest", "/api/drama/home" -> {
                out.putIfAbsent("page", "1")
                out.putIfAbsent("lang", lang)
            }
        }
        return BASE + cleanPath + out.toQuery()
    }

    private fun dramaRows(data: JSONObject, endpoint: String): List<JSONObject> {
        val query = queryMap(endpoint)
        val floorNeedle = query["floor"]?.let(::cleanTitleKey)
        val templateNeedle = query["template"]?.toIntOrNull()
        val floors = data.optJSONArray("floors").objects()
        if (floors.isNotEmpty()) {
            val selected = floors.filter { floor ->
                val titleOk = floorNeedle == null || cleanTitleKey(floor.str("title").orEmpty()).contains(floorNeedle)
                val templateOk = templateNeedle == null || floor.optInt("template_type", -1) == templateNeedle
                titleOk && templateOk
            }.ifEmpty {
                if (floorNeedle == null && templateNeedle == null) floors else emptyList()
            }
            return selected.flatMap { it.optJSONArray("dramas").objects() }
        }
        return data.optJSONArray("dramas").objects()
            .ifEmpty { data.optJSONArray("series").objects() }
            .ifEmpty { data.optJSONArray("list").objects() }
            .ifEmpty { data.optJSONArray("items").objects() }
    }

    private fun nextUrl(endpoint: String, data: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val query = queryMap(endpoint)
        val floorMode = query["floor"].orEmpty().isNotBlank()
        val path = canonicalPath(endpoint)
        val paging = data.optJSONObject("paging")
        val current = query["page"]?.toIntOrNull()
            ?: query["page_no"]?.toIntOrNull()
            ?: paging?.optInt("page", 1)
            ?: 1
        val pageSize = paging?.optInt("page_size", 20)?.takeIf { it > 0 } ?: 20
        val total = paging?.optInt("total", 0) ?: 0
        if (total <= current * pageSize) return null
        if (floorMode && query["template"]?.toIntOrNull() != 4 && path == "/api/drama/list") return null
        val next = query.toMutableMap()
        if (path == "/api/indexFloor" || path == "/api/rankingList") {
            next["page_no"] = (current + 1).toString()
        } else {
            next["page"] = (current + 1).toString()
        }
        return BASE + path + next.toQuery()
    }

    private fun dramaToItem(d: JSONObject, lang: String): LiveItem? {
        val id = d.strAny("series_id", "id") ?: return null
        val title = d.name() ?: return null
        val sections = d.intAny("sections", "section_count", "episode_count", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = d.strAny("cover", "image", "poster", "thumb"),
            type = "Drama",
            status = sections?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val n = row.intAny("no", "episode", "num", "number") ?: return null
        return LiveEpisode(
            num = n,
            title = row.strAny("title", "name") ?: "Episode $n",
            url = watchUrl(id, n, lang),
            thumb = row.strAny("cover", "thumb", "image"),
        )
    }

    private fun seriesObject(data: JSONObject): JSONObject =
        data.optJSONObject("series") ?: data.optJSONObject("series_info") ?: data.optJSONObject("info") ?: data

    private fun dataOf(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

    private fun isSearchEndpoint(url: String): Boolean = canonicalPath(url).contains("search", ignoreCase = true)

    private fun detailUrl(id: String, lang: String): String = "$BASE/detail/${encPath(id)}?lang=${enc(lang)}"
    private fun watchUrl(id: String, num: Int, lang: String): String = "$BASE/watch/${encPath(id)}/episode/$num?lang=${enc(lang)}"

    private fun seriesIdOf(url: String): String? {
        val query = queryMap(url)
        return query["id"] ?: query["series_id"]
            ?: Regex("/(?:detail|series|watch)/([^/?#]+)").find(url)?.groupValues?.getOrNull(1)?.dec()
    }

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun qualityLabel(url: String): String =
        Regex("/(\\d{3,4})\\.mp4", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" } ?: "Auto"

    private fun uniqueLabels(rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val counts = HashMap<String, Int>()
        return rows.map { (label, url) ->
            val n = (counts[label] ?: 0) + 1
            counts[label] = n
            (if (n == 1) label else "$label #$n") to url
        }
    }

    private fun resoRank(label: String): Int =
        label.takeWhile { it.isDigit() }.toIntOrNull() ?: if (label.equals("Auto", true)) 0 else 500

    private fun cleanTitleKey(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() } ?: LiveRuntimeConfig.accessCodeFor(url)

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).rawPath.orEmpty().ifBlank { "/" } }.getOrDefault("/")

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: queryMap(url)["_lang"] ?: DEFAULT_LANG

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host?.removePrefix("www.")?.lowercase() }.getOrNull()

    private fun queryMap(url: String): LinkedHashMap<String, String> {
        val raw = runCatching { URI(url).rawQuery.orEmpty() }.getOrDefault("")
        val out = LinkedHashMap<String, String>()
        if (raw.isBlank()) return out
        raw.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val key = part.substringBefore('=').dec()
            val value = part.substringAfter('=', "").dec()
            if (key.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun Map<String, String>.toQuery(): String =
        if (isEmpty()) "" else entries.joinToString("&", prefix = "?") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")
    private fun String.dec(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().ifBlank { null } }

    private fun JSONObject.name(): String? = strAny("name", "title", "recommend_name", "series_name")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JSONObject.arrayStrings(vararg keys: String): List<String> =
        keys.firstNotNullOfOrNull { key -> optJSONArray(key)?.strings()?.takeIf { it.isNotEmpty() } } ?: emptyList()
}
