package com.tetonova.core.scraper

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdlixSourceTest {

    @Test
    fun recognisesApiAndInternalWatchUrls() {
        assertTrue(IdlixSource.isIdlix("https://z2.idlixku.com/api/movies?page=1"))
        assertTrue(IdlixSource.isIdlix("https://z2.idlixku.com/tn-watch/episode/abc123"))
    }

    @Test
    fun sourcePlaybackHeadersSurviveDirectHlsExtraction() = runBlocking {
        val headers = mapOf(
            "Referer" to "https://z2.idlixku.com/",
            "Origin" to "https://z2.idlixku.com",
            "Cookie" to "session=abc",
        )
        val result = StreamExtractor.extract(
            VideoServer(
                name = "Idlix",
                embedUrl = "https://media.example/video/master.m3u8?token=short-lived",
                headers = headers,
            ),
            referer = "https://z2.idlixku.com/",
        )

        assertEquals(1, result.variants.size)
        assertEquals(headers, result.headers)
    }

    @Test
    fun signedMajorplayJsonIsTreatedAsDisguisedHls() = runBlocking {
        val url = "https://e2e.majorplay.net/v/a/video/config-123456.json?t=signed"
        val result = StreamExtractor.extract(VideoServer("Idlix", url), "https://z2.idlixku.com/")

        assertEquals(url, result.variants.single().url)
    }
}
