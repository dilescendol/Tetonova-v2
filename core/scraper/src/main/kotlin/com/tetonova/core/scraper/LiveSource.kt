package com.tetonova.core.scraper

import java.net.URLEncoder

/**
 * High-level live-content fetch: HTML via [LiveClient], structure via [LiveParser]. Every call is
 * best-effort and returns empty/null on any failure so callers can fall back to the bundled seed.
 */
object LiveSource {

    suspend fun list(url: String): List<LiveItem> {
        val html = LiveClient.getHtml(url) ?: return emptyList()
        return LiveParser.parseList(html, url)
    }

    /** Scrape the playable server/mirror list from an episode watch page (for the player's
     *  "Source video" picker). Empty on any failure. */
    suspend fun servers(url: String): List<VideoServer> {
        // Kuramanime injects its player <source> tags via JS *after* the CF challenge clears, so the
        // WebView must keep polling until they appear (flare/byparr hand back the bare page too early).
        // Every other source just needs a non-challenge page.
        // Kuramanime: kuramadrive's <source> tags are JS-injected after a per-load token flow that the
        // host RATE-LIMITS — so do exactly ONE load here (a retry would just burn another token and make
        // the throttle worse). Quick re-opens may briefly come back empty; the NoSource screen offers a
        // manual retry, and 2b enumeration is lazy so it doesn't add competing kuramadrive loads.
        if ("kuramanime" in url.lowercase() || "kuramadrive" in url.lowercase()) {
            val h = LiveClient.getHtml(url) { KuramanimeSource.hasSources(it) }
            return if (h != null) KuramanimeSource.servers(h) else emptyList()
        }
        val html = LiveClient.getHtml(url) ?: return emptyList()
        // Otakudesu hides its mirrors behind admin-ajax and groups them by resolution — resolve them
        // into host→[reso] servers. Falls through to the generic parser (which still finds the default
        // #pembed iframe) when otakudesu's AJAX flow yields nothing.
        if (OtakudesuSource.isOtakudesu(html)) {
            OtakudesuSource.servers(html, url).let { if (it.isNotEmpty()) return it }
        }
        return LiveParser.parseServers(html)
    }

    suspend fun detail(url: String): LiveDetail? {
        // A page is "usable" if it yielded a synopsis OR an episode list — NOT title, which many
        // themes (winbu/samehadaku/…) bury where our selectors miss it; the hero title comes from
        // the list card anyway (withLive keeps it when live.title is blank).
        val usable = { x: LiveDetail -> !x.synopsis.isNullOrBlank() || x.episodes.isNotEmpty() }

        // Kuramanime (and similar) host the full detail — synopsis + the COMPLETE episode list — on
        // the series/anime page; a trailing `/episode/N` is just a single-episode watch page that
        // shows only a slice. When handed such a watch URL, resolve to its series page first so we
        // pull every episode, not the few the watch page renders. Fall back to the watch URL below.
        val seriesFromPath = url.replace(Regex("/episode/\\d+/?$", RegexOption.IGNORE_CASE), "").takeIf { it != url }
        if (seriesFromPath != null) {
            LiveClient.getHtml(seriesFromPath)?.let { sHtml ->
                // Only short-circuit to the series page when it actually yielded the episode list —
                // its whole purpose. A synopsis-only page (episodes empty) must NOT win here, or the
                // UI would synthesize placeholder episodes instead of falling back to the watch page.
                parsePaged(seriesFromPath, sHtml).takeIf { it.episodes.isNotEmpty() }?.let { return it }
            }
        }

        val html = LiveClient.getHtml(url) ?: return null
        val d = parsePaged(url, html)
        // Cards on episode-based sites (AnimeSail/Anoboy) land on an episode page that lacks the
        // series synopsis + full episode list — follow the breadcrumb to the series page for those.
        val series = d.seriesUrl
        if (series != null && series != url && (d.synopsis.isNullOrBlank() || d.episodes.isEmpty())) {
            LiveClient.getHtml(series)?.let { sHtml ->
                val sd = parsePaged(series, sHtml)
                if (usable(sd)) return sd
            }
        }
        return d.takeIf(usable)
    }

    /**
     * Parse a detail page and complete kuramanime's paginated episode list (it shows only a
     * ~13-episode slice per `?page=N`).
     *
     * Primary path — extend, don't crawl: the "(Terbaru)" quick-pick gives the newest episode number
     * and kuramanime's episodes are contiguous with deterministic watch URLs (`{anime}/episode/{n}`),
     * so we fill the first page's grid up to that newest episode in ONE shot. Crawling every page of a
     * long donghua (e.g. Wushen Zhuzai, 654 eps ≈ 50 pages) is far too slow and leaves the UI sitting
     * on synthetic placeholders meanwhile. Falls back to following the pager (bounded) when there's no
     * "(Terbaru)" shortcut, and returns the page unchanged when it isn't paginated.
     */
    private suspend fun parsePaged(url: String, firstHtml: String): LiveDetail {
        val first = LiveParser.parseDetail(firstHtml, url)
        val gridMax = first.episodes.maxOfOrNull { it.num } ?: 0
        val gridMin = first.episodes.minOfOrNull { it.num } ?: 1
        val (oldest, latest) = LiveParser.episodeRange(firstHtml, url)

        // The Terlama/Terbaru quick-picks bound the WHOLE range no matter which slice we fetched, so
        // fill it in one shot. `oldest` guards the rare case where page 1 isn't the oldest slice.
        if (first.episodes.isNotEmpty() && latest != null && latest > gridMax) {
            val have = first.episodes.associateBy { it.num }
            val start = minOf(oldest ?: gridMin, gridMin)
            val base = first.episodes.first().url.replace(Regex("/episode/\\d+.*$", RegexOption.IGNORE_CASE), "")
            val top = minOf(latest, start + 3000) // sanity bound against a mislabeled "newest"
            val full = (start..top).map { n -> have[n] ?: LiveEpisode(n, "Episode $n", "$base/episode/$n") }
            return first.copy(episodes = full)
        }

        val queue = ArrayDeque(LiveParser.episodePageNumbers(firstHtml, url).filter { it != pageOf(url) })
        if (queue.isEmpty()) return first
        val episodes = LinkedHashMap<Int, LiveEpisode>()
        first.episodes.forEach { episodes.putIfAbsent(it.num, it) }
        val visited = mutableSetOf(pageOf(url))
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 12) {
            val p = queue.removeFirst()
            if (!visited.add(p)) continue
            val pageUrl = withPage(url, p)
            val html = LiveClient.getHtml(pageUrl) ?: continue
            LiveParser.parseDetail(html, pageUrl).episodes.forEach { episodes.putIfAbsent(it.num, it) }
            LiveParser.episodePageNumbers(html, pageUrl).forEach { if (it !in visited) queue.addLast(it) }
        }
        return first.copy(episodes = episodes.values.sortedBy { it.num })
    }

    /** Current `?page=` of a kuramanime episode-pager URL (default 1). */
    private fun pageOf(url: String): Int =
        Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** [url] with its `page` query set to [n], replacing any existing one. */
    private fun withPage(url: String, n: Int): String {
        val stripped = url.replace(Regex("([?&])page=\\d+&?"), "$1").trimEnd('?', '&')
        val sep = if (stripped.contains('?')) '&' else '?'
        return "$stripped${sep}page=$n"
    }

    /** WordPress site-search (`/?s=query`) against the source's base URL. */
    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank() || query.isBlank()) return emptyList()
        val url = "$base/?s=" + URLEncoder.encode(query, "UTF-8")
        val html = LiveClient.getHtml(url) ?: return emptyList()
        return LiveParser.parseList(html, url)
    }
}
