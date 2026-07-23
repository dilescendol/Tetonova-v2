package com.tetonova.app.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class TurboVipPngTsTest {
    @Test
    fun findsThreeConsecutiveTsPacketsAfterPngCover() {
        val payloadAt = 941
        val data = ByteArray(payloadAt + 188 * 3)
        repeat(3) { data[payloadAt + it * 188] = 0x47 }

        assertEquals(payloadAt, findTsPayloadOffset(data))
        data[payloadAt + 188 * 2] = 0
        assertEquals(-1, findTsPayloadOffset(data))
    }
}
