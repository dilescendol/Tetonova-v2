package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchMatchTest {
    @Test
    fun matchesIndonesianWordFormsWithoutAcceptingUnrelatedRecommendations() {
        assertTrue(LiveSource.matchesSearchQuery("Mengungkap Pangkatku", "pengungkapan pangkatku"))
        assertFalse(LiveSource.matchesSearchQuery("Menyamar Jadi Pria, Dia Tetap Memikat", "slime season 4"))
    }

    @Test
    fun kuramanimeIsNativeSearchAndParsesProductCards() {
        // Native so its server-side hits aren't re-filtered away by the caller's title match.
        assertTrue(LiveSource.hasNativeSearch("https://v19.kuramanime.ing"))

        // The generic parser must read kuramanime's `.product__item` search cards.
        val html = """
            <div class="product__item">
              <div class="product__item__pic"><a href="/properties/type/tv">TV</a></div>
              <div class="product__item__text"><h5><a href="/anime/1/trinity-seven">Trinity Seven</a></h5></div>
            </div>
        """.trimIndent()
        val items = LiveParser.parseList(html, "https://v19.kuramanime.ing/anime?search=trinity+seven")
        assertTrue(items.any { it.title == "Trinity Seven" && it.url.endsWith("/anime/1/trinity-seven") })
    }

    @Test
    fun nontonAnimeIdSearchCardsAreParsed() {
        // NontonAnimeID's `/?s=` page uses the `as-` theme: the card IS the anchor, title in h3.
        val html = """
            <div class="as-anime-grid">
              <a href="https://s13.nontonanimeid.boats/anime/tensei-shitara-slime-datta-ken/" class="as-anime-card">
                <div class="as-card-thumbnail"><img src="https://x/cover1.jpg" alt="Tensei shitara Slime Datta Ken"></div>
                <div class="as-card-content"><h3 class="as-anime-title">Tensei shitara Slime Datta Ken</h3></div>
              </a>
              <a href="https://s13.nontonanimeid.boats/anime/tensei-shitara-slime-datta-ken-3rd-season/" class="as-anime-card">
                <div class="as-card-thumbnail"><img src="https://x/cover2.jpg" alt="Tensei shitara Slime Datta Ken 3rd Season"></div>
                <div class="as-card-content"><h3 class="as-anime-title">Tensei shitara Slime Datta Ken 3rd Season</h3></div>
              </a>
            </div>
        """.trimIndent()
        val items = LiveParser.parseList(html, "https://s13.nontonanimeid.boats/?s=slime")
        assertTrue(items.any { it.title == "Tensei shitara Slime Datta Ken" && it.url.endsWith("/anime/tensei-shitara-slime-datta-ken/") })
        assertTrue(items.size >= 2)
    }
}
