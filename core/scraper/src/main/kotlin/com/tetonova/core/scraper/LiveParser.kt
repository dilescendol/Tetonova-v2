package com.tetonova.core.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder

/** One catalog card scraped from a source's list page. */
data class LiveItem(
    val title: String,
    val url: String,
    val cover: String?,
    val type: String?,
    val status: String?,
)

data class LivePage(
    val items: List<LiveItem>,
    val nextUrl: String? = null,
)

/** A scraped detail page: metadata + the full episode list. */
data class LiveDetail(
    val title: String,
    val cover: String?,
    val synopsis: String?,
    val status: String?,
    val type: String?,
    val studio: String?,
    val released: String?,
    val genres: List<String>,
    val episodes: List<LiveEpisode>,
    val url: String,
    /** When [url] is an episode page, the breadcrumb link to the series page (synopsis + episodes). */
    val seriesUrl: String? = null,
    /** Origin country code where the source exposes it (kuramanime `CN` ⇒ donghua, `JP` ⇒ anime). */
    val country: String? = null,
)

data class LiveEpisode(
    val num: Int,
    val title: String,
    val url: String,
    val thumb: String? = null,
    /** Season + episode-within-season when the source stacks seasons on one page (PusatFilm `/tv/`);
     *  null on flat single-season lists. Carried through to the app's Detail season-accordion. */
    val season: Int? = null,
    val epInSeason: Int? = null,
)

/** One playable mirror/server scraped from a watch page. [embedUrl] is the host iframe URL
 *  (ok.ru / dailymotion / filelions / …) — played in a WebView, since these are embeds, not
 *  direct streams. [name] is the human label (e.g. "OK.ru", "Dailymotion [Ads]").
 *
 *  [variants] is non-empty only for sources that pick the resolution BEFORE the embed (otakudesu
 *  groups its mirrors host→[360p/480p/720p], each resolution a separate iframe). When present,
 *  [StreamExtractor] resolves each variant to a direct stream so the player's Resolusi picker offers
 *  them — i.e. the "Source → Resolusi" model. [embedUrl] then mirrors the top variant (highest reso)
 *  so host checks / the WebView fallback still work. Empty for ordinary single-embed hosts. */
data class VideoServer(
    val name: String,
    val embedUrl: String,
    val variants: List<ServerVariant> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    /** Headers that must survive source resolution and be attached to the media request. */
    val headers: Map<String, String> = emptyMap(),
)

/** A resolution choice within a [VideoServer] (e.g. "720p" → that host's 720p iframe URL). */
data class ServerVariant(val label: String, val embedUrl: String)

/** External subtitle sidecar for a playable server, usually SRT/VTT from short-drama APIs. */
data class SubtitleTrack(val label: String, val url: String, val language: String? = null)

/**
 * Parser for the "tsthemes" / Dooplay WordPress theme shared by virtually every source in the
 * registry (anichin, animexin, otakudesu, samehadaku, anoboy, donghub, …). Selectors are kept
 * broad and forgiving — a missing block yields a blank field, never an exception — so one parser
 * serves all of them and the app degrades gracefully when a site drifts.
 */
object LiveParser {

    /**
     * Scrape the watch page's server list. Anichin/Dooplay expose it as
     * `<select class="mirror"><option value="BASE64(<iframe src=…>)">Name</option>`; decode each
     * option and pull the iframe src. Falls back to a bare on-page `<iframe>` for single-server
     * themes. Best-effort: returns empty on any failure.
     */
    /** anichin tags options like "Dailymotion [ADS]" / "VidHide [ADS]". The marker is noise in our own
     *  picker (we block the ad hosts anyway), so strip it — the pill just reads "Dailymotion". */
    private fun cleanServerLabel(name: String): String =
        name.replace(Regex("\\s*\\[\\s*ads\\s*\\]\\s*", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    /**
     * Unwrap a site's own player shell back to the real host embed.
     *
     * anichin serves OK.ru / Dailymotion through `anichin-player.web.id/index.php?ok=<okruId>` and
     * `?url=<dailymotionId>` — a thin HTML shell whose iframe points at `ok.ru/videoembed/<id>` /
     * `geo.dailymotion.com/player.html?video=<id>`. The shell hides the real host, so these servers
     * missed the OK.ru / Dailymotion handlers we already have and fell through to the generic
     * extract→sniff path, which can't crack them — they'd "fail" and silently failover even though the
     * upstream video is perfectly alive. The ids sit right in the query string, so rewrite to the real
     * embed and let the existing handlers take over.
     */
    private fun unwrapPlayerShell(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        if (!uri.host.orEmpty().endsWith("player.web.id")) return url
        val q = uri.rawQuery.orEmpty()
        Regex("(?:^|&)ok=(\\d+)").find(q)?.groupValues?.get(1)
            ?.let { return "https://ok.ru/videoembed/$it" }
        Regex("(?:^|&)url=([A-Za-z0-9]+)").find(q)?.groupValues?.get(1)
            ?.let { return "https://www.dailymotion.com/embed/video/$it" }
        return url
    }

    fun parseServers(html: String): List<VideoServer> {
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, VideoServer>()  // dedupe by embed url, preserve page order
        fun add(nameHint: String, raw: String) {
            playableUrlFrom(raw)?.let { found ->
                val src = unwrapPlayerShell(found)
                val name = cleanServerLabel(nameHint).ifBlank { hostLabel(src) }
                out.putIfAbsent(src, VideoServer(name, src))
            }
        }
        doc.select("select.mirror option, select[name=mirror] option, select option").forEach { opt ->
            val value = opt.attr("value").trim()
            if (value.isEmpty()) return@forEach
            add(opt.text(), value)
            // anichin labels its options ("OK.ru"…); anixcafe leaves them blank → derive from host.
        }
        doc.select(
            listOf(
                "[data-content]", "[data-src]", "[data-video]", "[data-embed]", "[data-iframe]",
                "[data-em]", "[data-default]", "[data-url]", "[data-link]", "[data-href]", "[data-file]", "[onclick]",
            ).joinToString(", "),
        ).forEach { el ->
            val label = sequenceOf(
                el.attr("data-type"),
                el.attr("data-server"),
                el.attr("data-name"),
                el.attr("title"),
                el.text(),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            listOf(
                "data-content", "data-src", "data-video", "data-embed", "data-iframe",
                "data-em", "data-default", "data-url", "data-link", "data-href", "data-file", "onclick",
            ).forEach { attr ->
                el.attr(attr).takeIf { it.isNotBlank() }?.let { add(label, it) }
            }
        }
        doc.select("iframe[src]").forEach { frame ->
            val src = frame.absUrl("src").ifBlank { frame.attr("src") }
            add(frame.attr("title").ifBlank { frame.attr("name") }, src)
        }
        return groupResolutionServers(out.values.toList())
    }

    /**
     * Anoboy groups its players as `<div class="vmiror">{Server} | <a data-video>{reso}</a>…`, one div
     * per server (Btube, YUp, KrakenFiles, Gofile, M4U, Mirror), each holding that server's resolution
     * buttons. Map each to a VideoServer whose resolution buttons become variants — the Source→Resolusi
     * model the player picker already renders, so the user gets grouped choices instead of a flat list.
     * Redirector `data-video` paths (`/uploads/adsbatch720.php?…`, `/uploads/yup…`) are made absolute so
     * the WebView player can follow them; direct embeds (krakenfiles) pass through unchanged.
     */
    /**
     * Servers for episode [episodeNum] of an anoboy batch/streaming page: take that episode's button from
     * EACH server tab (div.satu/dua/…) and resolve its redirector into a picker entry — Blogger (WebView)
     * plus yourupload (extracts to our ExoPlayer with a 240/360/480/720 Resolusi picker). The dead
     * acbatch/zippyshare tabs are dropped. Empty when the page isn't a batch grid (caller falls back).
     */
    fun anoboyBatchServers(html: String, pageUrl: String, episodeNum: Int): List<VideoServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, VideoServer>()
        listOf("satu", "dua", "tiga", "empat", "lima", "enam").forEach { cls ->
            val button = doc.select("div.$cls a[data-video]")
                .firstOrNull { anoboyBatchEpisodeNumber(it.text()) == episodeNum } ?: return@forEach
            val raw = button.attr("data-video").lowercase()
            if ("acbatch" in raw || "zippyshare" in raw || "/zipy/" in raw) return@forEach // dead hosts
            val variants = anoboyVariantsFor(button)
                .distinctBy { it.label.lowercase() }
                .sortedWith(compareByDescending { resoRank(it.label) })
            if (variants.isEmpty()) return@forEach
            val name = anoboyServerName(anoboyHostLabel(variants.first().embedUrl))
            out.putIfAbsent(name.lowercase(), VideoServer(name, variants.first().embedUrl, variants))
        }
        return out.values.toList()
    }

    /** Server display name from a resolved embed host (Blogger / yourupload / other). */
    private fun anoboyHostLabel(url: String): String = when {
        "blogger.com" in url || "blogspot" in url -> "Blogger"
        "yourupload" in url -> "Yup"
        else -> runCatching { java.net.URI(url).host.orEmpty().removePrefix("www.").substringBefore('.') }
            .getOrDefault("Server")
    }

    fun parseAnoboyServers(html: String, pageUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, VideoServer>()
        // Redirector "Pilih Resolusi" page (yupbatch/adsbatch → <a class="link" href="…/embed/…">240</a>):
        // fold its links into one server whose variants are the resolutions.
        // New-format episode pages only inline a Blogger (Btube) embed for STREAMING, but the Download
        // section lists mp4upload per resolution (240/360/480/720/1080) — mp4upload extracts to a direct
        // mp4 that plays in our ExoPlayer, so surface it as a server "M4U" and PREFER it over Blogger's
        // WebView. Each host is a <span class="ud"> (name in .udj, links in <a class="udl" href>).
        doc.select("span.ud").forEach { grp ->
            val host = grp.selectFirst(".udj")?.text().orEmpty()
            if (!host.contains("m4u", true) && !host.contains("mp4upload", true)) return@forEach
            val variants = grp.select("a.udl[href]").mapNotNull { a ->
                val href = a.absUrl("href").takeIf { "mp4upload.com/" in it } ?: return@mapNotNull null
                ServerVariant(anoboyReso(a.text()), href)
            }.distinctBy { it.embedUrl }.sortedWith(compareByDescending { resoRank(it.label) })
            if (variants.isNotEmpty()) out.putIfAbsent("m4u", VideoServer("M4U", variants.first().embedUrl, variants))
        }
        doc.select("a.link[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            // Only the redirector's resolution links (video embeds), not any stray class="link" anchor.
            if (!Regex("/embed/|video\\.g|yourupload|blogger", RegexOption.IGNORE_CASE).containsMatchIn(href)) return@mapNotNull null
            ServerVariant(anoboyReso(a.text()), href)
        }.distinctBy { it.embedUrl }.sortedWith(compareByDescending { resoRank(it.label) }).let { links ->
            if (links.isNotEmpty()) {
                val name = anoboyServerName(anoboyHostLabel(links.first().embedUrl))
                out.putIfAbsent(name.lowercase(), VideoServer(name, links.first().embedUrl, links))
            }
        }
        doc.select("div.vmiror").forEach { box ->
            val label = box.ownText().substringBefore('|').trim()
            val name = anoboyServerName(label)
            val variants = box.children()
                .filter { it.tagName() == "a" && it.hasAttr("data-video") }
                .flatMap { anoboyVariantsFor(it) }
                .distinctBy { it.label.lowercase() }
                .sortedWith(compareByDescending { resoRank(it.label) })
            if (variants.isEmpty()) return@forEach
            val top = variants.first()
            out.putIfAbsent(name.lowercase(), VideoServer(name, top.embedUrl, variants))
        }
        return out.values.toList()
    }

    /**
     * Resolve one anoboy `data-video` button to playable embed variant(s). Anoboy wraps its real hosts
     * behind two redirector paths whose tokens are right in the query — resolve them without a fetch so
     * the player's WebView sniffer sees a real player (the raw `/uploads/…php` page auto-plays nothing,
     * which is why extraction returned 0 and the app showed "no source"):
     *   - `/uploads/adsbatch720.php?url=TOKEN`            → Blogger video (`blogger.com/video.g?token=`)
     *   - `/uploads/yup/data.php?data=A&data2=B&data3=C…` → yourupload embeds at 240/360/480/720p
     * Direct embeds (krakenfiles etc.) pass through; the DEAD-host filter drops the genuinely dead ones.
     */
    private fun anoboyVariantsFor(a: Element): List<ServerVariant> {
        val raw = a.attr("data-video").trim()
        if (raw.isBlank() || raw.equals("none", ignoreCase = true)) return emptyList()
        val abs = when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> a.absUrl("data-video")
        }
        if (abs.isBlank()) return emptyList()
        val bloggerToken = Regex("adsbatch[^?]*\\?url=([^&]+)", RegexOption.IGNORE_CASE).find(abs)?.groupValues?.get(1)
        if (bloggerToken != null) {
            return listOf(ServerVariant(anoboyReso(a.text()), "https://www.blogger.com/video.g?token=$bloggerToken"))
        }
        if (abs.contains("yup/data.php", ignoreCase = true) || abs.contains("yupbatch", ignoreCase = true)) {
            val params = HashMap<String, String>()
            for (pair in abs.substringAfter('?', "").split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) params[pair.substring(0, eq)] = pair.substring(eq + 1)
            }
            val out = ArrayList<ServerVariant>()
            for ((key, label) in listOf("data" to "240p", "data2" to "360p", "data3" to "480p", "data4" to "720p")) {
                val id = params[key]
                if (!id.isNullOrBlank() && !id.equals("none", ignoreCase = true)) {
                    out.add(ServerVariant(label, "https://www.yourupload.com/embed/$id"))
                }
            }
            if (out.isNotEmpty()) return out
        }
        return listOf(ServerVariant(anoboyReso(a.text()), abs))
    }

    private fun anoboyServerName(label: String): String {
        val l = label.lowercase()
        return when {
            "btube" in l || "b-tube" in l -> "Btube"
            "yup" in l -> "YUp"
            "kra" in l -> "KrakenFiles"
            "gofile" in l -> "Gofile"
            "m4u" in l -> "M4U"
            "mirror" in l -> "Mirror"
            "pc" in l -> "PCloud"
            label.isBlank() -> "Server"
            else -> label.replaceFirstChar { it.uppercase() }
        }
    }

    private fun anoboyReso(label: String): String {
        val t = label.trim()
        if (Regex("\\d+\\s*[-–]\\s*\\d+").containsMatchIn(t)) return "Auto" // e.g. yup "240-720"
        Regex("(2160|1440|1080|720|480|360|240)").find(t)?.value?.let { return "${it}p" }
        if (t.contains("1K", ignoreCase = true)) return "1080p"
        return t.ifBlank { "Auto" }
    }

    /**
     * WordPress "List Category Posts" pagination (`?lcp_page0=N`) — anoboy paginates long episode lists
     * (Tokusatsu: Gotchard, Zero-One) newest-page-first, so page 1 shows the latest ~48 and the oldest
     * (E1, E2…) live on later pages. Returns the distinct page numbers reachable from the pager so
     * [LiveSource] can fetch them and complete the list.
     */
    fun lcpPageNumbers(html: String): List<Int> =
        Regex("lcp_page0=(\\d+)", RegexOption.IGNORE_CASE).findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 1 }
            .distinct()
            .toList()

    private data class ServerNameParts(val key: String, val display: String, val resolution: String?)

    private fun groupResolutionServers(servers: List<VideoServer>): List<VideoServer> {
        val keyed = servers.map { it to serverNameParts(it.name) }
        val grouped = keyed.groupBy { it.second.key }
        val out = ArrayList<VideoServer>()
        val emitted = HashSet<String>()

        for ((server, parts) in keyed) {
            if (!emitted.add(parts.key)) continue
            val rows = grouped[parts.key].orEmpty()
            val hasResolutionChoices = rows.any { it.second.resolution != null }
            if (!hasResolutionChoices) {
                out.add(server)
                continue
            }

            val variants = rows
                .map { (s, p) -> ServerVariant(p.resolution ?: "Auto", s.embedUrl) }
                .distinctBy { it.label.lowercase() }
                .sortedWith(compareByDescending<ServerVariant> { resoRank(it.label) }.thenBy { it.label == "Auto" })
            val top = variants.firstOrNull()
            if (top != null) out.add(VideoServer(parts.display, top.embedUrl, variants))
        }
        return out
    }

    private fun serverNameParts(name: String): ServerNameParts {
        val cleaned = name.replace(Regex("\\s*\\[[^\\]]*\\]"), "").trim()
        val resolution = Regex("""(?i)\b(2160|1440|1080|720|480|360|240)p\b""")
            .find(cleaned)?.groupValues?.getOrNull(0)?.lowercase()
        val base = cleaned
            .replace(Regex("""(?i)\b(2160|1440|1080|720|480|360|240)p\b"""), "")
            .replace(Regex("""[-_]+"""), " ")
            .trim()
            .ifBlank { cleaned }
        val key = when (base.lowercase()) {
            "mp4upload" -> "mp4upload"
            else -> base.lowercase()
        }
        val display = when (key) {
            "mp4upload" -> "Mp4upload"
            else -> base.replaceFirstChar { it.uppercase() }
        }
        return ServerNameParts(key, display, resolution)
    }

    private fun resoRank(label: String): Int =
        Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: -1

    /** Friendly server label from the embed host (for sources like anixcafe that leave option text blank). */
    private fun hostLabel(url: String): String {
        val h = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.").lowercase()
        return when {
            "ok.ru" in h || "odnoklassniki" in h -> "OK.ru"
            "dailymotion" in h -> "Dailymotion"
            "rumble" in h -> "Rumble"
            "videoplayer" in h -> "Player VIP"
            "luluvid" in h -> "LuluVid"
            "dood" in h || "d-s.io" in h || "playmogo" in h || "dsvplay" in h -> "Dood"
            "fembed" in h -> "Fembed"
            "short.ink" in h || "short.icu" in h -> "New Player"
            "streamwish" in h || "wishfast" in h -> "StreamWish"
            "sblongvu" in h || "streamsb" in h || "sbchill" in h || "likessb" in h -> "StreamSB"
            "krakenfiles" in h -> "KrakenFiles"
            else -> h.substringBefore('.').replaceFirstChar { it.uppercase() }.ifBlank { "Server" }
        }
    }

    private fun decodeB64(s: String): String? =
        runCatching { String(java.util.Base64.getMimeDecoder().decode(s)) }.getOrNull()

    private fun iframeSrc(iframeHtml: String): String? =
        Regex("""src\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
            .find(iframeHtml)?.groupValues?.get(1)
            ?.let { if (it.startsWith("//")) "https:$it" else it } // anixcafe gives ok.ru as protocol-relative //ok.ru/…
            ?.takeIf { it.startsWith("http") }

    private fun playableUrlFrom(raw: String): String? {
        val decoded = raw.decodeUrlOnce().let { Parser.unescapeEntities(it, false) }.trim()
        val probes = sequenceOf(
            decoded,
            // Decode Base64 from the RAW value too — `decodeUrlOnce` turns any `+` in the payload into a
            // space (URL rules), which corrupts long Base64 blobs (AnimeXin's Dailymotion `<div>` options)
            // so `decodeB64(decoded)` yields garbage and the embed is lost. The raw value is intact.
            decodeB64(raw.trim()).orEmpty(),
            decodeB64(decoded).orEmpty(),
            decodeB64(decoded.substringAfter("base64,", decoded)).orEmpty(),
        ).filter { it.isNotBlank() }.toList()

        probes.forEach { text ->
            // schema.org VideoObject wrapper (AnimeXin's Dailymotion options): the real embed is in
            // `<meta itemprop="embedUrl" content="…">`. Take it first, else the generic url-regex below
            // grabs the `itemtype="https://schema.org/VideoObject"` namespace URL as if it were the stream.
            Regex("""itemprop\s*=\s*["']embedUrl["']\s+content\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.takeIf(::looksPlayable)?.let { return it }
            iframeSrc(text)?.takeIf(::looksPlayable)?.let { return it }
            Regex("""https?:\\/\\/[^"'<>\s\\]+""").find(text)?.value
                ?.replace("\\/", "/")
                ?.takeIf(::looksPlayable)
                ?.let { return it }
            Regex("""https?://[^"'<>\s]+""").find(text)?.value
                ?.takeIf(::looksPlayable)
                ?.let { return it }
        }
        return decoded
            .let { if (it.startsWith("//")) "https:$it" else it }
            .takeIf(::looksPlayable)
    }

    private fun String.decodeUrlOnce(): String =
        runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)

    private fun looksPlayable(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        if (!StreamExtractor.isPlayable(u)) return false
        val path = u.substringBefore('?').substringBefore('#')
        if (listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".css", ".js").any { path.endsWith(it) }) return false
        if (listOf("discord", "facebook", "twitter", "tiktok", "instagram", "wp-content", "gravatar").any { it in u }) return false
        // schema.org / w3.org are itemtype/namespace URLs (from microdata wrappers), never a video stream.
        if ("schema.org" in u || "://www.w3.org" in u) return false
        return true
    }

    fun parseList(html: String, baseUrl: String): List<LiveItem> {
        val doc = Jsoup.parse(html, baseUrl)
        // Sites differ wildly. Rather than guess one container, probe every known main-grid selector
        // and use whichever yields the MOST cards — the main "latest/ongoing" grid is reliably the
        // largest block, so sidebars / "popular" / "related" widgets never win.
        val cards = listOf(
            ".post-show ul li",   // samehadaku home (Anime Terbaru list)
            ".venz ul li",        // otakudesu ongoing-anime
            ".ml-item",           // winbu (MovieList theme)
            ".item-infinite",     // pusatfilm (GMR theme outer card: poster + title)
            ".nk-search-item",    // nekopoi (anchor card; cover is a CSS background-image)
            ".nk-episodes-area .nk-post-card", // nekopoi homepage latest episodes
            ".nk-hentai-grid li", // nekopoi homepage series grid
            ".animeseries",       // nontonanimeid homepage grid
            "a.as-anime-card",    // nontonanimeid SEARCH grid (as- theme: anchor IS the card, title in h3.as-anime-title)
            "a:has(div.amv)",     // anoboy homepage grid (anchor-wrapped)
            "article[itemtype*=Movie]", // LK21 / Nonton Drama category grids
            ".chivsrc > li",      // otakudesu search
            ".product__item",     // kuramanime
            ".listupd .bsx", "div.bsx",                       // tsthemes (anichin, animexin, samehadaku search…)
            ".listupd article", ".animepost", ".anime-list li",
        ).map { doc.select(it) }.filter { it.isNotEmpty() }.maxByOrNull { it.size } ?: return emptyList()

        val seen = HashSet<String>()
        return cards.mapNotNull { card ->
            // Prefer the title's own link (the series page) over a poster link (often an episode);
            // `closest` covers anchor-wrapped cards (anoboy) where the card IS inside the link.
            // NB: kuramanime's `.product__item__text` opens with `/properties/type/…` filter chips
            // (TV/HD) BEFORE the title, so target the title's `h5 a` (the real `/anime/{id}` link) —
            // a bare `.product__item__text a` would grab the "TV" filter and send every card to the
            // category page.
            val link = card.selectFirst(".tt a[href], .product__item__text h5 a[href], h2 a[href]")
                ?: card.selectFirst("a[href]")
                ?: card.closest("a[href]")
                ?: return@mapNotNull null
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            // Title: try the explicit heading / link-title first, then img alt as a last resort, and
            // reject junk — URLs (otakudesu stores a permalink in `title`) and image filenames
            // (AnimeSail's alt is `1774998318-155312l.jpg`).
            val title = sequenceOf(
                card.selectFirst("h2, h3, .product__item__text h5")?.text(),
                card.selectFirst("a[title]")?.attr("title"),
                card.selectFirst("img[alt]")?.attr("alt"),
                card.selectFirst(".title, .tt")?.let { it.ownText().ifBlank { it.text() } },
                link.text(),
            ).firstNotNullOfOrNull { titleable(it) } ?: return@mapNotNull null
            val clean = cleanTitle(title)
            if (clean.isBlank() || !seen.add(clean.lowercase())) return@mapNotNull null
            LiveItem(
                title = clean,
                url = url,
                cover = coverOf(card),
                type = card.selectFirst(".typez, .type")?.text()?.trim(),
                status = card.selectFirst(".epx, .bt .epx, .status, .ep")?.text()?.trim(),
            )
        }
    }

    fun parseNextPage(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val current = normalUrl(baseUrl)
        val explicit = listOf(
            "a[rel=next]",
            "a.next",
            "a.nextpostslink",
            ".pagination a.next",
            ".page-numbers.next",
            ".nav-links a.next",
            ".wp-pagenavi a.nextpostslink",
        ).firstNotNullOfOrNull { sel ->
            doc.selectFirst(sel)?.absUrl("href")?.takeIf { isUsableNext(it, current) }
        }
        if (explicit != null) return explicit

        // LK21/NontonDrama end their numbered pager with `»`, but that glyph links to the LAST
        // page (for example 1 -> 1177), not the next one. Resolve current+1 before text labels.
        val currentNumber = doc.selectFirst(".page-numbers.current, .pagination .active, .pagenav .current")
            ?.text()?.trim()?.toIntOrNull()
        if (currentNumber != null) {
            val wanted = (currentNumber + 1).toString()
            doc.select("a[href]").firstOrNull { it.text().trim() == wanted }
                ?.absUrl("href")?.takeIf { isUsableNext(it, current) }?.let { return it }
        }

        val textNext = doc.select("a[href]").firstNotNullOfOrNull { a ->
            val t = a.text().trim().lowercase()
            val labelLooksNext = t in setOf("next", "next page", "older", "older posts", "berikutnya", "selanjutnya", "lanjut", "»", "›")
            val ariaLooksNext = a.attr("aria-label").lowercase().let { "next" in it || "berikutnya" in it || "selanjutnya" in it }
            if (labelLooksNext || ariaLooksNext) a.absUrl("href").takeIf { isUsableNext(it, current) } else null
        }
        if (textNext != null) return textNext
        return null
    }

    private fun isUsableNext(url: String, current: String): Boolean =
        url.startsWith("http") && normalUrl(url) != current && "#" !in url.substringAfterLast('/')

    private fun normalUrl(url: String): String = url.substringBefore('#').trimEnd('/')

    fun parseDetail(html: String, url: String): LiveDetail {
        val doc = Jsoup.parse(html, url)
        // Cards may link to an episode page (anime­sail/anoboy/otakudesu), whose entry-title carries
        // an "Episode N Subtitle Indonesia" suffix — strip it so the detail header reads the series.
        val title = detailTitle(doc, url)

        val cover = detailCover(doc)

        // Synopsis container varies wildly per theme; try the specific real-synopsis blocks first,
        // then generic ones, and take the first with real text — skipping empty `<meta description>`
        // and the SEO boilerplate ("Nonton Streaming … download … Subtitle Indonesia terbaru di …")
        // that several sites (samehadaku/anoboy/animesail) put in itemprop/entry-content.
        val synopsis = (if (isNekopoi(url)) nekopoiSynopsis(doc, title) else null)
            ?: localizedSynopsis(doc)
            ?: if (isAnimeXin(url)) null else sequenceOf(
                ".sinopc",                    // otakudesu
                ".synopsis-prose",            // nontonanimeid
                ".mli-desc",                  // winbu
                ".desc.mindes",               // Donghub real synopsis (before generic SEO .desc)
                ".mindes",                    // Donghub episode page
                ".bixbox.synp .entry-content",
                ".synp .entry-content",
                ".contentdeks",               // anoboy (episode pages)
                ".unduhan",                   // anoboy (series/movie/live-action pages — real synopsis,
                                              // NOT the Yoast SEO <meta description> the app fell back to)
                ".entry-content.serial-info", // AnimeSail
                ".desc",                      // samehadaku + many
                "[itemprop=description]",      // anichin (real synopsis lives here)
                ".entry-content.entry-content-single",
                ".synp .entry-content",
                "#synopsisField",             // kuramanime
                ".entry-content",
            ).mapNotNull { doc.selectFirst(it)?.text() }
                .map { cleanSynopsis(it, title) }
                .firstOrNull { it.length > 20 && !isSeoBlurb(it) }
            ?: labeledSynopsis(doc, title)

        val genres = kuramanimeGenres(doc)
            .ifEmpty { doc.select(".genxed a, .gnr a, .mgen a, .anime-card__genres a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct() }
            .ifEmpty { labeledList(doc, "genre", "genres") }

        // Origin country — kuramanime links it as `…/properties/country/CN` (donghua) or `…/JP`.
        val country = doc.selectFirst("a[href*=/country/]")?.text()?.trim()?.takeIf { it.isNotBlank() }

        // `.info-content .spe span` rows like `<b>Status:</b> Ongoing`.
        val info = HashMap<String, String>()
        info.putAll(kuramanimeInfo(doc))
        doc.select(".info-content .spe span, .spe span, .info span").forEach { sp ->
            val key = sp.selectFirst("b")?.text()?.trim()?.trimEnd(':')?.lowercase().orEmpty()
            if (key.isBlank()) return@forEach
            val value = sp.ownText().trim().ifBlank { sp.select("a").joinToString(", ") { it.text() } }
            if (value.isNotBlank()) info.putIfAbsent(key, value)
        }

        return LiveDetail(
            title = title,
            cover = cover,
            synopsis = synopsis,
            status = info.entries.firstOrNull { "status" in it.key }?.value,
            type = info.entries.firstOrNull { "type" in it.key }?.value,
            studio = info.entries.firstOrNull { "studio" in it.key || "network" in it.key }?.value,
            released = info.entries.firstOrNull { "released" in it.key || "rilis" in it.key || "season" in it.key }?.value,
            genres = genres,
            episodes = parseEpisodes(doc),
            url = url,
            seriesUrl = seriesLink(doc, url),
            country = country,
        )
    }

    private fun localizedSynopsis(doc: Document): String? {
        val paragraphs = doc.select(".bixbox.synp .entry-content p, .synp .entry-content p")
        for (i in 0 until paragraphs.size) {
            if (!isIndonesianLabel(paragraphs[i].text())) continue
            for (j in i + 1 until paragraphs.size) {
                val text = paragraphs[j].text().replace(Regex("\\s+"), " ").trim()
                if (text.length > 20 && !isLanguageLabel(text) && !isSeoBlurb(text)) return text
            }
        }
        return null
    }

    private fun nekopoiSynopsis(doc: Document, title: String): String? {
        doc.select(".as-synopsis, .nk-synopsis, .entry-content p, .post-content p, .postbody p, .content p, p")
            .map { cleanNekopoiSynopsis(it.text(), title) }
            .firstOrNull { isUsefulNekopoiSynopsis(it) }
            ?.let { return it }

        doc.select("p, div, span, b, strong").forEach { label ->
            if (!label.ownText().contains("sinopsis", ignoreCase = true)) return@forEach
            cleanNekopoiSynopsis(label.ownText().substringAfter(':', ""), title)
                .takeIf(::isUsefulNekopoiSynopsis)
                ?.let { return it }
            var next = label.nextElementSibling()
            repeat(4) {
                val text = cleanNekopoiSynopsis(next?.text().orEmpty(), title)
                if (isUsefulNekopoiSynopsis(text)) return text
                next = next?.nextElementSibling()
            }
        }
        return null
    }

    private fun cleanNekopoiSynopsis(raw: String, title: String): String =
        cleanSynopsis(raw, title)
            .replace(Regex("^\\s*synopsis\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+(?:genre|anime|producers?|producer|duration|durasi|size|judul|jepang|jenis|episode|status|tayang|skor)\\s*:\\s*.*$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun isUsefulNekopoiSynopsis(s: String): Boolean =
        s.length > 40 && !isSeoBlurb(s) && !s.contains("download", true) && !s.contains("streaming", true)

    private fun kuramanimeGenres(doc: Document): List<String> =
        doc.select(".anime__details__widget a[href*=/properties/genre/]")
            .map { it.text().trim().trimEnd(',') }
            .filter { it.isNotBlank() }
            .distinct()

    private fun kuramanimeInfo(doc: Document): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        doc.select(".anime__details__widget li .row").forEach { row ->
            val key = row.selectFirst(".col-3 span")?.text()?.trim()?.trimEnd(':')?.lowercase().orEmpty()
            if (key.isBlank()) return@forEach
            val valueEl = row.selectFirst(".col-9") ?: return@forEach
            val value = valueEl.text().replace(Regex("\\s+"), " ").trim()
            if (value.isNotBlank()) out.putIfAbsent(key, value)
        }
        return out
    }

    private fun labeledSynopsis(doc: Document, title: String): String? {
        val body = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val match = Regex(
            "(?i)(?:jalan\\s+cerita\\s+)?sinopsis\\s+(.+?)(?=\\s+(?:serial\\s+televisi|tonton\\s+streaming|watch\\s+streaming|watch\\s+the|download\\s+free|download\\s+the|download\\s+video|related\\s+episodes|recommended\\s+series|episode\\s+list|first\\s+episode|new\\s+episode|jadwal\\s+update|orang\\s+hebat|komentari)\\b)",
        ).find(body) ?: return null
        var text = match.groupValues[1].trim()
        if (title.isNotBlank() && text.startsWith(title, ignoreCase = true)) text = text.drop(title.length).trim()
        return cleanSynopsis(text, title).takeIf { it.length > 20 && !isSeoBlurb(it) }
    }

    private fun isAnimeXin(url: String): Boolean = url.contains("animexin", ignoreCase = true)

    private fun isNekopoi(url: String): Boolean = url.contains("nekopoi", ignoreCase = true)

    private fun isIndonesianLabel(s: String): Boolean =
        s.trim().lowercase() in setOf("indonesia", "indonesian", "bahasa indonesia")

    private fun isLanguageLabel(s: String): Boolean =
        s.trim().lowercase() in setOf("english", "eng", "indonesia", "indonesian", "bahasa indonesia")

    private fun detailTitle(doc: Document, url: String): String {
        // Winbu and Nekopoi often expose generic headings ("Nonton Serial Film", "Informasi Anime ...")
        // before the real title, so prefer site-specific detail/info blocks and metadata first.
        val candidates = sequenceOf(
            doc.selectFirst(".mvic-desc h3, .mvic-desc h1, .mvi-desc h3, .mvi-desc h1, .mvic-info h3, .mvic-info h1")?.text(),
            labeledValue(doc, "anime"),
            labeledValue(doc, "judul"),
            doc.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content"),
            doc.selectFirst("h1.entry-title")?.text(),
            doc.selectFirst(".entry-title, article h1, .post h1, h1, .ts-breadcrumb li:last-child span")?.text(),
            doc.title(),
            titleFromUrl(url),
        )
        return candidates.firstNotNullOfOrNull { raw ->
            cleanTitle(raw.orEmpty())
                // Strip the trailing site name several sources append to og:title / <title>: AnimeSail's
                // "– AnimeSail", Anoboy's "– anoBoy" (en-dash), Winbu/Nekopoi's "| Winbu". Handle both
                // hyphen and en-dash separators.
                .replace(Regex("\\s*[-–|]\\s*(Winbu|Nekopoi|NekoPoi|anoBoy|AnimeSail)\\b.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf(::isGoodDetailTitle)
        }.orEmpty()
    }

    private fun detailCover(doc: Document): String? {
        // Try selectors in PRIORITY order (one combined select returns document order, which let a
        // site-header logo carrying itemprop=image — e.g. anixverse — win over the real poster).
        val selectors = listOf(
            ".nk-series-poster", ".mvi-cover", ".mvic-cover", ".mvic-thumb", ".poster",
            ".thumbook .thumb", ".thumb", ".nk-post-image", ".post-thumbnail",
            "[itemprop=image]", "meta[property=og:image]", "meta[name=twitter:image]",
        )
        for (sel in selectors) {
            doc.select(sel).firstNotNullOfOrNull(::coverFromElement)?.let { return it }
        }
        return doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            ?.takeIf { it.startsWith("http") }
    }

    /**
     * The (oldest, newest) episode numbers from kuramanime's "Ep N (Terlama)" / "Ep N (Terbaru)"
     * quick-pick shortcuts inside the `#episodeLists` `data-content` popover; either may be null. These
     * bound the COMPLETE episode range regardless of which paginated slice we fetched, so [LiveSource]
     * can fill the whole list in one shot instead of crawling every page. Verified across many titles:
     * kuramanime episodes are contiguous and page 1 is the oldest slice, so the range is exact.
     */
    fun episodeRange(html: String, baseUrl: String): Pair<Int?, Int?> {
        val popover = Jsoup.parse(html, baseUrl)
            .selectFirst("#episodeLists[data-content], a[data-toggle=popover][data-content]")
            ?.attr("data-content")?.let { Jsoup.parse(it, baseUrl) } ?: return null to null
        fun shortcut(label: String): Int? = popover.select("a[href]")
            .firstOrNull { it.text().contains(label, true) }
            ?.let { Regex("/episode/(\\d+)").find(it.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull() }
        return shortcut("Terlama") to shortcut("Terbaru")
    }

    /**
     * Page numbers reachable from kuramanime's episode-list pager — the `?page=N` targets of its
     * `a.page__link__episode` "‹ / ›" buttons. Empty when the list isn't paginated. [LiveSource]
     * follows these so a detail shows the COMPLETE list, not just one ~13-episode page.
     *
     * The pager sits in the SAME `#episodeLists` `data-content` popover as the episodes, so its links
     * live inside an attribute (not the DOM) — parse that fragment first, then the DOM as a fallback.
     */
    fun episodePageNumbers(html: String, baseUrl: String): List<Int> {
        val doc = Jsoup.parse(html, baseUrl)
        val popover = doc.selectFirst("#episodeLists[data-content], a[data-toggle=popover][data-content]")
            ?.attr("data-content")?.let { Jsoup.parse(it, baseUrl) }
        return listOfNotNull(popover, doc)
            .flatMap { it.select("a.page__link__episode[href]") }
            .mapNotNull { Regex("[?&]page=(\\d+)").find(it.absUrl("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
    }

    /**
     * From an episode page's breadcrumb (`Home › Series › Episode`), return the series-page link —
     * the `[itemprop=item]` that's neither the site home nor an episode-style URL. Null on a page
     * that already is the series (no episode crumb).
     */
    private fun seriesLink(doc: Document, currentUrl: String): String? {
        nekopoiSeriesLink(doc, currentUrl)?.let { return it }
        winbuSeriesLink(doc, currentUrl)?.let { return it }
        val cur = currentUrl.trimEnd('/')
        val episodeUrl = Regex("-episode-\\d+|/episode/|/\\d{4}/\\d{2}/", RegexOption.IGNORE_CASE)
        return doc.select("[itemprop=item][href]").map { it.absUrl("href").trimEnd('/') }
            .firstOrNull { c ->
                c.isNotBlank() && c != cur &&
                    !episodeUrl.containsMatchIn(c) &&
                    c.substringAfter("//").contains('/') // has a path -> not the bare home
            }
    }

    private fun winbuSeriesLink(doc: Document, currentUrl: String): String? {
        if (!currentUrl.contains("winbu", ignoreCase = true)) return null
        val cur = currentUrl.trimEnd('/')
        val uri = runCatching { URI(cur) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().lowercase().trimEnd('/')
        if (Regex("^/(anime|animedonghua|film|others|tvshow)/[^/]+$", RegexOption.IGNORE_CASE).matches(path)) {
            return null
        }

        doc.select("a[href]").firstNotNullOfOrNull { a ->
            val href = a.absUrl("href").trim().trimEnd('/')
            val hPath = runCatching { URI(href).path.orEmpty() }.getOrDefault("")
            href.takeIf {
                it.startsWith("http") &&
                    it != cur &&
                    Regex("^/(anime|animedonghua|film|others|tvshow)/[^/?#]+/?$", RegexOption.IGNORE_CASE).matches(hPath)
            }
        }?.let { return it }

        val slug = path.substringAfterLast('/').replace(Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE), "")
        if (slug.isBlank() || slug == path) return null
        return "${uri.scheme}://${uri.host}/anime/$slug"
    }

    private fun nekopoiSeriesLink(doc: Document, currentUrl: String): String? {
        if (!currentUrl.contains("nekopoi", ignoreCase = true)) return null
        val cur = currentUrl.trimEnd('/')
        val currentPath = runCatching { URI(cur).path.orEmpty().lowercase() }.getOrDefault("")
        if (currentPath.startsWith("/hentai/")) return null
        val currentSlug = currentPath.substringAfterLast('/').replace(Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE), "")
        doc.selectFirst("a.nk-player-series[href]")?.absUrl("href")?.trim()?.trimEnd('/')
            ?.takeIf { it.startsWith("http") && it != cur }
            ?.let { return it }
        doc.select("a[href]").firstNotNullOfOrNull { a ->
            val href = a.absUrl("href").trim().trimEnd('/')
            val path = runCatching { URI(href).path.orEmpty() }.getOrDefault("")
            val seriesSlug = Regex("^/hentai/([^/?#]+)/?$", RegexOption.IGNORE_CASE)
                .matchEntire(path)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
            href.takeIf {
                it.startsWith("http") &&
                    it != cur &&
                    seriesSlug.isNotBlank() &&
                    (a.attr("class").contains("player-series", ignoreCase = true) ||
                        currentSlug == seriesSlug ||
                        currentSlug.startsWith("$seriesSlug-", ignoreCase = true))
            }
        }?.let { return it }
        // Nekopoi's redesigned episode pages no longer link back to their OWN series (the `/hentai/`
        // links on them all point to recommendations), so derive it from the episode slug directly:
        // `/enjo-kouhai-episode-11-subtitle-indonesia/` → `/hentai/enjo-kouhai/`. Only for real episode
        // pages — single videos (JAV/3D/L2D) have no `-episode-N` and no series page.
        if (Regex("-episode-\\d+", RegexOption.IGNORE_CASE).containsMatchIn(currentPath)) {
            // Latest-episode/search slugs carry noise prefixes ("new-release-", "preview-") that the
            // series slug doesn't: `/preview-shoujo-ramune-episode-7-…/` → series `/hentai/shoujo-ramune/`.
            val seriesSlug = currentSlug.replace(
                Regex("^(?:preview|new-release|uncensored|premium|batch)-", RegexOption.IGNORE_CASE), "",
            )
            if (seriesSlug.isNotBlank()) {
                runCatching { URI(cur).let { "${it.scheme}://${it.host}/hentai/$seriesSlug" } }
                    .getOrNull()
                    ?.takeIf { it != cur }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun parseEpisodes(doc: Document): List<LiveEpisode> {
        val baseUri = doc.baseUri()
        System.out.println("[parseEpisodes] baseUri: " + baseUri)
        parseNekopoiEpisodes(doc).takeIf { it.isNotEmpty() }?.let { return it }
        val samehadakuResult = parseSamehadakuEpisodes(doc)
        System.out.println("[parseEpisodes] samehadaku returned: " + samehadakuResult.size)
        if (samehadakuResult.isNotEmpty()) return samehadakuResult
        parseNekopoiCurrentEpisode(doc).takeIf { it.isNotEmpty() }?.let { return it }
        parseSamehadakuEpisodes(doc).takeIf { it.isNotEmpty() }?.let { return it }
        parseWinbuEpisodes(doc).takeIf { it.isNotEmpty() }?.let { return it }
        parseAnoboyEpisodes(doc).takeIf { it.isNotEmpty() }?.let { return it }
        parseDaftarEpisodes(doc).takeIf { it.isNotEmpty() }?.let { return it }
        // PusatFilm (muvipro) stacks all seasons into one `a.s-eps` accordion — the generic scan below
        // would keep only the largest season (season is part of the slug) and collide episode numbers.
        PusatFilmSource.episodes(doc).takeIf { it.isNotEmpty() }?.let { return it }

        // Kuramanime hides its full episode list inside the `data-content` attribute of the "Daftar
        // Episode" popover (#episodeLists) on the anime page. Jsoup keeps attribute values as raw
        // text, so those `<a …/episode/N>` never enter the DOM — parse the attribute as its own
        // fragment to recover them (it already holds just this series' episodes, so trust it like a
        // curated container).
        val popover = doc.selectFirst("#episodeLists[data-content], a[data-toggle=popover][data-content]")
            ?.attr("data-content")?.takeIf { it.contains("/episode/", ignoreCase = true) }
            ?.let { Jsoup.parse(it, doc.baseUri()).select("a[href]") }
            // The popover opens with two "jump to oldest/newest" shortcut buttons (`Ep N (Terlama)` /
            // `Ep N (Terbaru)`) that aren't list items. The newest shortcut points past THIS page's
            // slice (the grid is paginated ~13/page — see LiveSource.parsePaged), so keeping it would
            // inject a phantom episode with a gap. Drop them; real episodes come from each page grid.
            ?.filterNot { a -> a.text().let { it.contains("Terlama", true) || it.contains("Terbaru", true) } }
            ?.takeIf { it.isNotEmpty() }
        // Prefer curated episode containers; if none, scan every anchor (covers winbu/anoboy/etc.
        // whose lists use site-specific markup) and rely on the dominant-slug filter below.
        // `a.ep-button` is kuramanime's on-page episode nav — its watch pages list every episode.
        val known = doc.select(
            ".eplister ul li a, .eplister li a, ul.daftar li a, #daftarepisode li a, " +
                ".episodelist li a, .bxcl li a, .lstepsiode li a, .meta-episodes a[href], a.ep-button[href], " +
                // NontonAnimeID (kotakanime2): the full "Daftar Episode" list. Without this, only the
                // anime card's `.meta-episodes` first+last shortcut links match (so a 12-ep show shows
                // just E1 + E12); the dominant-num distinct() below dedups the overlap.
                ".episode-list-items a[href]," +
                // Samehadaku: episode list on series page (e.g., /anime/chainsaw-man-reze-hen-index/)
                ".list-eps li a, .eps-list li a, .episode-list li a, .eps a[href]",
        )
        val useGeneric = popover == null && known.size < 2
        val anchors = popover ?: if (useGeneric) doc.select("a[href]") else known
        data class Cand(val urlNum: Int?, val end: Int?, val labelNum: Int?, val slug: String, val title: String, val url: String, val thumb: String?)
        val cands = anchors.mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            // Episode number primarily from the canonical URL slug (`.epl-num` labels carry typos —
            // anichin lists ep 325 with epl-num "352"). Use the LAST `-episode-N` — winbu permalinks
            // double it up as `…-episode-2-episode-1` where the trailing one is the real number. The
            // optional `-(\d+)` captures a combined-range page (animesail `…-episode-227-228`). Some
            // animesail entries also label an episode `chapter` (One Piece "Chapter 915"), so accept it.
            val epMatch = Regex("-(?:episode|chapter)-(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE).findAll(url).lastOrNull()
            val urlNum = epMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
            // The on-page label (`.epl-num`, else a trailing "Episode/Chapter N" in the row text). Used
            // to break URL-number ties when the slug is the buggy side — animexin reuses `-episode-5`
            // for ep 6, so WordPress publishes it as `…-episode-5-…-2` (same urlNum, label says 6).
            val labelNum = a.selectFirst(".epl-num")?.text()?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
                ?: Regex("(?:episode|chapter)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull()
            val num = urlNum ?: labelNum ?: return@mapNotNull null
            // End of a combined-range page so the trailing episode(s) aren't lost — from the URL
            // (`…-episode-227-228`) or the title ("Episode 227 – 228").
            val rangeEnd = epMatch?.groupValues?.get(2)?.toIntOrNull()
                ?: Regex("(?:episode|chapter)\\s*\\d+\\s*[–—-]\\s*(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull()
            // Series slug for dominant-grouping. Most themes put it in the last path segment as
            // `series-name-episode-N`; kuramanime instead uses `…/{series-slug}/episode/{N}` where
            // the last segment is the bare number — drop a trailing `/episode/N` (or `/eps/N`) path
            // first so every episode of one series shares ONE slug (otherwise each collapses to its
            // own group and the dominant-slug filter keeps just a single episode).
            val slug = url.trimEnd('/')
                .replace(Regex("/(episode|eps)/\\d+.*$", RegexOption.IGNORE_CASE), "")
                .substringAfterLast('/')
                .replace(Regex("-(?:episode|chapter)-\\d+.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-sub-indo.*$", RegexOption.IGNORE_CASE), "")
            val title = a.selectFirst(".epl-title")?.text()?.trim()?.ifBlank { null } ?: "Episode $num"
            // Per-episode thumbnail when the source's list markup carries one (most themes don't —
            // just numbered links — so this is usually null and the UI falls back to the series cover).
            val thumb = a.selectFirst("img")?.let(::imgSrc) ?: a.closest("li")?.selectFirst("img")?.let(::imgSrc)
            Cand(urlNum, rangeEnd, labelNum, slug, title, url, thumb)
        }
        if (cands.isEmpty()) return emptyList()
        // Dominant-slug filtering is only for the GENERIC scan (drops cross-show "recommended"
        // episode links). A known episode container already holds just this series — trust it fully,
        // since anichin gives some episodes an odd slug (ep 1 is `…-herding-god-` vs `…-gods-`) that
        // the filter would otherwise discard.
        val kept = if (useGeneric) cands.groupBy { it.slug }.maxByOrNull { it.value.size }?.value ?: cands else cands
        // Resolve final numbers: trust the URL number, but when two kept episodes collide on it the
        // slug is the buggy one — fall back to each episode's on-page label to tell them apart, so a
        // typo'd slug (animexin ep 6 → `…-episode-5…-2`) doesn't get deduped away as a second ep 5.
        val urlCounts = kept.mapNotNull { it.urlNum }.groupingBy { it }.eachCount()
        return kept.flatMap { c ->
            val n = if (c.urlNum != null && (urlCounts[c.urlNum] ?: 0) > 1 && c.labelNum != null) c.labelNum
            else c.urlNum ?: c.labelNum!!
            // Combined-range page (animesail "Episode 227 – 228"): emit EVERY episode in the range,
            // each linking to that one shared page — exactly how the site groups them. Guard against a
            // bogus end (a date/season suffix) by only accepting a small forward range.
            val end = c.end?.takeIf { it in (n + 1)..(n + 8) } ?: n
            (n..end).map { e -> LiveEpisode(e, c.title, c.url, c.thumb) }
        }.distinctBy { it.num }.sortedBy { it.num }
    }

    /** Nekopoi's Hentai latest cards link to one episode; the full list lives on `/hentai/{series}/`. */
    private fun parseNekopoiEpisodes(doc: Document): List<LiveEpisode> {
        val base = doc.baseUri()
        if (!base.contains("nekopoi", ignoreCase = true)) return emptyList()
        val path = runCatching { URI(base).path.orEmpty() }.getOrDefault("")
        val looksSeriesPage = Regex("^/hentai/[^/?#]+/?$", RegexOption.IGNORE_CASE).matches(path)
        if (!looksSeriesPage) return emptyList()
        val seriesSlug = path.trim('/').substringAfterLast('/').lowercase()

        return doc.select(
            "a.nk-episode-card[href], .nk-episode-card a[href], .nk-episodes a[href], .nk-episode-list a[href], " +
                ".episode-list a[href], .daftar-episode a[href], a[href*=-episode-]",
        ).mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!url.contains("nekopoi", ignoreCase = true) || !url.contains("-episode-", ignoreCase = true)) return@mapNotNull null
            val episodeSlug = runCatching { URI(url).path.orEmpty().trim('/').substringAfterLast('/').lowercase() }
                .getOrDefault("")
                // Newest episodes carry noise prefixes the series slug lacks
                // (`new-release-saimin-…-episode-6`, also preview-/uncensored-/premium-/batch-) —
                // strip them or E5/E6 fail the startsWith guard and vanish (site 6 → app 4).
                .replace(Regex("^(?:preview|new-release|uncensored|premium|batch)-", RegexOption.IGNORE_CASE), "")
            if (!episodeSlug.startsWith("$seriesSlug-episode-")) return@mapNotNull null
            // Prefer the card's DISPLAYED number ("Ep 9" / "Episode 9") over the URL's — nekopoi admins
            // sometimes mislabel a slug (Ep 9's card links to a "…-episode-8…" URL), which would collide
            // with the real Ep 8 under `distinctBy { num }` and drop Ep 9 entirely.
            val num = Regex("\\b(?:episode|eps|ep)\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val title = nekopoiEpisodeTitle(a, num)
            // Per-episode thumb lives in a DESCENDANT `.nk-episode-card-thumb` background-image, not on
            // the <a> or an ancestor — coverOf scans descendants, so each card keeps its own screenshot.
            val thumb = coverOf(a)
                ?: a.closest("article, li, div")?.let { backgroundImage(it) ?: it.selectFirst("img")?.let(::imgSrc) }
            LiveEpisode(num, title, url, thumb)
            // The newest episode appears twice: once in a thumbless `.latestnow` widget (earlier in the
            // DOM) and once as the real grid card — so de-dupe by num but PREFER the copy that has a thumb,
            // else `distinctBy` would keep the thumbless widget and the newest ep loses its screenshot.
        }.groupBy { it.num }.values.map { g -> g.firstOrNull { it.thumb != null } ?: g.first() }.sortedBy { it.num }
    }

    /** Samehadaku series page episode list parser (e.g., /anime/chainsaw-man-reze-hen-index/).
     *  Also handles movie pages - extracts watch URL from "Nonton Movie" / "Nonton" button. */
    private fun parseSamehadakuEpisodes(doc: Document): List<LiveEpisode> {
        val base = doc.baseUri()
        System.out.println("[SamehadakuParser] parseSamehadakuEpisodes called for: $base")
        if (!base.contains("samehadaku", ignoreCase = true)) {
            System.out.println("[SamehadakuParser] Not a samehadaku URL, skipping")
            return emptyList()
        }
        val path = runCatching { URI(base).path.orEmpty() }.getOrDefault("")
        System.out.println("[SamehadakuParser] Path: $path")
        val isIndexPage = path.contains("-index") || path.contains("/anime/")
        System.out.println("[SamehadakuParser] isIndexPage: $isIndexPage")

        val episodes = mutableListOf<LiveEpisode>()

        // For index pages: look for episode/movie links
        if (isIndexPage) {
            System.out.println("[SamehadakuParser] Scanning for episode/movie links...")

            // First, let's see what links exist on the page
            val allLinks = doc.select("a[href]")
            System.out.println("[SamehadakuParser] Total links on page: ${allLinks.size}")
            val samehadakuLinks = allLinks.filter { it.absUrl("href").contains("samehadaku", ignoreCase = true) }
            System.out.println("[SamehadakuParser] Samehadaku links: ${samehadakuLinks.size}")

            // Log sample links
            samehadakuLinks.take(10).forEach { link ->
                val href = link.absUrl("href")
                val text = link.text().take(50)
                System.out.println("[SamehadakuParser] Link: $href | Text: $text")
            }

            // Common selectors for episode/movie watch links on Samehadaku
            val selectors = listOf(
                // Real samehadaku episode-list markup first (same as SamehadakuSource.episodeWatchLinks)
                "div.lstepsiode a[href]", "div.lchx a[href]", "div.episodelist a[href]",
                ".list-eps li a", ".eps-list li a", ".episode-list li a", ".eps li a",
                ".eps-list a[href]", ".episode-list a[href]", ".list-eps a[href]",
                "a[href*=-episode-]", "a[href*=-movie-]", "a[href*=_movie]", "a[href*=-ona-]",
                // "Nonton Movie" / "Nonton Episode" buttons
                "a.btn-nonton[href]", "a.nonton[href]", ".nonton a[href]", ".btn-watch[href]",
                ".tombol-eps a[href]",
                // Generic: any link containing movie or episode
                "a[href*='movie']", "a[href*='episode']",
            )

            for (selector in selectors) {
                val anchors = doc.select(selector)
                System.out.println("[SamehadakuParser] Selector '$selector' found ${anchors.size} elements")
                if (anchors.isNotEmpty()) {
                    for (a in anchors) {
                        val rawUrl = a.absUrl("href")
                        if (rawUrl.isBlank()) continue
                        val url = rawUrl
                        if (!url.contains("samehadaku", ignoreCase = true)) continue
                        if (url == base || url == base.trimEnd('/')) continue
                        // Nav/category/batch links leak in through the permissive selectors below
                        // (a[href*='movie'] etc.) — same exclusions as SamehadakuSource.episodeWatchLinks,
                        // plus "batch" anywhere (batch slugs look like /…-batch-episode-1-21/).
                        if ("/anime/" in url) continue
                        val lower = url.lowercase()
                        if (listOf("/genre/", "/daftar", "/jadwal", "batch", "/anime-terbaru", "/movie-terbaru", "/populer")
                                .any { it in lower } ||
                            a.text().contains("batch", ignoreCase = true)
                        ) continue

                        System.out.println("[SamehadakuParser] Found episode link: $url | ${a.text().take(30)}")

                        // Extract episode number or detect as movie
                        val isMovieLink = url.contains("-movie-", ignoreCase = true) ||
                                          url.contains("_movie", ignoreCase = true) ||
                                          a.text().contains("movie", ignoreCase = true) ||
                                          a.text().contains("nonton", ignoreCase = true)

                        val num = Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("/episode/(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("\\bEp\\s*(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                            ?: if (isMovieLink) 1 else continue  // Movies get num=1

                        val title = when {
                            isMovieLink -> "Movie"
                            else -> a.selectFirst(".epl-title, .ep-title, .title")?.text()?.trim()
                                ?: a.text().trim().ifBlank { "Episode $num" }
                                ?: "Episode $num"
                        }

                        System.out.println("[SamehadakuParser] Adding episode: num=$num, title=$title, url=$url")
                        episodes.add(LiveEpisode(num, title, url))
                    }
                    if (episodes.isNotEmpty()) {
                        System.out.println("[SamehadakuParser] Found ${episodes.size} episodes from selector '$selector'")
                        return episodes.distinctBy { it.url }.sortedBy { it.num }
                    }
                }
            }
        }

        System.out.println("[SamehadakuParser] Returning ${episodes.size} episodes")
        return episodes.distinctBy { it.url }.sortedBy { it.num }
    }

    private fun nekopoiEpisodeTitle(a: Element, num: Int): String {
        val cleaned = sequenceOf(
            a.selectFirst(".title, .entry-title, h2, h3")?.text(),
            a.attr("title"),
            Regex("(?i)\\b(?:ep|episode)\\s*$num\\b\\s*[:\\-]?\\s*([^\\n\\r]+)")
                .find(a.text().replace(Regex("\\s+"), " "))
                ?.groupValues
                ?.getOrNull(1),
        ).map { raw ->
            raw.orEmpty()
                .replace(Regex("\\b(unduh|download|streaming|nonton)\\b.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }.firstOrNull { it.length > 2 && !it.equals("unduh", true) }
        return cleaned
            ?.takeIf { Regex("^(ep|episode)\\s*$num\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
            ?: "Episode $num"
    }

    private fun parseNekopoiCurrentEpisode(doc: Document): List<LiveEpisode> {
        val base = doc.baseUri()
        if (!base.contains("nekopoi", ignoreCase = true)) return emptyList()
        val num = Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE)
            .find(base)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return emptyList()
        val thumb = doc.selectFirst(".nk-post-image img, .post-thumbnail img, meta[property=og:image]")
            ?.let { if (it.tagName().equals("meta", true)) it.attr("content").takeIf { c -> c.startsWith("http") } else imgSrc(it) }
        return listOf(LiveEpisode(num, "Episode $num", base, thumb))
    }

    /**
     * AnimeSail (and other tsthemes sites) list episodes in `<ul class="daftar"><li><a>…`. Movies and
     * some single entries link as `{slug}-N/` WITHOUT the `-episode-` keyword the generic scan keys on,
     * so those detail pages parsed to zero episodes ("Episode belum tersedia dari sumber"). This
     * container is a curated per-series episode list — every `li a` is one episode — so take the number
     * from `-episode-N`, else a trailing `-N/` slug, else the newest-first DOM position.
     */
    private fun parseDaftarEpisodes(doc: Document): List<LiveEpisode> {
        val anchors = doc.select("ul.daftar li a[href]")
            .filter { a -> a.absUrl("href").let { it.startsWith("http") && !it.contains("/anime/", ignoreCase = true) } }
        if (anchors.isEmpty()) return emptyList()
        val n = anchors.size
        return anchors.mapIndexedNotNull { i, a ->
            val url = a.absUrl("href")
            val slug = url.trimEnd('/').substringAfterLast('/')
            val num = Regex("-(?:episode|chapter)-(\\d+)", RegexOption.IGNORE_CASE).find(slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(?:episode|chapter)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("-(\\d+)$").find(slug)?.groupValues?.get(1)?.toIntOrNull()
                ?: (n - i)
            LiveEpisode(num, "Episode $num", url)
        }.distinctBy { it.num }.sortedBy { it.num }
    }

    /** Winbu detail pages keep episode buttons as plain `/{slug}-episode-N/` links. */
    private fun parseWinbuEpisodes(doc: Document): List<LiveEpisode> {
        val base = doc.baseUri()
        if (!base.contains("winbu", ignoreCase = true)) return emptyList()
        val basePath = runCatching { URI(base).path.orEmpty().lowercase().trimEnd('/') }.getOrDefault("")
        val currentSlug = winbuSeriesSlug(basePath) ?: return emptyList()

        return doc.select("a[href*=-episode-]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }.trim()
            if (!url.contains("winbu", ignoreCase = true)) return@mapNotNull null
            val path = runCatching { URI(url).path.orEmpty().lowercase().trimEnd('/') }.getOrDefault("")
            val urlSlug = winbuSeriesSlug(path) ?: return@mapNotNull null
            if (urlSlug != currentSlug) return@mapNotNull null
            val num = Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE)
                .findAll(url)
                .lastOrNull()
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: return@mapNotNull null
            val rawTitle = a.text().replace(Regex("\\s+"), " ").trim()
            val title = Regex("episode\\s*\\d+", RegexOption.IGNORE_CASE).find(rawTitle)?.value
                ?.replaceFirstChar { it.uppercase() }
                ?: "Episode $num"
            val thumb = a.selectFirst("img")?.let(::imgSrc)
                ?: a.closest("li, article, div")?.selectFirst("img")?.let(::imgSrc)
            LiveEpisode(num, title, url, thumb)
        }.distinctBy { it.num }.sortedBy { it.num }
    }

    private fun winbuSeriesSlug(path: String): String? {
        val clean = path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return clean.replace(Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() && it != clean || path.contains("-episode-", ignoreCase = true) || path.contains("/anime/", ignoreCase = true) }
    }

    /**
     * Anoboy "batch/streaming" pages keep episode choices in server tabs as
     * `a#allvideo[data-video]` buttons, not episode permalinks. Use the first server tab so the
     * detail list is 1..N + specials once, instead of duplicating the same episode across mirrors.
     */
    private fun parseAnoboyEpisodes(doc: Document): List<LiveEpisode> {
        val base = doc.baseUri()
        val isAnoboy = base.contains("anoboy", ignoreCase = true)
        if (!isAnoboy && doc.select("a#allvideo[data-video]").isEmpty()) return emptyList()

        // Streaming/batch pages carry the real episode grid as data-video buttons, each server tab
        // (div.satu/dua/…) repeating the full 1..N list. Pick the tab whose redirector is actually
        // PLAYABLE — Blogger ("adsbatch", plays in WebView) or yourupload — NOT acbatch/zippyshare, which
        // are dead link-lists. Trinity Seven's "EP NN" acbatch tab used to win (only it carried the "EP"
        // keyword) so every episode resolved to "no source"; rank by redirector, and count bare trailing
        // numbers ("BT-HD 01", "Yup 01") too since inside a tab every button is an episode.
        val groups = listOf("satu", "dua", "tiga", "empat", "lima", "enam")
            .map { cls -> doc.select("div.$cls a[data-video]").toList() }
            .filter { it.isNotEmpty() }
        val batchGroup = groups
            .filter { g -> g.count { anoboyBatchEpisodeNumber(it.text()) != null || anoboySpecialLabel(it.text()) != null } >= 2 }
            .maxWithOrNull(compareBy({ anoboyGroupRank(it) }, { it.size }))
        val fromGroup = batchGroup != null
        val anchors = batchGroup ?: doc.select("a#allvideo[data-video], a[data-video]").toList().takeIf { group ->
            group.count { anoboyEpisodeNumber(it.text()) != null || anoboySpecialLabel(it.text()) != null } >= 2
        }

        if (anchors != null) {
            data class Raw(val num: Int?, val special: String?, val url: String)
            val raw = anchors.mapNotNull { a ->
                val text = a.text().replace(Regex("\\s+"), " ").trim()
                val special = anoboySpecialLabel(text)
                val num = if (special != null) null
                    else if (fromGroup) anoboyBatchEpisodeNumber(text) else anoboyEpisodeNumber(text)
                if (num == null && special == null) return@mapNotNull null
                val url = a.attr("abs:data-video").ifBlank { a.absUrl("data-video") }.ifBlank { a.attr("data-video") }
                    .trim()
                    .let { if (it.startsWith("//")) "https:$it" else it }
                    .takeIf { it.startsWith("http") } ?: return@mapNotNull null
                Raw(num, special, url)
            }
            if (raw.isNotEmpty()) {
                // Batch page: point each numbered episode at the PAGE + `#tnep=N` marker so servers() can
                // offer every server tab (Blogger + yourupload) for that episode, not just one tab's
                // redirector — that's what lets yourupload play in our ExoPlayer while Blogger stays as a
                // WebView fallback. Specials keep their direct redirector (matched by label, not number).
                val batchBase = base.substringBefore('#').trimEnd('/')
                val normal = raw.filter { it.num != null }
                    .distinctBy { it.num }
                    .map { LiveEpisode(it.num!!, "Episode ${it.num}", if (fromGroup) "$batchBase#tnep=${it.num}" else it.url) }
                    .sortedBy { it.num }
                val maxNormal = normal.maxOfOrNull { it.num } ?: 0
                val specials = raw.filter { it.special != null }
                    .distinctBy { it.special!!.lowercase() }
                    .mapIndexed { index, r -> LiveEpisode(maxNormal + index + 1, r.special!!, r.url) }
                return normal + specials
            }
        }

        // No EP grid → a single-episode or MOVIE watch page (its data-video buttons are resolutions,
        // not episodes). Emit ONE episode pointing at THIS page so movies are playable and episode
        // pages have a self entry; number from the URL (`-episode-N`), else 1. The full series list is
        // hydrated separately by LiveSource via the breadcrumb / [Streaming] link, then merged in.
        if (isAnoboy && doc.select("a[data-video], iframe#mediaplayer, #fplay").isNotEmpty()) {
            val n = Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE).find(base)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val label = if (n == 1 && base.contains("movie", ignoreCase = true)) "Movie" else "Episode $n"
            return listOf(LiveEpisode(n, label, base.trimEnd('/') + "/"))
        }
        return emptyList()
    }

    // Require the explicit "EP"/"Episode" keyword. The old bare-number fallback matched the resolution
    // buttons on episode/movie WATCH pages ("360", "PC 720", "480P", "240-720") and turned them into
    // phantom "Episode 360/480/720". Real episode grids (streaming/batch pages) always label buttons
    // "EP NN"; modern series pages list episodes as `-episode-N` links handled by the generic scan.
    private fun anoboyEpisodeNumber(label: String): Int? =
        Regex("\\b(?:EP|Episode)\\s*0*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull()

    /** Episode number for a batch server-tab button. Buttons under a tab are always episodes, so a bare
     *  trailing number ("BT-HD 01", "Yup 01") counts too — not just the "EP NN" the flat fallback needs. */
    private fun anoboyBatchEpisodeNumber(label: String): Int? =
        anoboyEpisodeNumber(label)
            ?: Regex("(?:^|[\\s-])0*(\\d{1,4})\\s*$").find(label.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Play-priority of a batch server tab by its redirector so episodes never point at a dead link-list:
     *  Blogger ("adsbatch", plays in WebView) > yourupload ("yupbatch") > acbatch / zippyshare (dead). */
    private fun anoboyGroupRank(anchors: List<Element>): Int {
        val v = anchors.joinToString(" ") { it.attr("data-video").lowercase() }
        return when {
            "adsbatch" in v -> 3
            "yupbatch" in v || "yup/data" in v -> 2
            "acbatch" in v || "zippyshare" in v || "/zipy/" in v -> 0
            else -> 1
        }
    }

    private fun anoboySpecialLabel(label: String): String? {
        val match = Regex("\\b(OVA|ONA|Special|SP)\\s*0*(\\d*)\\b", RegexOption.IGNORE_CASE).find(label) ?: return null
        val type = when (match.groupValues[1].lowercase()) {
            "sp" -> "Special"
            else -> match.groupValues[1].uppercase()
        }
        val n = match.groupValues.getOrNull(2).orEmpty().toIntOrNull()
        return if (n != null) "$type $n" else type
    }

    // ---------------- helpers ----------------

    /**
     * Cover from a card, in order: `data-setbg` (kuramanime), a real `<img>` (most sites), then a
     * CSS `background-image: url(...)` (nekopoi `.nk-search-thumb`).
     */
    private fun coverOf(card: Element): String? {
        card.selectFirst("[data-setbg]")?.let { el ->
            val abs = el.attr("abs:data-setbg").ifBlank { el.attr("data-setbg") }.trim()
            if (abs.isNotBlank() && !abs.startsWith("data:")) return if (abs.startsWith("//")) "https:$abs" else abs
        }
        card.selectFirst("img")?.let { imgSrc(it) }?.let { return it }
        card.select("[style*=background-image]").firstOrNull()?.let { el ->
            Regex("background-image:\\s*url\\(['\"]?([^'\")]+)").find(el.attr("style"))?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { return if (it.startsWith("//")) "https:$it" else it }
        }
        return null
    }

    private fun imgSrc(img: Element): String? {
        // Resolve against the document base so relative covers (e.g. anoboy `/img/upload/…`) load.
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val v = img.attr(attr).trim()
            if (v.isBlank() || v.startsWith("data:")) continue
            val abs = img.absUrl(attr).ifBlank { v }
            return if (abs.startsWith("//")) "https:$abs" else abs
        }
        return null
    }

    private fun backgroundImage(el: Element): String? =
        Regex("background-image:\\s*url\\(['\"]?([^'\")]+)").find(el.attr("style"))?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?.let { if (it.startsWith("//")) "https:$it" else it }

    private fun coverFromElement(el: Element): String? {
        if (el.tagName().equals("meta", true)) {
            return el.attr("content").trim().takeIf { it.startsWith("http") }
        }
        el.attr("data-setbg").trim().takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let {
            return if (it.startsWith("//")) "https:$it" else el.absUrl("data-setbg").ifBlank { it }
        }
        imgSrc(el)?.let { return it }
        el.selectFirst("img")?.let(::imgSrc)?.let { return it }
        backgroundImage(el)?.let { return it }
        el.select("[style*=background-image]").firstNotNullOfOrNull(::backgroundImage)?.let { return it }
        return null
    }

    private fun labeledValue(doc: Document, label: String): String? {
        val re = Regex("(?i)(?:^|\\b)" + Regex.escape(label) + "\\s*[:：]\\s*(.+)$")
        return doc.select("p, li, tr, div, span").firstNotNullOfOrNull { el ->
            val text = el.ownText().replace(Regex("\\s+"), " ").trim()
            re.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun labeledList(doc: Document, vararg labels: String): List<String> {
        val body = doc.body()?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val stop = "(?=\\s+(?:episode\\s+list|terakhir\\s+nonton|download\\s+video|lapor\\s+error|jalan\\s+cerita|sinopsis|serial\\s+televisi|tonton\\s+streaming|jadwal\\s+update|orang\\s+hebat|komentari)\\b)"
        return labels.firstNotNullOfOrNull { label ->
            Regex("(?i)\\b${Regex.escape(label)}\\s*[:ï¼š]\\s*(.+?)$stop").find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?: labeledValue(doc, label)
        }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.length <= 28 && !isSeoBlurb(it) && !it.contains("download", true) }
            .orEmpty()
    }

    private fun titleFromUrl(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        val slug = path.substringAfterLast('/').replace(Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE), "")
        if (slug.isBlank() || slug in setOf("anime", "hentai", "film", "others", "tvshow")) return null
        return slug.split('-').filter { it.isNotBlank() }.joinToString(" ") { part ->
            part.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.uppercase() else ch.toString() }
        }.trim().ifBlank { null }
    }

    private fun isGoodDetailTitle(title: String): Boolean {
        val t = title.trim()
        if (titleable(t) == null) return false
        val l = t.lowercase()
        if (l == "nonton serial film" || l == "informasi anime" || l == "anime") return false
        if (Regex("^informasi\\s+anime\\s+\\d+\\s+kali$", RegexOption.IGNORE_CASE).matches(t)) return false
        if ("just a moment" in l || "enable javascript" in l) return false
        return true
    }

    /** Drop the trailing "Subtitle Indonesia" / episode noise that list titles often carry. */
    /** Accept a title candidate only if it reads like a real name (has letters, not a URL/filename). */
    private fun titleable(s: String?): String? {
        val t = s?.trim().orEmpty()
        if (t.length < 2 || !t.any(Char::isLetter)) return null
        if (t.startsWith("http", ignoreCase = true)) return null
        if (Regex("\\.(jpe?g|png|webp|gif)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        return t
    }

    private fun cleanTitle(t: String): String = t
        .replace(Regex("^\\s*\\[[^\\]]*\\]\\s*"), "")  // leading tags: [NEW Release], [Batch]…
        .replace(Regex("\\s*Subtitle Indonesia.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*Sub Indo\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*Episode\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    /** SEO boilerplate several sites use instead of a real synopsis (samehadaku/pusatfilm/oppadrama). */
    private fun isSeoBlurb(s: String): Boolean {
        val l = s.lowercase()
        return ("streaming" in l && ("nonton film" in l || "nonton streaming" in l || "tonton streaming" in l)) ||
            ("watch streaming" in l && "download" in l) ||
            "subtitle indonesia terbaru di" in l ||
            "trending viral" in l ||
            "gimana filmnya" in l
    }

    private fun cleanSynopsis(raw: String, title: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        s = s.replace(Regex("\\s*Catatan:\\s*Sinopsis\\s+diterjemahkan\\s+secara\\s+otomatis\\s+oleh\\s+Google\\s+Translate\\.?\\s*.*$", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s*\\(Sumber:\\s*[^)]+\\)\\s*", RegexOption.IGNORE_CASE), " ")
        // Some sites prefix "<Title> – synopsis"; strip only when a separator follows, so titles that
        // legitimately start the sentence ("Needy Girl Overdose adalah…") aren't mangled.
        if (title.isNotBlank()) {
            s = s.replace(Regex("^" + Regex.escape(title) + "\\s*[–\\-:]\\s*", RegexOption.IGNORE_CASE), "")
        }
        return s
    }
}
