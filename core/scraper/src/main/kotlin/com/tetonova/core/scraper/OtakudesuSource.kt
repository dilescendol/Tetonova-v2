package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/**
 * Otakudesu has its own watch-page shape that the generic [LiveParser.parseServers] can't read: the
 * playable mirrors live in a `.mirrorstream` block grouped by resolution (Mirror 360p/480p/720p),
 * and each `<a data-content="BASE64({id,i,q})">host</a>` carries NO embed URL — clicking POSTs to
 * `wp-admin/admin-ajax.php` (nonce first, then the mirror) and the server replies with the iframe.
 *
 * We invert that into the player's "Source → Resolusi" model: one [VideoServer] per HOST (vidhide,
 * filedon, ondesuhd, …), each carrying its available resolutions as [ServerVariant]s. So picking
 * "vidhide" offers 360p/480p/720p in the Resolusi pill instead of dumping the whole messy mirror grid
 * on the user. Dead/file-locker hosts (mega, terabox, …) are dropped. Everything is best-effort:
 * any failure returns empty and [LiveSource] falls back to the generic parser (which still finds the
 * default `#pembed` iframe).
 *
 * The action hashes rotate, so they're read from the page's inline script, never hardcoded. The
 * admin-ajax POSTs route through [LiveClient.postBypass] (direct → FlareSolverr) like every other
 * Internet-Positif-blocked request.
 */
object OtakudesuSource {

    /** Cheap signal (used by [LiveSource]) that a watch page is otakudesu-shaped. */
    fun isOtakudesu(html: String): Boolean = "mirrorstream" in html

    private data class Mirror(val host: String, val id: Int, val i: Int, val q: String)

    suspend fun servers(html: String, episodeUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html)
        val anchors = doc.select(".mirrorstream a[data-content]")
        if (anchors.isEmpty()) return emptyList()

        val ajaxUrl = Regex("""https?://[^"']+/wp-admin/admin-ajax\.php""").find(html)?.value
            ?: deriveAjaxUrl(episodeUrl) ?: return emptyList()
        val nonceAction = Regex("""data:\{action:"([0-9a-f]{32})"\}""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val embedAction = Regex("""nonce:[^,}]*,action:"([0-9a-f]{32})"""").find(html)?.groupValues?.get(1) ?: return emptyList()

        val mirrors = anchors.mapNotNull { a ->
            val name = a.text().trim()
            val json = decodeB64(a.attr("data-content"))?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
            Mirror(name, json.optInt("id"), json.optInt("i", -1), json.optString("q"))
        }.filter { it.host.isNotBlank() && it.q.isNotBlank() && it.i >= 0 && !isDeadName(it.host) }
        if (mirrors.isEmpty()) return emptyList()

        // One nonce, then resolve every mirror's iframe in parallel.
        val nonce = ajaxData(ajaxUrl, "action=$nonceAction", episodeUrl) ?: return emptyList()
        val resolved = coroutineScope {
            mirrors.map { m ->
                async {
                    val form = "id=${m.id}&i=${m.i}&q=${enc(m.q)}&nonce=${enc(nonce)}&action=$embedAction"
                    m to ajaxData(ajaxUrl, form, episodeUrl)?.let { decodeB64(it) }?.let(::iframeSrc)
                }
            }.awaitAll()
        }

        // Group by host, keep first-seen order; drop blank embeds + hosts with no in-app extractor.
        val byHost = LinkedHashMap<String, MutableList<ServerVariant>>()
        val display = HashMap<String, String>()
        resolved.forEach { (m, src) ->
            if (src.isNullOrBlank() || !isSupportedEmbed(src)) return@forEach
            val key = m.host.lowercase()
            display.putIfAbsent(key, m.host)
            byHost.getOrPut(key) { mutableListOf() }.add(ServerVariant(m.q, src))
        }

        return byHost.map { (key, variants) ->
            val sorted = variants.distinctBy { it.label }.sortedByDescending { resoRank(it.label) }
            VideoServer(name = display[key] ?: key, embedUrl = sorted.first().embedUrl, variants = sorted)
        }.sortedByDescending { hostPreference(it.name) }
    }

    /** POST [form] and pull the `data` field out of the `{"data":"…"}` reply (direct JSON or the
     *  FlareSolverr-wrapped, HTML-entity-escaped page). Returns the nonce / base64 payload, or null. */
    private suspend fun ajaxData(url: String, form: String, referer: String): String? {
        val body = LiveClient.postBypass(url, form, referer) { "data" in it } ?: return null
        val text = Parser.unescapeEntities(body, false)
        return Regex("\"data\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    private fun iframeSrc(html: String): String? =
        Regex("""src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

    private fun decodeB64(s: String): String? =
        runCatching { String(Base64.getMimeDecoder().decode(s.trim())) }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun deriveAjaxUrl(episodeUrl: String): String? = runCatching {
        URI(episodeUrl).let { "${it.scheme}://${it.host}/wp-admin/admin-ajax.php" }
    }.getOrNull()

    /** Leading digits of a resolution label ("720p" → 720) for high→low ordering. */
    private fun resoRank(label: String): Int = label.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    /** Hosts we won't even try to resolve — file-lockers that never yield an in-app stream. */
    private fun isDeadName(host: String): Boolean {
        val h = host.lowercase()
        return listOf("mega", "terabox", "kfiles", "krakenfiles", "gofile", "acefile").any { it in h }
    }

    /**
     * Keep only mirrors whose RESOLVED embed host has a working in-app extractor. Otakudesu lists lots
     * of dead/file-locker mirrors (mega, solidfiles, mp4upload, yourupload) and a couple of desustream
     * players we can't crack statically (desudrive's dynamic jwplayer `player.php`); per the "drop dead
     * links" rule we hide those entirely instead of dumping the user into a WebView. Strict allowlist —
     * a genuinely new host is dropped until an extractor exists for it (see [StreamExtractor]).
     */
    private fun isSupportedEmbed(url: String): Boolean {
        val u = url.lowercase()
        return when {
            "desudrive.com" in u -> false                  // yourupload iframe wrapper (3rd-party)
            "/desudrive/" in u || "player.php" in u -> false // desustream's dynamic jwplayer variant
            "desustream" in u -> true                      // direct <source googlevideo> players + updesu (→ondesu)
            "odvidhide" in u || "vidhide" in u -> true     // VidHide → generic HLS unpacker
            "filedon" in u -> true                         // presigned Cloudflare-R2 mp4
            else -> false                                  // mega / solidfiles / mp4upload / unknown → drop
        }
    }

    /** Default-pick order: VidHide (HLS) and the first-party desustream players (direct googlevideo
     *  mp4) extract most reliably, so float them above the third-party file hosts. */
    private fun hostPreference(name: String): Int {
        val h = name.lowercase()
        return when {
            "vidhide" in h -> 4
            "ondesu" in h -> 3
            "updesu" in h -> 2
            "filedon" in h -> 1
            else -> 0
        }
    }
}
