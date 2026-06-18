package com.tetonova.core.scraper

import org.jsoup.Jsoup

/**
 * Kuramanime (`v18.kuramanime.ing`) watch pages. The page is Cloudflare-gated, so [LiveClient.getHtml]
 * fetches it through byparr (`/v1cf`) — which runs a real browser, so the rendered HTML already
 * contains the **kuramadrive** player's resolution `<source>` tags (kuramadrive runs a token flow in
 * JS that byparr executes for us).
 *
 * The on-page server `<select id="changeServer">` (Kuramadrive / FileLions / FileMoon / StreamWish /
 * MEGA) is `display:none` and only kuramadrive — the default, first-party server — is rendered into the
 * page; the others load on demand behind a fresh token flow (a separate byparr solve each), so we
 * expose just kuramadrive. That's the richest option anyway: a direct, seekable, headerless mp4 served
 * per resolution (`<source id="source720|480|360" size="…">`), which becomes the player's Source →
 * Resolusi exactly like otakudesu's host-grouped mirrors.
 */
object KuramanimeSource {

    /** Cheap signal (used by [LiveSource]) that a watch page is kuramanime-shaped. */
    fun isKuramanime(url: String, html: String): Boolean =
        "kuramanime" in url.lowercase() || "kuramadrive" in html

    /**
     * True once kuramadrive's JS has injected its per-resolution mp4 `<source size=…>` tags — i.e.
     * exactly when [servers] can succeed. Used as the WebView "ready" predicate so the on-device
     * browser keeps polling the live DOM until the player sources actually appear (they're injected
     * after the Cloudflare challenge clears + a token flow runs), instead of returning the bare page.
     */
    fun hasSources(html: String): Boolean = servers(html).isNotEmpty()

    fun servers(html: String): List<VideoServer> {
        val doc = Jsoup.parse(html)
        // plyr-style resolution sources: <source src="…mp4" size="720" type="video/mp4">
        val variants = doc.select("source[src][size]").mapNotNull { s ->
            val url = s.attr("src").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val size = s.attr("size").filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
            ServerVariant("${size}p", url)
        }.distinctBy { it.label }.sortedByDescending { it.label.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        if (variants.isEmpty()) return emptyList()
        return listOf(VideoServer(name = "Kuramadrive", embedUrl = variants.first().embedUrl, variants = variants))
    }
}
