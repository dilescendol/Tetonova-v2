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
    }

    @Test
    fun liveCatalogSeriesAndPlayerResolveEndToEnd() = runBlocking {
        val page = DutamovieSource.listPage("${DutamovieSource.DEFAULT_BASE}/box-office/")
        assertTrue(page.items.isNotEmpty(), "DutaMovie catalog should return cards")
        assertTrue(page.items.all { it.cover?.startsWith("http") == true })
        val search = DutamovieSource.search(DutamovieSource.DEFAULT_BASE, "They Fight")
        assertTrue(search.any { "They Fight" in it.title }, "DutaMovie search should return a matching card")

        val series = DutamovieSource.detail("${DutamovieSource.DEFAULT_BASE}/tv/spooky-in-love-2026/")
        assertNotNull(series)
        assertTrue(series.episodes.size >= 2, "Series detail should expose its episode list")

        val watch = "${DutamovieSource.DEFAULT_BASE}/they-fight-2026/"
        val servers = DutamovieSource.servers(watch)
        assertTrue(servers.isNotEmpty(), "DutaMovie player tabs should expose streaming servers")
        assertTrue(servers.map { it.name } == servers.indices.map { "Server ${it + 1}" }, "Sources must retain the website's Server 1..N order")
        assertTrue(servers.none { it.name.startsWith("Download", ignoreCase = true) }, "Download links must stay out of the Source picker")
        val vidStack = servers.firstOrNull {
            "upns.live" in it.embedUrl || "embed4me.vip" in it.embedUrl || "playerp2p.online" in it.embedUrl
        }
        assertNotNull(vidStack, "DutaMovie should expose at least one native VidStack mirror")

        val extracted = StreamExtractor.extract(vidStack, watch)
        val hls = extracted.variants.firstOrNull()?.url
        assertTrue(hls?.contains(".m3u8") == true, "VidStack should decrypt to a direct HLS URL")
        assertTrue(
            extracted.variants.map { it.label }.any { it.matches(Regex("\\d{3,4}p")) },
            "An HLS quality exposed by a DutaMovie server must become a resolution variant",
        )
        val playlist = LiveClient.requestText(
            url = hls,
            headers = extracted.headers,
            ready = { it.trimStart().startsWith("#EXTM3U") },
        )
        assertTrue(playlist?.trimStart()?.startsWith("#EXTM3U") == true, "Resolved HLS must be playable")

        val odysseyUrl = "${DutamovieSource.DEFAULT_BASE}/the-odyssey-2026/"
        val odysseyServers = DutamovieSource.servers(odysseyUrl)
        val fourMeOrP2p = odysseyServers.firstOrNull {
            "4meplayer.com" in it.embedUrl || "p2pplay.pro" in it.embedUrl
        }
        assertNotNull(fourMeOrP2p, "The example page should expose its 4Me/P2P VidStack tab")
        val nativeVidStack = StreamExtractor.extract(fourMeOrP2p, odysseyUrl)
        assertTrue(
            nativeVidStack.variants.isNotEmpty(),
            "The example page's 4Me/P2P tab should resolve natively",
        )
    }
}
