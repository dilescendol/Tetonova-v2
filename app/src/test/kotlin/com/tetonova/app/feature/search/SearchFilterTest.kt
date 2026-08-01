package com.tetonova.app.feature.search

import com.tetonova.core.model.PosterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFilterTest {
    @Test fun `dedup type and year filters share normalized metadata`() {
        assertEquals(searchKey("Desire (2026)"), searchKey("Desire"))
        assertTrue(matchesType("Drama", "Series"))
        assertEquals("2026", posterYear(PosterItem("Desire (2026)", "Mexico", 0)))
    }
}
