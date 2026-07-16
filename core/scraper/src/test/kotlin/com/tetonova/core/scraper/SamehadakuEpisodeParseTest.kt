package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression: a samehadaku MOVIE detail page must yield exactly one episode — nav
 *  ("Movie Terbaru"), batch, and related /anime/ links used to leak in as duplicate "Movie" rows. */
class SamehadakuEpisodeParseTest {
    private val html = """
        <html><body>
        <nav>
          <a href="https://v2.samehadaku.how/movie-terbaru/">Movie Terbaru</a>
          <a href="https://v2.samehadaku.how/anime-terbaru/">Anime Terbaru</a>
          <a href="https://v2.samehadaku.how/daftar-anime-2/">Daftar Anime</a>
        </nav>
        <div class="lstepsiode listeps">
          <ul><li>
            <span class="eps"><a href="https://v2.samehadaku.how/kimetsu-no-yaiba-the-movie-infinity-castle-part-1/">1</a></span>
            <span class="lchx"><a href="https://v2.samehadaku.how/kimetsu-no-yaiba-the-movie-infinity-castle-part-1/">Kimetsu no Yaiba The Movie – Infinity Castle Part 1</a></span>
          </li></ul>
        </div>
        <div class="widget">
          <a href="https://v2.samehadaku.how/batch/kimetsu-no-yaiba-movie-batch/">Download Batch Movie</a>
          <a href="https://v2.samehadaku.how/one-piece-gyojin-tou-hen-batch-episode-1-21/">Download Batch Anime One Piece</a>
          <a href="https://v2.samehadaku.how/anime/kimetsu-no-yaiba-the-movie-mugen-train/">Related Movie</a>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun moviePageYieldsSingleEpisode() {
        val eps = LiveParser.parseDetail(html, "https://v2.samehadaku.how/anime/kimetsu-no-yaiba-the-movie-infinity-castle-part-1-akaza-returns/").episodes
        assertEquals(1, eps.size, "expected exactly 1 episode, got: ${eps.map { it.title to it.url }}")
        assertEquals("https://v2.samehadaku.how/kimetsu-no-yaiba-the-movie-infinity-castle-part-1/", eps[0].url)
    }
}
