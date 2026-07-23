package com.tetonova.core.scraper

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Native adapter for Idlix's JSON catalog and its timed gate -> claim -> redeem playback flow. */
object IdlixSource {

    const val DEFAULT_BASE = "https://z2.idlixku.com"
    private const val TMDB_IMAGE = "https://image.tmdb.org/t/p"

    fun isIdlix(url: String): Boolean {
        val lower = url.lowercase()
        return "idlixku.com" in lower || "idlix" in lower && ("/api/" in lower || "/tn-watch/" in lower)
    }

    suspend fun listPage(url: String): LivePage {
        val pageUrl = normalizeCatalogUrl(url)
        val root = getJson(pageUrl) ?: return LivePage(emptyList())
        val fallbackType = if ("/api/movies" in pageUrl) "movie" else if ("/api/series" in pageUrl) "series" else null
        val items = root.optJSONArray("data").objects().mapNotNull { it.toItem(baseOf(pageUrl), fallbackType) }
        return LivePage(items.distinctBy { it.url }, nextPage(pageUrl, items.isNotEmpty()))
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val base = baseOf(baseUrl)
        val url = "$base/api/search?q=${URLEncoder.encode(query, "UTF-8")}&page=1&limit=24"
        val root = getJson(url) ?: return emptyList()
        return root.optJSONArray("results").objects().mapNotNull { it.toItem(base, null) }.distinctBy { it.url }
    }

    suspend fun detail(url: String): LiveDetail? {
        val apiUrl = detailApiUrl(url) ?: return null
        val base = baseOf(apiUrl)
        val data = getJson(apiUrl) ?: return null
        val title = data.str("title") ?: return null
        val slug = data.str("slug") ?: slugOf(apiUrl)
        val isSeries = "/api/series/" in apiUrl || data.optJSONArray("seasons") != null
        val episodes = if (isSeries) seriesEpisodes(base, slug, data) else {
            val id = data.str("id") ?: return null
            listOf(LiveEpisode(1, title, "$base/tn-watch/movie/$id"))
        }
        return LiveDetail(
            title = title,
            cover = image(data.str("posterPath"), "w500"),
            synopsis = data.str("overview"),
            status = if (isSeries) "Series" else "Movie",
            type = if (isSeries) "Series" else "Movie",
            studio = null,
            released = (data.str("releaseDate") ?: data.str("firstAirDate"))?.substringBefore('-'),
            genres = data.optJSONArray("genres").objects().mapNotNull { it.str("name") },
            episodes = episodes,
            url = apiUrl,
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val match = Regex("/tn-watch/(movie|episode)/([^/?#]+)", RegexOption.IGNORE_CASE).find(url) ?: return emptyList()
        val type = match.groupValues[1].lowercase()
        val id = match.groupValues[2]
        val base = baseOf(url)
        val commonHeaders = linkedMapOf(
            "Referer" to "$base/",
            "Origin" to base,
            "Accept" to "*/*",
            "Content-Type" to "application/json",
        )
        val infoUrl = "$base/api/watch/play-info/$type/$id"
        val info = requestJson(infoUrl, headers = commonHeaders) ?: run {
            System.out.println("[TnIdlix] play-info failed for $type/$id")
            return emptyList()
        }
        val token = info.str("gateToken") ?: return emptyList()
        val waitMs = (info.optLong("unlockAt") - info.optLong("serverNow")).coerceIn(0L, 30_000L)
        if (waitMs > 0) {
            System.out.println("[TnIdlix] gate wait ${waitMs}ms for $type/$id")
            delay(waitMs + 150L)
        }

        // The gate may scope its session cookie to /api/watch rather than `/`. Snapshot it from the
        // exact play-info URL (Cloudstream likewise forwards playResponse.cookies explicitly).
        val sessionCookies = LiveClient.cookieHeader(infoUrl)
        val sessionHeaders = if (sessionCookies.isBlank()) commonHeaders else commonHeaders + ("Cookie" to sessionCookies)
        val claim = requestJson(
            "$base/api/watch/session/claim",
            method = "POST",
            body = JSONObject().put("gateToken", token).toString(),
            headers = sessionHeaders,
        ) ?: run {
            System.out.println("[TnIdlix] claim failed for $type/$id, cookiePresent=${sessionCookies.isNotBlank()}")
            return emptyList()
        }
        val claimToken = claim.str("claim") ?: run {
            System.out.println("[TnIdlix] claim response missing claim for $type/$id")
            return emptyList()
        }
        val redeemUrl = claim.str("redeemUrl")?.let { absolute(base, it) } ?: run {
            System.out.println("[TnIdlix] claim response missing redeemUrl for $type/$id")
            return emptyList()
        }
        val redeemed = requestJson(
            redeemUrl,
            method = "POST",
            body = JSONObject().put("claim", claimToken).toString(),
            headers = sessionHeaders,
        ) ?: run {
            System.out.println("[TnIdlix] redeem failed for $type/$id")
            return emptyList()
        }
        val stream = redeemed.str("url")?.let { absolute(base, it) } ?: run {
            System.out.println("[TnIdlix] redeem response missing stream URL for $type/$id")
            return emptyList()
        }
        val subtitles = redeemed.optJSONArray("subtitles").objects().mapNotNull { sub ->
            val path = sub.str("path") ?: return@mapNotNull null
            val lang = sub.str("lang")
            SubtitleTrack(sub.str("label") ?: lang ?: "Subtitle", absolute(base, path), lang)
        }
        val playbackHeaders = buildMap {
            put("Referer", "$base/")
            put("Origin", base)
            if (sessionCookies.isNotBlank()) put("Cookie", sessionCookies)
        }
        System.out.println("[TnIdlix] redeemed $type/$id -> HLS, subtitles=${subtitles.size}")
        return listOf(
            VideoServer(
                name = "Idlix",
                embedUrl = stream,
                subtitles = subtitles,
                headers = playbackHeaders,
            ),
        )
    }

    private suspend fun seriesEpisodes(base: String, slug: String?, detail: JSONObject): List<LiveEpisode> {
        if (slug.isNullOrBlank()) return emptyList()
        val seasons = detail.optJSONArray("seasons").objects()
            .mapNotNull { it.optInt("seasonNumber", Int.MIN_VALUE).takeIf { n -> n != Int.MIN_VALUE } }
            .toMutableSet()
        detail.optJSONObject("firstSeason")?.optInt("seasonNumber", Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let(seasons::add)

        val rows = ArrayList<Triple<Int, Int, JSONObject>>()
        detail.optJSONObject("firstSeason")?.let { season ->
            val seasonNum = season.optInt("seasonNumber", 1)
            season.optJSONArray("episodes").objects().forEach { ep -> rows += Triple(seasonNum, ep.optInt("episodeNumber", 0), ep) }
        }
        for (seasonNum in seasons.sorted()) {
            if (rows.any { it.first == seasonNum }) continue
            val wrapper = getJson("$base/api/series/$slug/season/$seasonNum") ?: continue
            val season = wrapper.optJSONObject("season") ?: continue
            season.optJSONArray("episodes").objects().forEach { ep -> rows += Triple(seasonNum, ep.optInt("episodeNumber", 0), ep) }
        }
        return rows
            .distinctBy { it.third.str("id") ?: "${it.first}:${it.second}" }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .mapIndexedNotNull { index, (season, episode, data) ->
                val id = data.str("id") ?: return@mapIndexedNotNull null
                LiveEpisode(
                    num = index + 1,
                    title = data.str("name") ?: "Episode $episode",
                    url = "$base/tn-watch/episode/$id",
                    thumb = image(data.str("stillPath"), "w300"),
                    season = season,
                    epInSeason = episode.takeIf { it > 0 },
                )
            }
    }

    private suspend fun getJson(url: String): JSONObject? = requestJson(url)

    private suspend fun requestJson(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val text = LiveClient.requestText(
            url = url,
            method = method,
            body = body,
            headers = headers,
            ready = { runCatching { JSONObject(it); true }.getOrDefault(false) },
        ) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun JSONObject.toItem(base: String, fallbackType: String?): LiveItem? {
        val slug = str("slug") ?: return null
        val title = str("title") ?: return null
        val rawType = str("contentType") ?: fallbackType ?: return null
        val movie = rawType.equals("movie", true)
        val endpoint = if (movie) "movies" else "series"
        return LiveItem(
            title = title,
            url = "$base/api/$endpoint/$slug",
            cover = image(str("posterPath"), "w342"),
            type = if (movie) "Movie" else "Series",
            status = str("quality") ?: (str("releaseDate") ?: str("firstAirDate"))?.substringBefore('-'),
        )
    }

    private fun normalizeCatalogUrl(url: String): String {
        val base = baseOf(url)
        if ("/api/" in url) return url
        return "$base/api/browse?page=1&limit=36&sort=latest"
    }

    private fun detailApiUrl(url: String): String? {
        if ("/api/movies/" in url || "/api/series/" in url) return url.substringBefore('?').substringBefore('#')
        val base = baseOf(url)
        Regex("/(movie|series)/([^/?#]+)", RegexOption.IGNORE_CASE).find(url)?.let { m ->
            val endpoint = if (m.groupValues[1].equals("movie", true)) "movies" else "series"
            return "$base/api/$endpoint/${m.groupValues[2]}"
        }
        return null
    }

    private fun nextPage(url: String, hasItems: Boolean): String? {
        if (!hasItems) return null
        val current = Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return if (Regex("([?&])page=\\d+").containsMatchIn(url)) {
            url.replace(Regex("([?&])page=\\d+")) { "${it.groupValues[1]}page=${current + 1}" }
        } else {
            url + (if ('?' in url) "&" else "?") + "page=${current + 1}"
        }
    }

    private fun baseOf(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(DEFAULT_BASE).takeIf { it.startsWith("http") } ?: DEFAULT_BASE

    private fun slugOf(url: String): String? = Regex("/api/(?:movies|series)/([^/?#]+)").find(url)?.groupValues?.get(1)

    private fun image(path: String?, size: String): String? = path?.let {
        if (it.startsWith("http")) it else "$TMDB_IMAGE/$size/${it.trimStart('/')}"
    }

    private fun absolute(base: String, value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        value.startsWith('/') -> base + value
        else -> "$base/$value"
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifBlank { null }
}
