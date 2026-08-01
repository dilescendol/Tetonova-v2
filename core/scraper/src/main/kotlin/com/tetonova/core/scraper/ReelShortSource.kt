package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * ReelShort GoodBos API adapter.
 *
 * The API documented at /api.html is JSON-first and lives at the domain root:
 *   /trending, /home, /shelf/{id}, /search, /detail/{id}, /chapters/{id}, /allepisodes/{id}
 *
 * /chapters and /allepisodes require an access code. The code is supplied through
 * [LiveSource.configureAccessCodes] from the panel/bundled registry.
 */
object ReelShortSource {
    private const val BASE = "https://reelshort.goodbos.online"
    private const val DEFAULT_LANG = "in"

    fun isReelShort(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return "reelshort" in host && ("goodbos" in host || "dramabos" in host)
    }

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint) ?: return LivePage(emptyList())
        val items = bookArray(json, endpoint).objects().mapNotNull { bookToItem(it, langOf(endpoint)) }
        return LivePage(items, nextUrl(endpoint, json, items.isNotEmpty()))
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val endpoint = "$BASE/search?q=${enc(query)}&lang=${enc(lang)}"
        val json = getJson(endpoint) ?: return emptyList()
        return json.optJSONArray("results").objects().mapNotNull { bookToItem(it, lang) }
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = bookIdOf(url) ?: return null
        val lang = langOf(url)
        val d = getJson("$BASE/detail/${encPath(id)}?lang=${enc(lang)}") ?: return null
        if (d.optString("error").isNotBlank()) return null

        val title = d.str("title") ?: return null
        val chapterRows = chapters(id, lang)
        val total = chapterRows.size.takeIf { it > 0 } ?: d.optInt("chapters", 0)
        val episodes = if (chapterRows.isNotEmpty()) {
            val rawNumbers = chapterRows.mapIndexed { index, ch ->
                chapterNumber(ch) ?: episodeNumber(ch.str("name")) ?: index + 1
            }
            val zeroBased = rawNumbers.any { it <= 0 }
            chapterRows.mapIndexed { index, ch ->
                val raw = rawNumbers.getOrNull(index) ?: index + 1
                val n = if (zeroBased) raw + 1 else raw
                LiveEpisode(
                    num = n,
                    title = displayEpisodeTitle(ch.str("name"), n),
                    url = watchUrl(id, n, lang),
                )
            }
        } else {
            (1..total).map { n -> LiveEpisode(n, "Episode $n", watchUrl(id, n, lang)) }
        }

        return LiveDetail(
            title = title,
            cover = d.str("pic"),
            synopsis = d.str("desc"),
            status = total.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "ReelShort",
            released = null,
            genres = d.optJSONArray("theme").strings(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = bookIdOf(url) ?: return emptyList()
        val n = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val episodes = allEpisodes(id, lang)
        val zeroBased = episodes.any { it.int("episode") == 0 }
        val rawTargets = if (zeroBased) {
            listOfNotNull(if (n > 0) n - 1 else n, n).distinct()
        } else {
            listOf(n)
        }
        val row = rawTargets.firstNotNullOfOrNull { target -> episodes.firstOrNull { it.int("episode") == target } }
            ?: episodes.firstOrNull { it.int("episode") == n }
            ?: episodes.getOrNull(n - 1)
            ?: (if (zeroBased) episodes.getOrNull(n) else null)
            ?: return emptyList()
        val variants = row.optJSONArray("streams").objects()
            .mapNotNull { stream ->
                val streamUrl = stream.str("url") ?: return@mapNotNull null
                val label = qualityLabel(stream.str("quality"))
                label to streamUrl
            }
            .distinctBy { it.second }
            .sortedByDescending { resoRank(it.first) }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer(name = "ReelShort", embedUrl = variants.first().embedUrl, variants = variants))
    }

    private suspend fun chapters(id: String, lang: String): List<JSONObject> {
        val code = accessCode(BASE) ?: return emptyList()
        val json = getJson("$BASE/chapters/${encPath(id)}?lang=${enc(lang)}&code=${enc(code)}") ?: return emptyList()
        return json.optJSONArray("chapters").objects()
    }

    private suspend fun allEpisodes(id: String, lang: String): List<JSONObject> {
        val code = accessCode(BASE) ?: return emptyList()
        val json = getJson("$BASE/allepisodes/${encPath(id)}?lang=${enc(lang)}&code=${enc(code)}") ?: return emptyList()
        return json.optJSONArray("episodes").objects()
    }

    private suspend fun getJson(url: String): JSONObject? {
        val body = LiveClient.getHtml(url) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun listEndpoint(url: String): String {
        val path = canonicalPath(url)
        val query = queryMap(url)
        val lang = query["lang"] ?: DEFAULT_LANG
        val cleanPath = when {
            path.isBlank() || path == "/" || path.equals("/api.html", true) || path.equals("/api/v1", true) -> "/home"
            path.startsWith("/api/v1/") -> path.removePrefix("/api/v1")
            else -> path
        }
        val out = LinkedHashMap<String, String>()
        out.putAll(query)
        when {
            cleanPath == "/home" -> {
                out.putIfAbsent("tab", "populer")
                out.putIfAbsent("lang", lang)
            }
            cleanPath == "/trending" || cleanPath == "/search" || cleanPath.startsWith("/shelf/") -> {
                out.putIfAbsent("lang", lang)
            }
        }
        if (cleanPath.startsWith("/shelf/")) {
            out.putIfAbsent("page", "1")
            out.putIfAbsent("limit", "24")
        }
        return BASE + cleanPath + out.toQuery()
    }

    private fun bookArray(json: JSONObject, endpoint: String): JSONArray? {
        val path = canonicalPath(endpoint)
        return when {
            path == "/trending" -> json.optJSONArray("popular")
            path == "/search" -> json.optJSONArray("results")
            else -> json.optJSONArray("books")
        }
    }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        val path = canonicalPath(endpoint)
        if (!hasItems) return null
        val query = queryMap(endpoint)
        return when {
            path == "/home" -> {
                val current = query["page"]?.toIntOrNull() ?: 1
                query.putIfAbsent("limit", "24")
                query["page"] = (current + 1).toString()
                BASE + path + query.toQuery()
            }
            path.startsWith("/shelf/") && json.optInt("is_finished", 1) == 0 -> {
                val current = json.optInt("page", query["page"]?.toIntOrNull() ?: 1)
                query["page"] = (current + 1).toString()
                BASE + path + query.toQuery()
            }
            else -> null
        }
    }

    private fun bookToItem(book: JSONObject, lang: String): LiveItem? {
        val id = book.str("id") ?: return null
        val title = book.str("title") ?: return null
        val chapters = book.optInt("chapters", 0)
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = book.str("pic"),
            type = "Drama",
            status = chapters.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun detailUrl(id: String, lang: String): String = "$BASE/detail/${encPath(id)}?lang=${enc(lang)}"
    private fun watchUrl(id: String, num: Int, lang: String): String = "$BASE/watch/${encPath(id)}/episode/$num?lang=${enc(lang)}"

    private fun bookIdOf(url: String): String? =
        Regex("/detail/([^/?#]+)").find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/watch/([^/?#]+)/episode/\\d+").find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun episodeNumber(name: String?): Int? =
        Regex("\\b(?:episode|ep)\\s*(\\d+)\\b", RegexOption.IGNORE_CASE)
            .find(name.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun chapterNumber(ch: JSONObject): Int? =
        ch.int("episode") ?: ch.int("chapter") ?: ch.int("chapter_num") ?: ch.int("num") ?: ch.int("number")

    private fun displayEpisodeTitle(raw: String?, num: Int): String {
        val title = raw.orEmpty().trim()
        return if (title.isBlank() || episodeNumber(title) != null) "Episode $num" else title
    }

    private fun qualityLabel(raw: String?): String {
        val q = raw.orEmpty().trim()
        if (q.isBlank() || q == "0p") return "Auto"
        Regex("(\\d+)\\s*p", RegexOption.IGNORE_CASE).find(q)?.let { return it.groupValues[1] + "p" }
        return q
    }

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

    private fun accessCode(url: String): String? =
        queryMap(url)["code"]?.takeIf { it.isNotBlank() } ?: LiveRuntimeConfig.accessCodeFor(url)

    private fun canonicalPath(url: String): String =
        runCatching { URI(url).rawPath.orEmpty().ifBlank { "/" } }.getOrDefault("/")

    private fun langOf(url: String): String = queryMap(url)["lang"] ?: DEFAULT_LANG

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

    private fun MutableMap<String, String>.toQuery(): String =
        if (isEmpty()) "" else entries.joinToString("&", prefix = "?") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")
    private fun String.dec(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).trim().ifBlank { null } }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()
}
