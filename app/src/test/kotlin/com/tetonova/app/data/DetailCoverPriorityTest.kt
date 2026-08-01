package com.tetonova.app.data

import com.tetonova.core.scraper.LiveParser
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailCoverPriorityTest {
    /** anixverse regression: the header logo carries itemprop=image and precedes the real poster in
     *  the DOM — the poster inside `.thumb` must still win. */
    @Test
    fun posterBeatsHeaderLogoWithItempropImage() {
        val html = """
            <html><body>
              <header><img itemprop="image" src="https://site.example/wp-content/uploads/logo.png"></header>
              <div class="bigcontent"><div class="thumbook">
                <div class="thumb" itemprop="image">
                  <img src="https://site.example/wp-content/uploads/real-poster.jpg" class="ts-post-image">
                </div>
              </div></div>
            </body></html>
        """.trimIndent()
        val detail = LiveParser.parseDetail(html, "https://site.example/anime/some-title/")
        assertEquals("https://site.example/wp-content/uploads/real-poster.jpg", detail.cover)
    }
}
