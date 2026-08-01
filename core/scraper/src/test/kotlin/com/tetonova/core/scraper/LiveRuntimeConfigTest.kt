package com.tetonova.core.scraper

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveRuntimeConfigTest {
    @AfterTest
    fun clear() = LiveRuntimeConfig.setPremiumProxy("", "")

    @Test
    fun routesOnlyGoodBosThroughPanel() {
        LiveRuntimeConfig.setPremiumProxy("https://panel.example/", "firebase-token")

        val routed = LiveRuntimeConfig.proxiedRequestFor(
            "https://dramabite.goodbos.online/episodes/7?code=server&lang=id",
        )
        assertEquals("firebase-token", routed?.bearer)
        assertTrue(routed?.url?.startsWith("https://panel.example/api/v1/me/premium/fetch?url=") == true)
        assertNull(LiveRuntimeConfig.proxiedRequestFor("https://example.com/api/list"))
    }
}
