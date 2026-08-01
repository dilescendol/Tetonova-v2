package com.tetonova.app.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchPolicyTest {
    @Test
    fun trustsServerSideSearchButFiltersGenericWordPress() {
        // Premium proxy (DramaBos): always server-side search, trusted regardless of base URL.
        assertTrue(trustSearchResults(premium = true, apiBaseUrl = "https://anything.example"))
        // Native JSON search APIs are trusted so localized titles survive.
        assertTrue(trustSearchResults(premium = false, apiBaseUrl = "https://dramabox.goodbos.online"))
        assertTrue(trustSearchResults(premium = false, apiBaseUrl = "https://oploverz.gold"))
        // Generic WordPress `/?s=` sources may echo their homepage — their hits get title-filtered.
        assertFalse(trustSearchResults(premium = false, apiBaseUrl = "https://otakudesu.best"))
    }
}
