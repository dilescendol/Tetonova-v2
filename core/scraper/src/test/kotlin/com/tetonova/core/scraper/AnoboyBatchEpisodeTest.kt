package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnoboyBatchEpisodeTest {
    // Old anoboy "batch/streaming" page: the whole series lives on one page, repeated under each server
    // tab (Blogger "BT-HD NN", acbatch "EP NN" [dead], yourupload "Yup NN"). Episodes point at the page +
    // "#tnep=N" so servers() can offer every tab; servers() then resolves Blogger + yourupload for the ep.
    private val pageUrl = "https://anoboy.xyz/2016/10/trinity-seven-streaming-sub-indo/"
    private val html = """
        <div class="satu">
          <a id="allvideo" href="#" data-video="/uploads/adsbatch720.php?url=TOKEN1">BT-HD 01</a>
          <a id="allvideo" href="#" data-video="/uploads/adsbatch720.php?url=TOKEN2">BT-HD 02</a>
        </div>
        <div class="dua" style="display:none;">
          <a id="allvideo" href="#" data-video="/uploads/acbatch.php?data=X&data5=01">EP 01</a>
          <a id="allvideo" href="#" data-video="/uploads/acbatch.php?data=X&data5=02">EP 02</a>
        </div>
        <div class="tiga" style="display:none;">
          <a id="allvideo" href="#" data-video="/uploads/yupbatch.php?data=A1&data2=B1&data3=C1&data4=D1&data5=01">Yup 01</a>
          <a id="allvideo" href="#" data-video="/uploads/yupbatch.php?data=A2&data2=B2&data3=C2&data4=D2&data5=02">Yup 02</a>
        </div>
    """.trimIndent()

    @Test
    fun batchEpisodesPointAtPageWithEpisodeMarker() {
        val eps = LiveParser.parseDetail(html, pageUrl).episodes
        assertEquals(listOf(1, 2), eps.map { it.num })
        assertTrue(eps.first().url.endsWith("#tnep=1"), "got ${eps.first().url}")
        assertTrue(eps.all { "anoboy.xyz" in it.url && "#tnep=" in it.url })
    }

    @Test
    fun batchServersOfferBloggerAndYourupload_notDeadAcbatch() {
        val servers = LiveParser.anoboyBatchServers(html, pageUrl, 1)
        assertEquals(2, servers.size, "expected Blogger + Yup only, got ${servers.map { it.name }}")
        assertTrue(servers.any { it.name.equals("Blogger", true) })

        val yup = servers.firstOrNull { "yup" in it.name.lowercase() }
        assertNotNull(yup, "yourupload server missing")
        assertTrue(
            yup!!.variants.map { it.label }.containsAll(listOf("720p", "480p", "360p", "240p")),
            "yup variants ${yup.variants.map { it.label }}",
        )
        assertTrue(yup.variants.all { "yourupload.com/embed/" in it.embedUrl })
        assertEquals("720p", yup.variants.first().label) // defaults to highest
        assertTrue(servers.none { s -> s.variants.any { "acbatch" in it.embedUrl } }, "dead acbatch tab leaked")
    }

    // New-format episode page (ongoing titles): only a Blogger (Btube) streaming embed, but the Download
    // section lists mp4upload per resolution — surface it as "M4U" (our ExoPlayer) and prefer it.
    @Test
    fun newFormatSurfacesMp4uploadPreferredOverBlogger() {
        val page = """
            <div class="vmiror">Btube |
              <a data-video="/uploads/adsbatch720.php?url=TOK">720</a>
            </div>
            <div class="download"><div id="colomb"><p>
              <span class="ud"><span class="udj">Gofile</span>
                <a class="udl" href="https://gofile.io/d/v8ij5F">240P</a></span>
              <span class="ud"><span class="udj">M4U</span>
                <a class="udl" href="https://www.mp4upload.com/aa1">240P</a> |
                <a class="udl" href="https://www.mp4upload.com/bb2">SD360P</a> |
                <a class="udl" href="https://www.mp4upload.com/cc3">480P</a> |
                <a class="udl" href="https://www.mp4upload.com/dd4">720P</a> |
                <a class="udl" href="https://www.mp4upload.com/ee5">1K</a> |
                <a class="udl" href="none">2K</a></span>
            </p></div></div>
        """.trimIndent()
        val servers = LiveParser.parseAnoboyServers(page, "https://anoboy.xyz/2026/07/hell-mode-episode-2/")
        assertEquals("M4U", servers.first().name, "M4U should be preferred, got ${servers.map { it.name }}")
        val m4u = servers.first { it.name == "M4U" }
        assertEquals(listOf("1080p", "720p", "480p", "360p", "240p"), m4u.variants.map { it.label })
        assertTrue(m4u.variants.all { "mp4upload.com/" in it.embedUrl })
        assertTrue(servers.any { it.name.equals("Btube", true) }, "Blogger fallback missing")
    }
}
