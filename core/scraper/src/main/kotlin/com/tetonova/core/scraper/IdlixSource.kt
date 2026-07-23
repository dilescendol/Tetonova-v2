package com.tetonova.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

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

    // A recent desktop Chrome UA — the gate binds a `did` session cookie per user-agent; keep it stable
    // across play-info -> claim -> redeem.
    private const val GATE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Idlix's timed gate -> claim -> redeem DRM (ported from the working CloudStream IdlixProvider).
     * play-info sets a `did` session cookie; the claim (same host) needs it, and the CROSS-HOST redeem on
     * majorplay.net needs it forwarded manually (a shared cookie jar won't send an idlixku cookie there).
     * The redeemed URL is a Majorplay `config-*.json` HLS that StreamExtractor/ExoPlayer already play
     * natively — so Idlix runs in OUR player (no WebView, no site ad/chrome). Self-contained OkHttp +
     * CookieJar so the `did` capture matches CloudStream exactly (LiveClient's shared jar was flaky here).
     */
    suspend fun servers(url: String): List<VideoServer> = withContext(Dispatchers.IO) {
        val match = Regex("/tn-watch/(movie|episode)/([^/?#]+)", RegexOption.IGNORE_CASE).find(url)
            ?: return@withContext emptyList()
        val type = match.groupValues[1].lowercase()
        val id = match.groupValues[2]
        val base = baseOf(url)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val jsonMedia = "application/json".toMediaType()
        fun headers(cookie: String? = null): Headers = Headers.Builder()
            .add("User-Agent", GATE_UA).add("Referer", "$base/").add("Origin", base)
            .add("Accept", "*/*").add("Content-Type", "application/json")
            .apply { if (!cookie.isNullOrBlank()) add("Cookie", cookie) }
            .build()
        fun post(u: String, bodyJson: String, cookie: String?): JSONObject? = runCatching {
            client.newCall(
                Request.Builder().url(u).headers(headers(cookie)).post(bodyJson.toRequestBody(jsonMedia)).build(),
            ).execute().use { r -> r.body?.string()?.let { JSONObject(it) } }
        }.getOrNull()

        // 1) play-info -> gateToken + `did` session cookie (captured from the response's Set-Cookie).
        val (infoBody, setCookies) = runCatching {
            client.newCall(
                Request.Builder().url("$base/api/watch/play-info/$type/$id").headers(headers()).get().build(),
            ).execute().use { r -> r.body?.string() to r.headers("Set-Cookie") }
        }.getOrDefault(null to emptyList())
        val info = infoBody?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: run { System.out.println("[TnIdlix] play-info failed $type/$id"); return@withContext emptyList() }
        val token = info.optString("gateToken").ifBlank { return@withContext emptyList() }
        // Dedup Set-Cookie by name (the gate emits several `did=...`; the last wins, like a browser jar).
        val didCookie = setCookies.map { it.substringBefore(';').trim() }.filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .entries.joinToString("; ") { "${it.key}=${it.value}" }
        val waitMs = (info.optLong("unlockAt") - info.optLong("serverNow")).coerceIn(0L, 30_000L)
        if (waitMs > 0) delay(waitMs + 300L)

        // 2) claim (same host; forward `did`) -> claim token + redeemUrl.
        val claim = post("$base/api/watch/session/claim", JSONObject().put("gateToken", token).toString(), didCookie)
            ?: run { System.out.println("[TnIdlix] claim failed $type/$id"); return@withContext emptyList() }
        val claimToken = claim.optString("claim").ifBlank {
            System.out.println("[TnIdlix] claim rejected $type/$id: $claim"); return@withContext emptyList()
        }
        val redeemUrl = claim.optString("redeemUrl").ifBlank { return@withContext emptyList() }

        // 3) redeem (cross-host majorplay.net; forward `did` by hand) -> Majorplay HLS + subtitles.
        val redeemed = post(redeemUrl, JSONObject().put("claim", claimToken).toString(), didCookie)
            ?: run { System.out.println("[TnIdlix] redeem failed $type/$id"); return@withContext emptyList() }
        val stream = redeemed.optString("url").ifBlank { return@withContext emptyList() }
        val subtitles = redeemed.optJSONArray("subtitles").objects().mapNotNull { sub ->
            val path = sub.str("path") ?: return@mapNotNull null
            SubtitleTrack(sub.str("label") ?: sub.str("lang") ?: "Subtitle", path, sub.str("lang"))
        }
        System.out.println("[TnIdlix] redeemed $type/$id -> HLS ok, subs=${subtitles.size}")
        listOf(
            VideoServer(
                name = "Idlix",
                embedUrl = stream,
                subtitles = subtitles,
                headers = mapOf("Referer" to "$base/", "Origin" to base),
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
