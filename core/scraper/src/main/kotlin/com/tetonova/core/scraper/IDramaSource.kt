package com.tetonova.core.scraper

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Native adapter for iDrama's GoodBos API documented at `/api.html`. */
object IDramaSource {
    private const val BASE = "https://idrama.goodbos.online"
    private const val DEFAULT_LANG = "id"
    private const val DEFAULT_TAB = "channel_7e89a1a2" // Terbaru
    private const val DEFAULT_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun isIDrama(url: String): Boolean =
        hostOf(url)?.contains("idrama") == true

    suspend fun list(url: String): List<LiveItem> = listPage(url).items

    suspend fun listPage(url: String): LivePage {
        val endpoint = listEndpoint(url)
        val value = getJsonValue(endpoint, needsCode = false) ?: return LivePage(emptyList())
        val rows = listRows(value, canonicalPath(endpoint), targetSectionsOf(endpoint))
        val lang = langOf(endpoint)
        return LivePage(
            items = rows.mapNotNull { dramaToItem(it, lang) }.dedupeByTitle(),
            nextUrl = nextUrl(endpoint, value, rows.isNotEmpty()),
        )
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val lang = langOf(baseUrl)
        val direct = getJsonValue(
            "$BASE/search?q=${enc(cleanQuery)}&lang=${enc(lang)}&page=1",
            needsCode = false,
        )
            ?.let { listRows(it, "/search", emptySet()) }
            .orEmpty()
            .filter { matchesQuery(it.name().orEmpty(), cleanQuery) }
        if (direct.isNotEmpty()) return direct.mapNotNull { dramaToItem(it, lang) }.dedupeByTitle()

        val matches = LinkedHashMap<String, LiveItem>()
        listOf(DEFAULT_TAB, "channel_022dd4f1", "channel_f4904f0b", "channel_a57c8658").forEach { tab ->
            val rows = getJsonValue("$BASE/tab/$tab?lang=${enc(lang)}", needsCode = false)
                ?.let { listRows(it, "/tab/$tab", emptySet()) }
                .orEmpty()
            rows.asSequence()
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
        val info = detailInfo(id, lang) ?: return null
        val episodes = info.optJSONArray("episode_list").objects()
            .mapNotNull { episodeToLive(id, lang, it) }
            .distinctBy { it.num }
            .sortedBy { it.num }
        val total = info.intAny("current_count", "episode_count", "total")
            ?: episodes.size.takeIf { it > 0 }
        return LiveDetail(
            title = info.name() ?: "iDrama",
            cover = info.strAny("cover_url", "compress_cover_url", "image"),
            synopsis = info.strAny("introduction", "description", "summary"),
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
            type = "Drama",
            studio = "iDrama",
            released = null,
            genres = info.tags(),
            episodes = episodes,
            url = detailUrl(id, lang),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val id = dramaIdOf(url) ?: return emptyList()
        val episode = episodeNumberOf(url) ?: return emptyList()
        val lang = langOf(url)
        val row = detailInfo(id, lang)
            ?.optJSONArray("episode_list")
            .objects()
            .firstOrNull { it.intAny("episode_order", "episode", "number") == episode }
        val unlocked = unlockEpisode(id, episode, lang)?.videoVariants().orEmpty()
        val fromDetail = row?.videoVariants().orEmpty()
        val variants = preferFreshLabels(unlocked + fromDetail)
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
            .sortedByDescending { it.first.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            .let(::uniqueLabels)
            .map { (label, streamUrl) -> ServerVariant(label, streamUrl) }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer("iDrama", variants.first().embedUrl, variants))
    }

    private suspend fun detailInfo(id: String, lang: String): JSONObject? =
        getJsonValue("$BASE/drama/${encPath(id)}?lang=${enc(lang)}", needsCode = true) as? JSONObject

    private suspend fun unlockEpisode(id: String, episode: Int, lang: String): JSONObject? =
        (getJsonValue("$BASE/unlock/${encPath(id)}/$episode?lang=${enc(lang)}", needsCode = true) as? JSONObject)
            ?.optJSONObject("target_ep_info")

    private suspend fun getJsonValue(url: String, needsCode: Boolean): Any? {
        val endpoint = if (needsCode) withCode(url) ?: return null else url
        val body = LiveClient.getHtml(endpoint) { body ->
            val trimmed = body.trimStart()
            trimmed.startsWith("{") || trimmed.startsWith("[")
        } ?: return null
        val value = runCatching { JSONTokener(body).nextValue() }.getOrNull() ?: return null
        if (value is JSONObject && value.optString("error").isNotBlank()) return null
        return value
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
            path == "/hot" -> "$BASE/hot?lang=${enc(lang)}"
            path.startsWith("/tab/") -> {
                val section = query["section"]?.takeIf { it.isNotBlank() }
                "$BASE$path?lang=${enc(lang)}" + (section?.let { "&section=${enc(it)}" } ?: "")
            }
            path.startsWith("/section/") -> {
                val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                "$BASE$path?lang=${enc(lang)}&page=$page"
            }
            else -> "$BASE/tab/$DEFAULT_TAB?lang=${enc(lang)}"
        }
    }

    private fun listRows(value: Any, path: String, targetSections: Set<String>): List<JSONObject> =
        when (value) {
            is JSONArray -> value.objects()
                .let { rows ->
                    if (targetSections.isEmpty()) rows else rows.filter { it.strAny("id") in targetSections }
                }
                .flatMap(::componentRows)
            is JSONObject -> when {
                path.equals("/hot", true) -> buildList {
                    addAll(value.optJSONArray("hot_drama_list").objects().filter(::isDramaRow))
                    addAll(value.optJSONArray("new_drama_list").objects().filter(::isDramaRow))
                    addAll(value.optJSONArray("recommend_plays").objects().filter(::isDramaRow))
                }
                path.equals("/search", true) -> value.optJSONArray("results").objects()
                    .ifEmpty { value.optJSONArray("guess_plays").objects() }
                    .filter(::isDramaRow)
                path.startsWith("/section/", true) -> value.optJSONArray("short_plays").objects().filter(::isDramaRow)
                isDramaRow(value) -> listOf(value)
                else -> componentRows(value)
            }
            else -> emptyList()
        }

    private fun componentRows(component: JSONObject): List<JSONObject> = buildList {
        component.optJSONObject("short_play_info")?.takeIf(::isDramaRow)?.let(::add)
        addAll(component.optJSONArray("short_plays").objects().filter(::isDramaRow))
        if (isDramaRow(component)) add(component)
        component.optJSONArray("items").objects().forEach { item ->
            val nested = item.optJSONObject("short_play_info")?.takeIf(::isDramaRow)
            nested?.let(::add)
            addAll(item.optJSONArray("short_plays").objects().filter(::isDramaRow))
            if (nested == null && isDramaRow(item)) add(item)
        }
    }

    private fun nextUrl(endpoint: String, value: Any, hasItems: Boolean): String? {
        if (!hasItems) return null
        val path = canonicalPath(endpoint).lowercase()
        val lang = langOf(endpoint)
        if (path.startsWith("/tab/") && targetSectionsOf(endpoint).isNotEmpty()) return null
        if (path.startsWith("/section/") && value is JSONObject) {
            if (!value.optBoolean("has_more", false)) return null
            val pageInfo = value.optJSONObject("page")
            val page = pageInfo?.optInt("page_num", 0)?.takeIf { it > 0 }
                ?: queryMap(endpoint)["page"]?.toIntOrNull()
                ?: 1
            val total = pageInfo?.optInt("total_page", page) ?: page
            if (page >= total || page >= 100) return null
            return "$BASE$path?lang=${enc(lang)}&page=${page + 1}"
        }
        if (path.startsWith("/tab/") && value is JSONArray) {
            val section = nextSectionId(value) ?: return null
            return "$BASE/section/${encPath(section)}?lang=${enc(lang)}&page=2"
        }
        return null
    }

    private fun nextSectionId(components: JSONArray): String? {
        val rows = components.objects()
        return rows.lastOrNull {
            it.strAny("id")?.startsWith("section_") == true &&
                it.strAny("component_type").orEmpty().contains("feed", ignoreCase = true)
        }?.strAny("id")
            ?: rows.lastOrNull { it.strAny("id")?.startsWith("section_") == true }?.strAny("id")
    }

    private fun dramaToItem(row: JSONObject, lang: String): LiveItem? {
        val id = dramaId(row) ?: return null
        val title = row.name() ?: return null
        val total = row.intAny("current_count", "episode_count", "total")
        return LiveItem(
            title = title,
            url = detailUrl(id, lang),
            cover = row.strAny("cover_url", "compress_cover_url", "image"),
            type = "Drama",
            status = total?.takeIf { it > 0 }?.let { "$it episode" },
        )
    }

    private fun episodeToLive(id: String, lang: String, row: JSONObject): LiveEpisode? {
        val number = row.intAny("episode_order", "episode", "number") ?: return null
        return LiveEpisode(
            num = number,
            title = "Episode $number",
            url = watchUrl(id, number, lang),
            thumb = row.strAny("episode_cover", "cover_url", "cover"),
        )
    }

    private fun JSONObject.videoVariants(): List<Pair<String, String>> =
        optJSONArray("play_info_list").objects().mapNotNull { video ->
            val url = video.strAny("play_url", "url") ?: return@mapNotNull null
            (video.strAny("definition", "quality") ?: qualityLabel(url)) to url
        }.ifEmpty {
            listOfNotNull(strAny("play_url")?.let { qualityLabel(it) to it })
        }

    private fun preferFreshLabels(rows: List<Pair<String, String>>): List<Pair<String, String>> {
        val seen = HashSet<String>()
        return rows.filter { (label, _) -> seen.add(label.lowercase()) }
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
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun List<LiveItem>.dedupeByTitle(): List<LiveItem> {
        val seen = HashSet<String>()
        return filter { seen.add(titleDedupeKey(it)) }
    }

    private fun titleDedupeKey(item: LiveItem): String =
        cleanTitleKey(item.title).ifBlank { item.url }

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
        "$BASE/idrama/detail/${encPath(id)}?lang=${enc(lang)}"

    private fun watchUrl(id: String, episode: Int, lang: String): String =
        "$BASE/idrama/watch/${encPath(id)}/episode/$episode?lang=${enc(lang)}"

    private fun dramaIdOf(url: String): String? =
        queryMap(url)["id"]
            ?: Regex("/idrama/(?:detail|watch)/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/drama/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()
            ?: Regex("/unlock/([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.dec()

    private fun episodeNumberOf(url: String): Int? =
        Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("/unlock/[^/?#]+/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun encPath(value: String): String = enc(value).replace("+", "%20")
    private fun String.dec(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull {
            optString(it).trim().ifBlank { null }
        }

    private fun targetSectionsOf(url: String): Set<String> =
        queryMap(url)["section"]
            ?.split(',', '|')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            .orEmpty()

    private fun isDramaRow(row: JSONObject): Boolean =
        dramaId(row) != null &&
            row.name()?.takeUnless { it.startsWith("ad_", ignoreCase = true) } != null

    private fun dramaId(row: JSONObject): String? =
        row.strAny("id", "short_play_id", "short_series_id", "route_val")
            ?.takeIf { it.all(Char::isDigit) }

    private fun JSONObject.name(): String? = strAny("short_play_name", "title", "name")

    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun JSONObject.strAny(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { str(it) }

    private fun JSONObject.int(key: String): Int? =
        if (!has(key) || isNull(key)) null else optString(key).trim().toIntOrNull()

    private fun JSONObject.intAny(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JSONObject.tags(): List<String> =
        (optJSONArray("content_tag").objects().mapNotNull { it.strAny("tag_local", "name") } +
            optJSONArray("category_tag").objects().mapNotNull { it.strAny("tag_local", "name") })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
}
