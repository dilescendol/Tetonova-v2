package com.tetonova.app.data

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class MatureAgeTest {
    private val today = LocalDate.of(2026, 7, 28)

    @Test
    fun exactlyEighteenIsValid() {
        assertEquals(MatureAgeResult.VALID, validateMatureBirthDate("28/07/2008", today))
    }

    @Test
    fun oneDayUnderEighteenIsRejected() {
        assertEquals(MatureAgeResult.UNDERAGE, validateMatureBirthDate("29/07/2008", today))
    }

    @Test
    fun invalidAndNonLeapDatesAreRejected() {
        assertEquals(MatureAgeResult.INVALID, validateMatureBirthDate("31/02/2000", today))
        assertEquals(MatureAgeResult.INVALID, validateMatureBirthDate("29/02/2001", today))
        assertEquals(MatureAgeResult.VALID, validateMatureBirthDate("29/02/2000", today))
    }

    @Test
    fun implausiblyOldDateIsRejected() {
        assertEquals(MatureAgeResult.INVALID, validateMatureBirthDate("27/07/1906", today))
    }

    @Test
    fun inputFormattingAddsDateSeparators() {
        assertEquals("28/07/2000", formatMatureBirthDateInput("28072000"))
        assertEquals("28/07/2000", formatMatureBirthDateInput("28-07-2000"))
        assertEquals("28/0", formatMatureBirthDateInput("280"))
    }
}
