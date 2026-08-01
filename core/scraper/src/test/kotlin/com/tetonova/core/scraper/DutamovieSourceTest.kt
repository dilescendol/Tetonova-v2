package com.tetonova.core.scraper

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DutamovieSourceTest {

    @Test
    fun recognisesLegacyEntryAndCurrentMirror() {
        assertTrue(DutamovieSource.isDutamovie("https://ppspublishers.com/category/box-office/"))
        assertTrue(DutamovieSource.isDutamovie("https://restaurantesabadell.com/box-office/"))
        assertTrue(DutamovieSource.isDutamovie("https://bdmoviesonline.com/"))
        // The current entry point is a bare IP, which has no domain for the host match to catch —
        // without an explicit entry every DutaMovie URL would be routed away from this scraper.
        assertTrue(DutamovieSource.isDutamovie("${DutamovieSource.DEFAULT_BASE}/box-office/"))
    }

    /**
     * Live end-to-end against the current mirror. Every target is DISCOVERED from the site's own
     * listings — an earlier version pinned specific film slugs and started failing the moment those
     * were rotated off the catalogue, which says nothing about whether the scraper still works.
     * Note DutaMovie soft-404s: an unknown path answers 200 with a generic page, so "the slug is
     * gone" looks identical to "the site is up" unless you parse it.
     */
    @Test
    fun liveCatalogSeriesAndPlayerResolveEndToEnd() = runBlocking {
        val page = DutamovieSource.listPage("${DutamovieSource.DEFAULT_BASE}/box-office/")
        assertTrue(page.items.isNotEmpty(), "DutaMovie catalog should return cards")
        assertTrue(page.items.all { it.cover?.startsWith("http") == true }, "Every card needs an absolute cover")

        // Search for a title the catalogue is carrying right now.
        val wanted = page.items.first().title.substringBefore(" (").trim()
        assertTrue(wanted.length >= 3, "Catalog title should be usable as a search term, got '$wanted'")
        val search = DutamovieSource.search(DutamovieSource.DEFAULT_BASE, wanted)
        assertTrue(
            search.any { wanted.take(6).equals(it.title.take(6), ignoreCase = true) || wanted in it.title },
            "Search for a live catalog title '$wanted' should return it",
        )

        // Series come from the /tv/ archive (the only listing that actually resolves; /category/series/
        // is one of the soft-404 pages).
        val seriesCards = DutamovieSource.listPage("${DutamovieSource.DEFAULT_BASE}/tv/").items
            .filter { "/tv/" in it.url }
        assertTrue(seriesCards.isNotEmpty(), "The /tv/ archive should list series")
        val details = seriesCards.take(4).mapNotNull { DutamovieSource.detail(it.url) }
        assertTrue(details.isNotEmpty(), "Series detail should parse")
        // Deliberately NOT "episodes >= 2": the archive is mostly currently-airing 2026 shows with a
        // single episode out, so that assertion measured the broadcast schedule rather than the parser.
        // What actually proves the episode list was parsed is that the links point at real /eps/ pages
        // and not at the series page itself, which is what a broken selector would fall back to.
        val richest = details.maxByOrNull { it.episodes.size }
        assertNotNull(richest)
        assertTrue(richest.episodes.isNotEmpty(), "A series should expose at least one episode")
        assertTrue(
            richest.episodes.all { it.url.startsWith("http") && "/eps/" in it.url },
            "Episode links must be absolute /eps/ pages, got ${richest.episodes.map { it.url }.take(3)}",
        )
        assertTrue(
            richest.episodes.map { it.url }.toSet().size == richest.episodes.size,
            "Episode list must not repeat the same URL",
        )

        // Player tabs: walk the first few films until one exposes a natively extractable VidStack
        // mirror, rather than pinning a single film that may only carry WebView-only hosts today.
        var checkedServers = false
        var extractedHls: String? = null
        var extractedHeaders: Map<String, String> = emptyMap()
        var extractedLabels: List<String> = emptyList()
        for (card in page.items.take(3)) {
            val servers = DutamovieSource.servers(card.url)
            if (servers.isEmpty()) continue
            checkedServers = true
            // The picker shows the site's OWN tab labels, whatever they are — DutaMovie currently
            // serves a single "MULTI SERVER" tab rather than the old "Server 1..N" set, so pinning
            // those exact strings tested the site's copywriting, not the scraper. What must hold is
            // that a label is present and is not the embed host leaking through (which is what made
            // the picker look random before).
            assertTrue(servers.all { it.name.isNotBlank() }, "Every tab needs a label (${card.title})")
            assertTrue(
                servers.all { it.embedUrl.startsWith("http") },
                "Tab embeds must be absolute (${card.title})",
            )
            assertTrue(
                servers.none { s ->
                    runCatching { java.net.URI(s.embedUrl).host.orEmpty() }.getOrDefault("")
                        .let { it.isNotEmpty() && it.equals(s.name.trim(), ignoreCase = true) }
                },
                "Tab labels must come from the site, not the host behind them (${servers.map { it.name }})",
            )
            assertTrue(
                servers.none { it.name.startsWith("Download", ignoreCase = true) },
                "Download links must stay out of the Source picker (${card.title})",
            )
            val vidStack = servers.firstOrNull {
                "upns.live" in it.embedUrl || "embed4me.vip" in it.embedUrl ||
                    "playerp2p.online" in it.embedUrl || "4meplayer.com" in it.embedUrl ||
                    "p2pplay.pro" in it.embedUrl
            } ?: continue
            val extracted = StreamExtractor.extract(vidStack, card.url)
            val hls = extracted.variants.firstOrNull()?.url
            if (hls?.contains(".m3u8") == true) {
                extractedHls = hls
                extractedHeaders = extracted.headers
                extractedLabels = extracted.variants.map { it.label }
                break
            }
        }
        assertTrue(checkedServers, "At least one catalog film should expose player tabs")

        // The HLS leg is CONDITIONAL on the site still serving a mirror we crack natively. DutaMovie
        // currently fronts everything with playsobat.xyz, a CryptoJS + anti-devtool player in the same
        // class as Hydrax/Abyss — deliberately left to the WebView sniffer rather than chased with a
        // static extractor. Demanding a direct HLS here would fail on a site that works fine in the
        // app. When a supported mirror IS present, it must still decrypt — that is the regression this
        // guards.
        if (extractedHls != null) {
            assertTrue(
                extractedLabels.any { it.matches(Regex("\\d{3,4}p")) },
                "An HLS quality exposed by a DutaMovie server must become a resolution variant, got $extractedLabels",
            )
            val playlist = LiveClient.requestText(
                url = extractedHls,
                headers = extractedHeaders,
                ready = { it.trimStart().startsWith("#EXTM3U") },
            )
            assertTrue(playlist?.trimStart()?.startsWith("#EXTM3U") == true, "Resolved HLS must be playable")
        }
    }
}
