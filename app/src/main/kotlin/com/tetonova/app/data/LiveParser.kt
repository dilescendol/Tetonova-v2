package com.tetonova.app.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One catalog card scraped from a source's list page. */
data class LiveItem(
    val title: String,
    val url: String,
    val cover: String?,
    val type: String?,
    val status: String?,
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

data class LiveEpisode(val num: Int, val title: String, val url: String)

/** One playable mirror/server scraped from a watch page. [embedUrl] is the host iframe URL
 *  (ok.ru / dailymotion / filelions / …) — played in a WebView, since these are embeds, not
 *  direct streams. [name] is the human label (e.g. "OK.ru", "Dailymotion [Ads]"). */
data class VideoServer(val name: String, val embedUrl: String)

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
        doc.select("select.mirror option, select[name=mirror] option").forEach { opt ->
            val value = opt.attr("value").trim()
            if (value.isEmpty()) return@forEach
            val src = decodeB64(value)?.let(::iframeSrc) ?: return@forEach
            // anichin labels its options ("OK.ru"…); anixcafe leaves them blank → derive from host.
            val name = opt.text().trim().ifBlank { hostLabel(src) }
            out.putIfAbsent(src, VideoServer(name, src))
        }
        if (out.isEmpty()) {
            doc.select("iframe[src]").map { it.absUrl("src").ifBlank { it.attr("src") } }
                .firstOrNull { it.startsWith("http") }
                ?.let { out[it] = VideoServer("Server", it) }
        }
        return out.values.toList()
    }

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
            .find(iframeHtml)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }

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

    fun parseDetail(html: String, url: String): LiveDetail {
        val doc = Jsoup.parse(html, url)
        // Cards may link to an episode page (anime­sail/anoboy/otakudesu), whose entry-title carries
        // an "Episode N Subtitle Indonesia" suffix — strip it so the detail header reads the series.
        val title = cleanTitle(
            doc.selectFirst("h1.entry-title")?.text()?.trim()
                ?: doc.selectFirst(".entry-title, .ts-breadcrumb li:last-child span")?.text()?.trim().orEmpty(),
        )

        val cover = doc.selectFirst(".thumbook .thumb img, .thumb img, [itemprop=image], .poster img")?.let { imgSrc(it) }

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
        val cur = currentUrl.trimEnd('/')
        val episodeUrl = Regex("-episode-\\d+|/episode/|/\\d{4}/\\d{2}/", RegexOption.IGNORE_CASE)
        return doc.select("[itemprop=item][href]").map { it.absUrl("href").trimEnd('/') }
            .firstOrNull { c ->
                c.isNotBlank() && c != cur &&
                    !episodeUrl.containsMatchIn(c) &&
                    c.substringAfter("//").contains('/') // has a path -> not the bare home
            }
    }

    private fun parseEpisodes(doc: Document): List<LiveEpisode> {
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
                ".episodelist li a, .bxcl li a, .lstepsiode li a, .meta-episodes a[href], a.ep-button[href]",
        )
        val useGeneric = popover == null && known.size < 2
        val anchors = popover ?: if (useGeneric) doc.select("a[href]") else known
        data class Cand(val urlNum: Int?, val end: Int?, val labelNum: Int?, val slug: String, val title: String, val url: String)
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
            Cand(urlNum, rangeEnd, labelNum, slug, title, url)
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
            (n..end).map { e -> LiveEpisode(e, c.title, c.url) }
        }.distinctBy { it.num }.sortedBy { it.num }
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
