package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [looksGone] decides whether the player skips the WebView sniff, so it cuts both ways: missing a real
 * "file deleted" page costs ~12s of dead spinner per mirror, while a false positive drops a server that
 * the sniff could have played. Bodies below are the actual responses captured 2026-07-31.
 */
class GoneMarkerTest {

    /** playmogo.com (the host d0o0d.com now redirects to) for a deleted file. */
    private val doodDeleted = """
        <html lang="en"><head> <meta charset="utf-8"> <title>Video not found | DoodStream</title>
        <link rel="preconnect" href="//i.doodcdn.io"></head><body>
        <div class="text-center"> <img src="//i.doodcdn.io/img/no_video_3.svg">
        <h1>Not Found</h1> <p>video you are looking for is not found.</p> </div></body></html>
    """.trimIndent()

    /** stmruby.com for an expired file. */
    private val streamRubyExpired = """
        <html><body style="width:100%;height:100%;padding:0;margin:0;">
        <center><div style="position: absolute;top:50%;">File is no longer available as it expired or has been deleted.</div></center>
        <img src="/images2/player_blank.jpg" id="over"></body></html>
    """.trimIndent()

    @Test
    fun deletedDoodPageIsGone() = assertTrue(looksGone(doodDeleted))

    @Test
    fun expiredStreamRubyPageIsGone() = assertTrue(looksGone(streamRubyExpired))

    /**
     * The common case: a host we cannot parse statically but whose iframe plays fine. These MUST fall
     * through to the sniff, so nothing here may read as gone.
     */
    @Test
    fun unparseablePlayerPageIsNotGone() {
        val jwPlayer = """
            <html><head><title>Player</title></head><body>
            <div id="player"></div>
            <script>jwplayer("player").setup({sources:[{file:"/api/stream?id=abc"}],autostart:true});</script>
            </body></html>
        """.trimIndent()
        assertFalse(looksGone(jwPlayer))
    }

    /** A page whose only "not found" is an unrelated 404 handler must not kill the server. */
    @Test
    fun incidentalNotFoundWordingIsNotGone() {
        val incidental = """
            <html><body><script>
            window.onerror = function(){ console.log("asset not found, retrying"); };
            var player = new Clappr.Player({source: "https://cdn.example/hls/master.m3u8"});
            </script></body></html>
        """.trimIndent()
        assertFalse(looksGone(incidental))
    }
}
