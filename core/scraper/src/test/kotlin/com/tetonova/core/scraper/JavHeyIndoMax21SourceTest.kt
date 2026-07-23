package com.tetonova.core.scraper

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class JavHeyIndoMax21SourceTest {
    @Test
    fun recognisesCurrentAndRotatingDomains() {
        assertTrue(JavHeySource.isJavHey("https://javhey.com/videos/paling-baru"))
        assertTrue(IndoMax21Source.isIndoMax21("https://homecookingrocks.com/category/anime/"))
        assertTrue(IndoMax21Source.isIndoMax21("https://otrarevista.com/category/anime/"))
    }

    @Test
    fun currentIndoMaxPyroxServerResolvesNatively() = runBlocking {
        val referer = "https://otrarevista.com/eps/love-unseen-beneath-the-clear-night-sky-season-1-episode-1/"
        val server = VideoServer("Server 1", "https://embedpyrox.xyz/video/4d73845b1f67ab7a146a5210953a4358")
        val result = StreamExtractor.extract(server, referer)
        assertTrue(result.variants.isNotEmpty(), "Pyrox should resolve to a playable stream")
        assertTrue(result.variants.all { it.url.startsWith("http") })
    }

    @Test
    fun currentJavHeySeekplaysServerUsesCertificateSafeNativeRoute() = runBlocking {
        val server = VideoServer("Server 3", "https://p1.seekplays.pro/#hx8sy")
        val result = StreamExtractor.extract(server, JavHeySource.DEFAULT_BASE)
        assertTrue(result.variants.isNotEmpty(), "Seekplays should resolve for the native player")
        assertTrue(result.variants.all { it.url.startsWith("http") })
        assertTrue(
            result.variants.none { Regex("^https://(?:\\d{1,3}\\.){3}\\d{1,3}/").containsMatchIn(it.url) },
            "A raw-IP HTTPS route has an invalid certificate on Android",
        )
    }

}
