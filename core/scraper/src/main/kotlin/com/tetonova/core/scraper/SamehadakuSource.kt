package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Samehadaku (eastplay theme) loads its video player via JavaScript AJAX:
 * each `<div class="east_player_option">` carries `data-post`, `data-nume`, `data-type`,
 * and clicking POSTs to `wp-admin/admin-ajax.php` with `action=player_ajax&post=…&nume=…&type=…`
 * to get the embed iframe.
 *
 * We resolve every player option in parallel via [LiveClient.postBypass] (FlareSolverr handles Cloudflare)
 * and return the embed URLs. [StreamExtractor] then cracks blogger.com embeds for direct streams.
 */
object SamehadakuSource {

    // DEBUG: Set to true to see what's happening
    private const val DEBUG = true
    private fun log(msg: String) { if (DEBUG) System.out.println("[Samehadaku] $msg") }

    /** Cheap signal (used by [LiveSource]) that an page is Samehadaku watch page.
     *  Returns false for index pages (/anime/...-index/) - those should use episode parser first. */
    fun isSamehadaku(html: String, url: String = ""): Boolean {
        // Skip index pages - they don't have players directly
        if (url.contains("-index", ignoreCase = true)) {
            System.out.println("[Samehadaku] isSamehadaku: $url is index page, returning false")
            return false
        }
        if (url.contains("/anime/") && !url.contains("-episode-") && !url.contains("-movie-")) {
            System.out.println("[Samehadaku] isSamehadaku: $url is anime page without episode/movie, returning false")
            return false
        }
        val isEastplay = "east_player_option" in html && "player_ajax" in html
        // Movie pages have player_ajax but may have different structure
        val hasPlayerAjax = "player_ajax" in html || "wp-admin/admin-ajax.php" in html
        System.out.println("[Samehadaku] isSamehadaku: $url, isEastplay=$isEastplay, hasPlayerAjax=$hasPlayerAjax")
        return isEastplay || (hasPlayerAjax && url.contains("samehadaku", ignoreCase = true))
    }

    /** Read the real watch-page links out of a Samehadaku detail page's episode list
     *  (`div.lstepsiode … div.lchx > a`), newest-first. A movie detail lists exactly one link (the
     *  movie); a series lists its episodes. The watch slug is NOT derivable from the detail slug — e.g.
     *  `/anime/boku-no-hero-academia-the-movie-4/` → `/boku-no-hero-academia-the-movie-youre-next/` — so
     *  we must read the href rather than guess. Filters out nav/category links, keeps root-level watch URLs. */
    fun episodeWatchLinks(detailHtml: String): List<String> {
        val doc = Jsoup.parse(detailHtml)
        return doc.select("div.lstepsiode a[href], div.lchx a[href], div.episodelist a[href]")
            .map { it.absUrl("href").ifBlank { it.attr("href") } }
            .filter { href ->
                href.startsWith("http") && "/anime/" !in href &&
                    Regex("//[^/]*samehadaku", RegexOption.IGNORE_CASE).containsMatchIn(href) &&
                    listOf("/genre/", "/daftar", "/jadwal", "/batch", "/anime-terbaru", "/movie-terbaru", "/populer")
                        .none { it in href.lowercase() }
            }
            .distinct()
    }

    /** Try to derive watch URL from index URL for Samehadaku movies/episodes.
     *  Index: /anime/slug-index/ -> Watch: /slug-movie/ or /slug-episode-1/
     */
    fun deriveWatchUrl(indexUrl: String): String? {
        val base = "https://v2.samehadaku.how"
        // /anime/chainsaw-man-reze-hen-index/ -> /chainsaw-man-reze-hen-movie/
        val movieMatch = Regex("/anime/([\\w-]+)-index/", RegexOption.IGNORE_CASE).find(indexUrl)
        if (movieMatch != null) {
            val slug = movieMatch.groupValues[1]
            System.out.println("[Samehadaku] deriveWatchUrl: derived movie URL: $base/$slug-movie/")
            return "$base/$slug-movie/"
        }
        // Try episode pattern
        val epMatch = Regex("/anime/([\\w-]+)-index/", RegexOption.IGNORE_CASE).find(indexUrl)
        if (epMatch != null) {
            val slug = epMatch.groupValues[1]
            System.out.println("[Samehadaku] deriveWatchUrl: derived episode URL: $base/$slug-episode-1/")
            return "$base/$slug-episode-1/"
        }
        System.out.println("[Samehadaku] deriveWatchUrl: could not derive watch URL from $indexUrl")
        return null
    }

    private data class PlayerOption(val name: String, val nume: String, val type: String)

    suspend fun servers(html: String, episodeUrl: String): List<VideoServer> {
        log("servers called for: $episodeUrl")
        log("HTML length: ${html.length}, contains player_ajax: ${"player_ajax" in html}, contains ajax: ${"ajax" in html.lowercase()}, contains iframe: ${"iframe" in html.lowercase()}")
        val doc = Jsoup.parse(html)

        // Check if page has player AJAX options
        val hasPlayerAjax = "player_ajax" in html || "wp-admin/admin-ajax.php" in html
        log("hasPlayerAjax: $hasPlayerAjax")

        // Try multiple selectors for player options
        val options = doc.select("div.east_player_option, div.easy-player-option, div[class*=player-option], div[data-nume], div[data-id], .player-options a, .streaming a, a[data-nume], a[data-type]")
        if (options.isEmpty()) {
            log("No player options found in HTML. Looking for direct iframes...")
            // Try parsing direct iframes as fallback
            return parseDirectIframes(doc, episodeUrl)
        }
        log("Found ${options.size} player options")

        val postId = options.firstNotNullOfOrNull { it.attr("data-post").takeIf { it.isNotBlank() } }
            ?: doc.selectFirst("[data-post]")?.attr("data-post")?.takeIf { it.isNotBlank() }
        if (postId == null) {
            log("No data-post ID found")
            return parseDirectIframes(doc, episodeUrl)
        }
        log("postId: $postId")

        val ajaxUrl = deriveAjaxUrl(episodeUrl) ?: run {
            log("Could not derive AJAX URL from: $episodeUrl")
            return parseDirectIframes(doc, episodeUrl)
        }
        log("ajaxUrl: $ajaxUrl")

        val wanted = options.mapNotNull { opt ->
            val name = opt.selectFirst("span")?.text()?.trim()
                ?: opt.attr("data-name").takeIf { it.isNotBlank() }
                ?: opt.text().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val nume = opt.attr("data-nume").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val type = opt.attr("data-type").takeIf { it.isNotBlank() } ?: "schtml"
            PlayerOption(name.trim(), nume, type)
        }.distinctBy { it.nume }

        if (wanted.isEmpty()) {
            log("No valid player options parsed")
            return parseDirectIframes(doc, episodeUrl)
        }
        log("Resolved ${wanted.size} player options: ${wanted.map { it.name }}")

        // Resolve every player option in parallel.
        val resolved = coroutineScope {
            wanted.map { opt ->
                async {
                    log("Fetching embed for: ${opt.name} (nume=${opt.nume}, type=${opt.type})")
                    val src = ajaxIframe(ajaxUrl, postId, opt.nume, opt.type, episodeUrl)
                    log("${opt.name} → ${src?.take(80) ?: "FAILED"}")
                    opt to src
                }
            }.awaitAll()
        }

        val servers = resolved
            .mapNotNull { (opt, src) -> src?.let { VideoServer(opt.name, it) } }
            .distinctBy { it.embedUrl }

        log("Returning ${servers.size} servers: ${servers.map { "${it.name} -> ${it.embedUrl.take(60)}" }}")
        return servers
    }

    /** Fallback: parse direct iframes from the page (for themes that don't use AJAX). */
    private suspend fun parseDirectIframes(doc: Document, baseUrl: String): List<VideoServer> {
        val servers = mutableListOf<VideoServer>()
        doc.select("iframe[src]").forEach { frame ->
            val src = frame.absUrl("src").ifBlank { frame.attr("src") }
            if (src.isNotBlank() && !src.contains("google.com/recaptcha") && !src.lowercase().contains("ads")) {
                val name = StreamExtractor.playableHostLabel(src)
                servers.add(VideoServer(name, src))
            }
        }
        log("Direct iframes fallback: ${servers.size} servers found")
        return servers
    }

    /** POST the player_ajax form and pull the iframe src from the HTML reply. */
    private suspend fun ajaxIframe(url: String, postId: String, nume: String, type: String, referer: String): String? {
        val form = "action=player_ajax&post=$postId&nume=$nume&type=$type"
        log("POSTing to $url with form: $form")
        val body = LiveClient.postBypass(url, form, referer) { it.isNotBlank() }
        if (body == null) {
            log("postBypass returned null for ${url}")
            return null
        }
        log("postBypass got ${body.length} chars, contains iframe: ${"iframe" in body}")
        // Some hosts return JSON with embed URL
        if (body.trimStart().startsWith("{")) {
            log("Response is JSON, parsing...")
            val embed = try {
                org.json.JSONObject(body).optString("embed")
                    .takeIf { it.isNotBlank() }
                    ?: org.json.JSONObject(body).optString("src")
                    .takeIf { it.isNotBlank() }
                    ?: org.json.JSONObject(body).optString("url")
                    .takeIf { it.isNotBlank() }
                    ?: org.json.JSONObject(body).optString("data")
                    ?.let { if (it.contains("iframe")) iframeSrc(it) else it }
            } catch (e: Exception) { log("JSON parse error: ${e.message}"); null }
            if (embed != null) {
                log("JSON embed: ${embed.take(80)}")
                return iframeSrc(embed) ?: embed
            }
        }
        return iframeSrc(body)
    }

    private fun iframeSrc(html: String): String? {
        // Try standard iframe extraction
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.let { raw ->
                return normalizeUrl(raw)
            }
        // Try data-src or data-easy attributes
        Regex("""data-(?:src|embed|easy)=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.let { raw ->
                return normalizeUrl(raw)
            }
        // Try src= attribute directly
        Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.let { raw ->
                return normalizeUrl(raw)
            }
        return null
    }

    private fun normalizeUrl(raw: String): String? {
        val url = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            raw.startsWith("/") -> raw // relative URL, skip
            else -> return null
        }
        // Samehadaku's "Server 1" ships `file.fm/embed/…`, which 301-redirects to `files.fm`. The player's
        // WebView pins navigation to the embed host, and `files.fm` does NOT end with `file.fm`, so the
        // redirect gets blocked → black screen. Pin to the real host up front so no cross-host hop happens.
        return url.replace("://file.fm/", "://files.fm/")
    }

    private fun deriveAjaxUrl(episodeUrl: String): String? = runCatching {
        URI(episodeUrl).let { "${it.scheme}://${it.host}/wp-admin/admin-ajax.php" }
    }.getOrNull()
}
