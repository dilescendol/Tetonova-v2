package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for GoodShort's GoodBos API documented at `/api.html`. */
object GoodShortSource {
    private const val BASE = "https://goodshort.goodbos.online"
    private const val DEFAULT_LANG = "in"
    private const val DEFAULT_CHANNEL = "563" // Terbaru
    private const val DEFAULT_SIZE = 20
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isGoodShort(url: String): Boolean =
        hostOf(url)?.contains("goodshort") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val json = getJson(endpoint, needsCode = false) ?: return LivePage(emptyList())
        val rows = homeRows(json)
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { bookToItem(it, lang) }.distinctBy { it.url },
            nextUrl = nextUrl(endpoint, json, rows.isNotEmpty()),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val direct = getJson(
            "$BASE/search?lang=${enc(lang)}&q=${enc(cleanQuery)}&page=1&size=15",
            needsCode = true,
        )
            ?.let(::searchRows)
            .orEmpty()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
        if (direct.isNotEmpty()) return direct.mapNotNull { bookToItem(it, lang) }.distinctBy { it.url }

        val matches = LinkedHashMap<String, LiveItem>()
        listOf(DEFAULT_CHANNEL, "-1", "656", "567").forEach { channel ->
            for (page in 1..5) {
                val rows = getJson(
                    "$BASE/home?lang=${enc(lang)}&channel=$channel&page=$page&size=$DEFAULT_SIZE",
                    needsCode = false,
                )?.let(::homeRows).orEmpty()
                if (rows.isEmpty()) break
                rows.asSequence()
                    .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
                    .mapNotNull { bookToItem(it, lang) }
                    .forEach { matches.putIfAbsent(it.url, it) }
                if (matches.size >= 30) return matches.values.toList()
            }
        }
        return matches.values.toList()
    }

    suspend fun detail(url: String): LiveDetail? {
        val id = bookIdOf(url) ?: return null
        val lang = langOf(url)
        val bookData = getJson("$BASE/book/${encPath(id)}?lang=${enc(lang)}", needsCode = false)
            ?.optJSONObject("data")
        val book = bookData?.optJSONObject("book") ?: bookData
        val batch = batchData(id, lang)
        val episodes = (
            batch?.optJSONArray("episodes").objects().ifEmpty { bookData?.optJSONArray("list").objects() }
            )
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = batch?.intAny("totalEpisode")
            ?: book?.intAny("chapterCount", "episodeCount", "total")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = book?.name() ?: batch?.name() ?: "GoodShort",
            cover = book?.strAny("bookDetailCover", "cover", "cover2", "image") ?: batch?.strAny("cover"),
            synopsis = book?.strAny("introduction", "description", "summary"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "GoodShort",
            released = null,
            genres = book?.labels().orEmpty(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = bookIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = batchData(id, lang)
            ?.optJSONArray("episodes")
            .objects()
            .firstOrNull { episodeNumber(it) == episode }
            ?: return emptyList()
        val variants = row.videoVariants()
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { it.first.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("GoodShort", variants.first().embedUrl, variants))
    }

    private suspend fun batchData(id: String, lang: String): JSONObject? =
        getJson("$BASE/batchload/${encPath(id)}?lang=${enc(lang)}&q=", needsCode = true)
            ?.optJSONObject("data")

    private suspend fun getJson(url: String, needsCode: Boolean): JSONObject? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
            ?.takeIf {
                it.optString("error").isBlank() &&
                    (!it.has("status") || it.optInt("status", 0) == 0) &&
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
        val query = queryMap(url)
        val out = LinkedHashMap<String, String>()
        out["lang"] = upstreamLang(query["lang"] ?: DEFAULT_LANG)
        out["channel"] = query["channel"] ?: DEFAULT_CHANNEL
        out["page"] = query["page"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: "1"
        out["size"] = query["size"]?.toIntOrNull()?.coerceAtLeast(1)?.toString() ?: DEFAULT_SIZE.toString()
        return "$BASE/home${out.toQuery()}"
    }

    private fun homeRows(json: JSONObject): List<JSONObject> {
        val data = json.optJSONObject("data") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        data.optJSONArray("records").objects().forEach { record ->
            val items = record.optJSONArray("items").objects()
            if (items.isNotEmpty()) out.addAll(items) else if (record.strAny("bookId", "id") != null) out.add(record)
        }
        return out
    }

    private fun searchRows(json: JSONObject): List<JSONObject> {
        val data = json.optJSONObject("data") ?: return emptyList()
        val search = data.optJSONObject("searchResult")
        return search?.optJSONArray("records").objects()
            .ifEmpty { data.optJSONArray("records").objects() }
            .ifEmpty { data.optJSONArray("searchResult").objects() }
    }

    private fun nextUrl(endpoint: String, json: JSONObject, hasItems: Boolean): String? {
        if (!hasItems) return null
        val data = json.optJSONObject("data") ?: return null
        val current = data.optInt("current", queryMap(endpoint)["page"]?.toIntOrNull() ?: 1)
        val pages = data.optInt("pages", current)
        if (pages <= current || current >= 100) return null
        val query = queryMap(endpoint).toMutableMap()
        query["page"] = (current + 1).toString()
        return "$BASE/home${query.toQuery()}"
    }

    private fun bookToItem(book: JSONObject, lang: String): LiveItem? {
        val id = book.strAny("bookId", "id") ?: return null
        val title = book.name() ?: return null
        val total = book.intAny("chapterCount", "episodeCount", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = book.strAny("bookDetailCover", "cover", "cover2", "image"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = episodeNumber(row) ?: return null
        return LiveEpisode(
            num = number,
            title = row.strAny("chapterName", "seoChapterName", "name", "title") ?: "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("image", "cover", "thumb"),
        )
    }

    private fun episodeNumber(row: JSONObject): Int? =
        row.intAny("chapterIndex", "episode", "number", "num")
            ?: row.intAny("index")?.let { it + 1 }
            ?: row.strAny("chapterName", "seoChapterName")?.filter(Char::isDigit)?.toIntOrNull()

    private fun JSONObject.videoVariants(): List<Pair<String, String>> =
        optJSONArray("videos").objects().mapNotNull { video ->
            val url = video.strAny("filePath", "url", "videoPath", "cdn", "m3u8Path") ?: return@mapNotNull null
            (video.strAny("type", "quality") ?: qualityLabel(url)) to url
        }.ifEmpty {
            optJSONArray("multiVideos").objects().mapNotNull { video ->
                val url = video.strAny("filePath", "url", "videoPath") ?: return@mapNotNull null
                (video.strAny("type", "quality") ?: qualityLabel(url)) to url
            }
        }.ifEmpty {
            listOfNotNull(strAny("cdn", "m3u8Path")?.let { qualityLabel(it) to it })
        }

    private fun matchesQuery(title: String, query: String): Boolean =
        LiveSource.matchesSearchQuery(title, query)

    private fun uniqueLabels(rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val counts = HashMap<String, Int>()
        return rows.map { (label, streamUrl) ->
            val n = (counts[label] ?: 0) + 1
            counts[label] = n
            (if (n == 1) label else "$label #$n") to streamUrl
        }
    }

    private fun qualityLabel(url: String): String =
        Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.let { "${it}p" }
            ?: "Auto"

    private fun detailUrl(id: String, lang: String): String =
        "$BASE/goodshort/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/goodshort/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun bookIdOf(url: String): String? =
        queryMap(url)["bookId"]
            ?: queryMap(url)["id"]
            ?: Regex("/goodshort/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/(?:book|batchload)/([^/?#]+)", RegexOption.IGNORE_CASE)
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

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).trim().ifBlank { null }
        }

    private fun JSONObject.name(): String? = strAny("bookName", "name", "title")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JSONObject.labels(): List<String> =
        (optJSONArray("labels").strings() + optJSONArray("typeTwoNames").strings() +
            optJSONArray("genreList").objects().mapNotNull { it.strAny("name", "labelName") } +
            optJSONArray("labelInfos").objects().mapNotNull { it.strAny("labelName", "name") })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
}
