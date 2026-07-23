package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertEquals

class NgefilmSourceTest {
    @Test fun `player parser ignores ad iframes`() {
        val html = """
            <iframe src="https://ads.example/banner"></iframe>
            <div class="gmr-embed-responsive"><iframe src="about:blank" data-litespeed-src="https://video.example/embed/1"></iframe></div>
        """.trimIndent()
        assertEquals(
            "https://video.example/embed/1",
            NgefilmSource.parseServer(html, "https://new38.ngefilm.site/eps/show-episode-1/", "Server 1")?.embedUrl,
        )
    }

    @Test fun `muvipro episode links become episodes`() {
        val html = """
            <h1 class="entry-title">Dream to You (2026)</h1>
            <div class="gmr-listseries">
              <a href="/eps/dream-to-you-episode-1/">Eps1</a>
              <a href="/eps/dream-to-you-episode-2/">Eps2</a>
            </div>
        """.trimIndent()
        assertEquals(
            listOf(1, 2),
            LiveParser.parseDetail(html, "https://new38.ngefilm.site/tv/dream-to-you-2026/").episodes.map { it.num },
        )
    }
}
