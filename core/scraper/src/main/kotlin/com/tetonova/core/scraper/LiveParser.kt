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

data class LiveEpisode(val num: Int, val title: String, val url: String, val thumb: String? = null)

/** One playable mirror/server scraped from a watch page. [embedUrl] is the host iframe URL
 *  (ok.ru / dailymotion / filelions / …) — played in a WebView, since these are embeds, not
 *  direct streams. [name] is the human label (e.g. "OK.ru", "Dailymotion [Ads]").
 *
 *  [variants] is non-empty only for sources that pick the resolution BEFORE the embed (otakudesu
 *  groups its mirrors host→[360p/480p/720p], each resolution a separate iframe). When present,
 *  [StreamExtractor] resolves each variant to a direct stream so the player's Resolusi picker offers
 *  them — i.e. the "Source → Resolusi" model. [embedUrl] then mirrors the top variant (highest reso)
 *  so host checks / the WebView fallback still work. Empty for ordinary single-embed hosts. */
data class VideoServer(val name: String, val embedUrl: String, val variants: List<ServerVariant> = emptyList())

/** A resolution choice within a [VideoServer] (e.g. "720p" → that host's 720p iframe URL). */
data class ServerVariant(val label: String, val embedUrl: String)

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
    fun parseServers(html: String): List<VideoServer> {
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, VideoServer>()  // dedupe by embed url, preserve page order
        fun add(nameHint: String, raw: String) {
            playableUrlFrom(raw)?.let { src ->
                val name = nameHint.trim().ifBlank { hostLabel(src) }
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
            decodeB64(decoded).orEmpty(),
            decodeB64(decoded.substringAfter("base64,", decoded)).orEmpty(),
        ).filter { it.isNotBlank() }.toList()

        probes.forEach { text ->
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
            "a:has(div.amv)",     // anoboy homepage grid (anchor-wrapped)
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

        val textNext = doc.select("a[href]").firstNotNullOfOrNull { a ->
            val t = a.text().trim().lowercase()
            val labelLooksNext = t in setOf("next", "next page", "older", "older posts", "berikutnya", "selanjutnya", "lanjut", "»", "›")
            val ariaLooksNext = a.attr("aria-label").lowercase().let { "next" in it || "berikutnya" in it || "selanjutnya" in it }
            if (labelLooksNext || ariaLooksNext) a.absUrl("href").takeIf { isUsableNext(it, current) } else null
        }
        if (textNext != null) return textNext

        val currentNumber = doc.selectFirst(".page-numbers.current, .pagination .active, .pagenav .current")
            ?.text()?.trim()?.toIntOrNull()
        if (currentNumber != null) {
            val wanted = (currentNumber + 1).toString()
            doc.select("a[href]").firstOrNull { it.text().trim() == wanted }
                ?.absUrl("href")?.takeIf { isUsableNext(it, current) }?.let { return it }
        }
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
        val synopsis = sequenceOf(
            ".sinopc",                    // otakudesu
            ".synopsis-prose",            // nontonanimeid
            ".mli-desc",                  // winbu
            ".contentdeks",               // anoboy
            ".entry-content.serial-info", // AnimeSail
            ".desc",                      // samehadaku + many
            "[itemprop=description]",      // anichin (real synopsis lives here)
            ".entry-content.entry-content-single",
            ".synp .entry-content",
            ".anime__details__text",      // kuramanime
            ".entry-content",
        ).mapNotNull { doc.selectFirst(it)?.text() }
            .map { cleanSynopsis(it, title) }
            .firstOrNull { it.length > 20 && !isSeoBlurb(it) }

        val genres = doc.select(".genxed a, .gnr a, .mgen a").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Origin country — kuramanime links it as `…/properties/country/CN` (donghua) or `…/JP`.
        val country = doc.selectFirst("a[href*=/country/]")?.text()?.trim()?.takeIf { it.isNotBlank() }

        // `.info-content .spe span` rows like `<b>Status:</b> Ongoing`.
        val info = HashMap<String, String>()
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
                .replace(Regex("\\s*[-|]\\s*(Winbu|Nekopoi|NekoPoi).*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf(::isGoodDetailTitle)
        }.orEmpty()
    }

    private fun detailCover(doc: Document): String? {
        val targeted = doc.select(
            ".mvi-cover, .mvic-cover, .mvic-thumb, .poster, .thumbook .thumb, .thumb, " +
                ".nk-post-image, .post-thumbnail, [itemprop=image], meta[property=og:image], meta[name=twitter:image]",
        )
        targeted.firstNotNullOfOrNull(::coverFromElement)?.let { return it }
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

        return doc.select(
            "a.nk-episode-card[href], .nk-episode-card a[href], .nk-episodes a[href], .nk-episode-list a[href], " +
                ".episode-list a[href], .daftar-episode a[href], a[href*=-episode-][href*=subtitle-indonesia]",
        ).mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            if (!url.contains("nekopoi", ignoreCase = true) || !url.contains("-episode-", ignoreCase = true)) return@mapNotNull null
            // Prefer the card's DISPLAYED number ("Ep 9" / "Episode 9") over the URL's — nekopoi admins
            // sometimes mislabel a slug (Ep 9's card links to a "…-episode-8…" URL), which would collide
            // with the real Ep 8 under `distinctBy { num }` and drop Ep 9 entirely.
            val num = Regex("\\b(?:episode|eps|ep)\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val title = nekopoiEpisodeTitle(a, num)
            val thumb = a.selectFirst("img")?.let(::imgSrc)
                ?: backgroundImage(a)
                ?: a.closest("article, li, div")?.let { backgroundImage(it) ?: it.selectFirst("img")?.let(::imgSrc) }
            LiveEpisode(num, title, url, thumb)
        }.distinctBy { it.num }.sortedBy { it.num }
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
        if (!doc.baseUri().contains("anoboy", ignoreCase = true) && doc.select("a#allvideo[data-video]").isEmpty()) {
            return emptyList()
        }
        val grouped = listOf("satu", "dua", "tiga", "empat", "lima", "enam")
            .map { cls -> doc.select("div.$cls a[data-video]").toList() }
        val anchors = grouped.firstOrNull { group ->
            group.count { anoboyEpisodeNumber(it.text()) != null || anoboySpecialLabel(it.text()) != null } >= 2
        } ?: doc.select("a#allvideo[data-video], a[data-video]").toList().takeIf { group ->
            group.count { anoboyEpisodeNumber(it.text()) != null || anoboySpecialLabel(it.text()) != null } >= 2
        } ?: return emptyList()

        data class Raw(val num: Int?, val special: String?, val url: String)

        val raw = anchors.mapNotNull { a ->
            val text = a.text().replace(Regex("\\s+"), " ").trim()
            val special = anoboySpecialLabel(text)
            val num = if (special == null) anoboyEpisodeNumber(text) else null
            if (num == null && special == null) return@mapNotNull null
            val url = a.attr("abs:data-video").ifBlank { a.absUrl("data-video") }.ifBlank { a.attr("data-video") }
                .trim()
                .let { if (it.startsWith("//")) "https:$it" else it }
                .takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Raw(num, special, url)
        }
        if (raw.isEmpty()) return emptyList()

        val normal = raw.filter { it.num != null }
            .distinctBy { it.num }
            .map { LiveEpisode(it.num!!, "Episode ${it.num}", it.url) }
            .sortedBy { it.num }
        val maxNormal = normal.maxOfOrNull { it.num } ?: 0
        val specials = raw.filter { it.special != null }
            .distinctBy { it.special!!.lowercase() }
            .mapIndexed { index, r -> LiveEpisode(maxNormal + index + 1, r.special!!, r.url) }
        return normal + specials
    }

    private fun anoboyEpisodeNumber(label: String): Int? =
        Regex("\\b(?:EP|Episode)\\s*0*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b0*(\\d{1,4})\\b").find(label)?.groupValues?.get(1)?.toIntOrNull()

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
            "subtitle indonesia terbaru di" in l ||
            "trending viral" in l ||
            "gimana filmnya" in l
    }

    private fun cleanSynopsis(raw: String, title: String): String {
        var s = raw.replace(Regex("\\s+"), " ").trim()
        // Some sites prefix "<Title> – synopsis"; strip only when a separator follows, so titles that
        // legitimately start the sentence ("Needy Girl Overdose adalah…") aren't mangled.
        if (title.isNotBlank()) {
            s = s.replace(Regex("^" + Regex.escape(title) + "\\s*[–\\-:]\\s*", RegexOption.IGNORE_CASE), "")
        }
        return s
    }
}
