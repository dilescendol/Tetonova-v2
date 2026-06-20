package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URI
import java.util.Base64

/**
 * NontonAnimeID (WordPress "kotakanime2" theme) hides its mirrors behind an admin-ajax player like
 * otakudesu, but the shape differs: each watch page has server tabs `li.kotak_player_option` carrying
 * `data-post` / `data-type` (server name) / `data-nume`, and only the default tab's iframe is rendered
 * inline — the rest load via `POST wp-admin/admin-ajax.php` `action=player_ajax`. That handler rejects
 * any request missing a same-site `Origin` header ("Invalid origin"), so the POST routes through
 * [LiveClient.postBypass] with [origin] set.
 *
 * We resolve every tab into its iframe URL and keep only the ones our player can actually crack: the
 * site's own `*.kotakanimeid.link/video-embed` servers (a repeating-XOR payload → direct googlevideo
 * mp4 / kotakanimeid m3u8, handled by [StreamExtractor]) plus OK.ru. The cryptic-but-faithful tab name
 * (`Kotakvideo`, `Lokal-1080`, …) becomes the player's "Source" pick; the resolution is whatever that
 * server serves (decided when the embed is cracked). Third-party hosts we have no extractor for
 * (gdriveplayer, gdplayer, yourupload, sibnet, mega, …) are dropped rather than dumped into a broken
 * WebView. Best-effort throughout: any failure returns empty and [LiveSource] falls back to the
 * generic [LiveParser.parseServers].
 */
object NontonAnimeIDSource {

    /** Cheap signal (used by [LiveSource]) that a watch page is kotakanime2-shaped. */
    fun isNontonAnimeID(html: String): Boolean = "kotak_player_option" in html

    private data class Tab(val name: String, val nume: String)

    suspend fun servers(html: String, episodeUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html)
        val tabs = doc.select("li.kotak_player_option")
        if (tabs.isEmpty()) return emptyList()

        val post = tabs.firstNotNullOfOrNull { it.attr("data-post").takeIf(String::isNotBlank) } ?: return emptyList()
        val nonce = findNonce(html) ?: return emptyList()
        val ajaxUrl = deriveAjaxUrl(episodeUrl) ?: return emptyList()
        val origin = runCatching { URI(episodeUrl).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: return emptyList()

        val wanted = tabs.mapNotNull { t ->
            val name = t.attr("data-type").trim()
            val nume = t.attr("data-nume").trim()
            if (name.isBlank() || nume.isBlank()) null else Tab(name, nume)
        }
        if (wanted.isEmpty()) return emptyList()

        // Resolve every tab's iframe in parallel (the default tab resolves the same way as the rest).
        val resolved = coroutineScope {
            wanted.map { tab ->
                async {
                    val form = "action=player_ajax&post=$post&nume=${tab.nume}&serverName=${tab.name}&nonce=$nonce"
                    tab to ajaxIframe(ajaxUrl, form, episodeUrl, origin)
                }
            }.awaitAll()
        }

        return resolved
            .mapNotNull { (tab, src) -> if (src != null && isSupportedEmbed(src)) VideoServer(tab.name, src) else null }
            .distinctBy { it.embedUrl }
            .sortedByDescending { preference(it.name, it.embedUrl) }
    }

    /** POST the player_ajax form and pull the iframe src out of the `<iframe … src="…">` reply. */
    private suspend fun ajaxIframe(url: String, form: String, referer: String, origin: String): String? {
        val body = LiveClient.postBypass(url, form, referer, origin) { "src=" in it && "iframe" in it } ?: return null
        return iframeSrc(body)
    }

    private fun iframeSrc(html: String): String? {
        val raw = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.trim()
            ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw" // protocol-relative (gdriveplayer/ok.ru) → https
            raw.startsWith("http") -> raw
            else -> null
        }
    }

    /**
     * The player nonce ships base64-encoded inside a `<script src="data:text/javascript;base64,…">`
     * that defines `kotakajax = {…,"nonce":"…",…}` (not as plain HTML), so decode the data-URIs and
     * read it from there. Scoped to `kotakajax` so we don't grab some other plugin's nonce.
     */
    private fun findNonce(html: String): String? {
        for (m in Regex("""data:text/javascript;base64,([A-Za-z0-9+/=]+)""").findAll(html)) {
            val js = runCatching { String(Base64.getMimeDecoder().decode(m.groupValues[1])) }.getOrNull() ?: continue
            Regex("""kotakajax\s*=[\s\S]*?"nonce":"(\w+)"""").find(js)?.let { return it.groupValues[1] }
        }
        // Fallback: some pages inline the config instead of base64-wrapping it.
        return Regex("""kotakajax\s*=[\s\S]*?"nonce":"(\w+)"""").find(html)?.groupValues?.get(1)
    }

    private fun deriveAjaxUrl(episodeUrl: String): String? = runCatching {
        URI(episodeUrl).let { "${it.scheme}://${it.host}/wp-admin/admin-ajax.php" }
    }.getOrNull()

    /** Keep only mirrors whose resolved host has a working in-app extractor (see [StreamExtractor]);
     *  drop everything else (gdriveplayer / gdplayer / yourupload / sibnet / mega / rpmvip / …). */
    private fun isSupportedEmbed(url: String): Boolean {
        val u = url.lowercase()
        return "kotakanimeid.link/video-embed" in u || "ok.ru" in u || "odnoklassniki" in u
    }

    /** Default-pick order. Gate on the resolved HOST first — a native direct mp4 beats third-party
     *  OK.ru regardless of the tab name (the "Ok-uhd" tab contains "uhd" but is NOT a native 1080p
     *  source) — then rank native servers by their quality hint (1080p mp4 > googlevideo > other). */
    private fun preference(name: String, embed: String): Int {
        val h = name.lowercase()
        val native = "kotakanimeid.link" in embed.lowercase()
        return when {
            native && ("1080" in h || "uhd" in h) -> 6 // native 1080p direct mp4
            native && "kotakvideo" in h -> 5           // native googlevideo (reliable)
            native && ("hd" in h || "lokal" in h) -> 4 // other native (HLS / mp4)
            native -> 3                                // any other native
            else -> 1                                  // OK.ru — extractable, but a third-party fallback
        }
    }
}
