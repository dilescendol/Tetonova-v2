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
}
