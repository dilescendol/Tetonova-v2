package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Oploverz (plus.oploverz.ltd) is a bespoke Next.js site — NOT a WordPress theme — so the generic
 * [LiveParser] can't read its markup (that's why its Home rail was stuck on the 2 bundled fallback
 * cards). It is backed by a clean, public, header-less JSON API at backapi.oploverz.ac which we use
 * directly for the whole flow:
 *
 *   GET /api/episodes[?page=N]            → latest-episodes feed (10/page) → the "Rilis Terbaru" rail
 *   GET /api/series?q={query}             → series search
 *   GET /api/series/{slug}                → series metadata + totalEpisodes
 *   GET /api/series/{slug}/episodes/{n}   → one episode's streamUrl[] watch embeds
 *
 * Both the site and the API address episodes by NUMBER (…/series/{slug}/episode/{n}), so [detail]
 * synthesizes the full 1..totalEpisodes list in a single call (the same approach [LiveSource.parsePaged]
 * uses for kuramanime) and [servers] resolves each episode's streams on demand by slug+number — no
 * episode-id bookkeeping.
 *
 * The watch embeds are filedon.co (presigned R2 mp4 → [StreamExtractor.filedon]) and dailymotion (HLS →
 * extractor) which play directly in ExoPlayer, plus oplo2.4meplayer.pro / blogger.com which the player
 * resolves via its WebView HLS sniffer / embed fallback. Everything is best-effort: any failure returns
 * empty/null and [LiveSource] falls back to the bundled seed.
 */
object OploverzSource {

    private const val API = "https://backapi.oploverz.ac/api"
    /** Canonical front-end host for the series/watch URLs we hand back (the API host differs). */
    private const val SITE = "https://plus.oploverz.ltd"

    /** Any oploverz domain (they rotate) shares the "oploverz" token — the dispatch signal in [LiveSource]. */
    fun isOploverz(url: String): Boolean = "oploverz" in url.lowercase()

    /** "Rilis Terbaru": the latest-episodes feed (first 2 pages ≈ 20), one card per series. */
    suspend fun latest(): List<LiveItem> = coroutineScope {
        val pages = listOf(1, 2).map { p -> async { getJson("$API/episodes?page=$p") } }.awaitAll()
        val seen = HashSet<String>()
        pages.filterNotNull()
            .flatMap { it.optJSONArray("data").objects() }
            .mapNotNull { episodeToItem(it) }
            .filter { seen.add(it.url) }
    }

    suspend fun search(query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val json = getJson("$API/series?q=" + URLEncoder.encode(query, "UTF-8")) ?: return emptyList()
        return json.optJSONArray("data").objects().mapNotNull { seriesToItem(it) }
    }

    suspend fun detail(url: String): LiveDetail? {
        val slug = slugOf(url) ?: return null
        val d = getJson("$API/series/$slug")?.optJSONObject("data") ?: return null
        val title = d.str("title") ?: return null
        // Episodes are addressed by number on both site and API, and are contiguous 1..total, so build
        // the whole list here and let [servers] resolve each on demand (cf. kuramanime synthesis).
        val total = d.optInt("totalEpisodes", 0)
        val episodes = (1..total).map { n -> LiveEpisode(n, "Episode $n", "$SITE/series/$slug/episode/$n") }
        return LiveDetail(
            title = title,
            cover = d.str("poster"),
            synopsis = d.str("description"),
            status = d.str("status"),
            type = d.str("releaseType"),
            studio = d.optJSONObject("studio")?.str("name"),
            released = d.optJSONObject("season")?.str("name") ?: d.str("releaseDate")?.take(4),
            genres = d.optJSONArray("genres").objects().mapNotNull { it.str("name") },
            episodes = episodes,
            url = "$SITE/series/$slug",
        )
    }

    /**
     * One "Nonton Online" source with a **Resolusi** picker (the otakudesu "Source → Resolusi" model),
     * instead of one flat source per resolution. Each resolution maps to the best embed for it,
     * preferring a directly-playable filedon mp4 (from the download links) over the streamUrl embed —
     * 4meplayer/blogger don't statically extract, so without filedon the Resolusi picker couldn't switch
     * in ExoPlayer; with it the picker is real direct streams (and it's often the only direct source
     * when streamUrl is 4meplayer/blogger-only).
     */
    suspend fun servers(url: String): List<VideoServer> {
        val slug = slugOf(url) ?: return emptyList()
        val n = numOf(url) ?: return emptyList()
        val d = getJson("$API/series/$slug/episodes/$n")?.optJSONObject("data") ?: return emptyList()

        val byReso = LinkedHashMap<String, String>() // resolution label -> embed URL
        d.optJSONArray("streamUrl").objects().forEach { s ->
            val embed = s.str("url") ?: return@forEach
            byReso.putIfAbsent(resoLabel(s.str("source")), embed)
        }
        // Download links double as a direct stream (filedon /view→/embed presigned mp4; pixeldrain
        // /u→/api/file range file) — the player extracts these to ExoPlayer. Prefer them per resolution
        // so the Resolusi picker is real direct streams (and they're often the ONLY direct source when
        // streamUrl is 4meplayer/blogger-only).
        directByReso(d).forEach { (reso, embed) -> byReso[reso] = embed }
        if (byReso.isEmpty()) return emptyList()

        val variants = byReso.entries
            .sortedByDescending { resoRank(it.key) }
            .map { ServerVariant(it.key, it.value) }
            .distinctBy { it.embedUrl } // collapse "HD"/"720p" that point at the same file
        return listOf(VideoServer(name = "Nonton Online", embedUrl = variants.first().embedUrl, variants = variants))
    }

    /** A streamUrl entry's resolution label: "Nonton Online 720p" → "720p"; "HD"/"sd" → "HD"/"SD"; else "Auto". */
    private fun resoLabel(source: String?): String {
        val s = source.orEmpty()
        Regex("(\\d+)\\s*p", RegexOption.IGNORE_CASE).find(s)?.let { return it.groupValues[1] + "p" }
        return when {
            s.contains("hd", true) -> "HD"
            s.contains("sd", true) -> "SD"
            else -> "Auto"
        }
    }

    /** High→low ordering key for a resolution label (numbered by its pixels; HD on top, SD at the bottom). */
    private fun resoRank(label: String): Int =
        label.takeWhile { it.isDigit() }.toIntOrNull() ?: when (label) { "HD" -> 2000; "SD" -> 1; else -> 500 }

    /**
     * resolution → a directly-playable download URL, by priority: filedon mp4 (presigned R2, fastest)
     * → pixeldrain (range-served file, often the only direct source for blogger-only episodes) → filedon
     * mkv. `putIfAbsent` means the first pass to fill a resolution wins.
     */
    private fun directByReso(d: JSONObject): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        fun scan(formatMp4Only: Boolean, map: (String) -> String?) {
            d.optJSONArray("downloadUrl").objects()
                .filter { !formatMp4Only || it.str("format").equals("mp4", true) }
                .forEach { fmt ->
                    fmt.optJSONArray("resolutions").objects().forEach { res ->
                        val q = res.str("quality") ?: return@forEach
                        res.optJSONArray("download_links").objects().forEach { link ->
                            link.str("url")?.let(map)?.let { out.putIfAbsent(q, it) }
                        }
                    }
                }
        }
        scan(formatMp4Only = true) { u -> if ("filedon.co/view/" in u) u.replace("/view/", "/embed/") else null }
        scan(formatMp4Only = false) { u -> if ("pixeldrain.com/u/" in u) u else null }
        scan(formatMp4Only = false) { u -> if ("filedon.co/view/" in u) u.replace("/view/", "/embed/") else null }
        return out.toList()
    }

    // ---- mappers ----

    /** A latest-feed episode → a series card (carries the latest episode label in `status`). */
    private fun episodeToItem(ep: JSONObject): LiveItem? {
        val s = ep.optJSONObject("series") ?: return null
        val slug = s.str("slug") ?: return null
        val title = s.str("title") ?: return null
        return LiveItem(
            title = title,
            url = "$SITE/series/$slug",
            cover = s.str("poster"),
            type = "Anime",
            status = ep.str("episodeNumber")?.let { "Episode $it" },
        )
    }

    private fun seriesToItem(s: JSONObject): LiveItem? {
        val slug = s.str("slug") ?: return null
        val title = s.str("title") ?: return null
        return LiveItem(title = title, url = "$SITE/series/$slug", cover = s.str("poster"), type = "Anime", status = s.str("status"))
    }

    // ---- helpers ----

    private suspend fun getJson(url: String): JSONObject? {
        // A JSON body is "ready" the moment it parses — skip [LiveClient]'s challenge tiers (which would
        // otherwise engage for a short reply, e.g. a 0-result search that's under the challenge-length
        // threshold), and parse defensively so an HTML error page just yields null.
        val body = LiveClient.getHtml(url) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    /** `…/series/{slug}` or `…/series/{slug}/episode/{n}` → slug. */
    private fun slugOf(url: String): String? =
        Regex("/series/([^/?#]+)").find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun numOf(url: String): Int? =
        Regex("/episode/(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** org.json arrays have no iterator — view a (possibly null) array as its object elements. */
    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** optString returning null for a missing / JSON-null / blank value, trimmed. */
    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifBlank { null }
}
