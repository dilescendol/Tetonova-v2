package com.tetonova.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Headless in-app clearance for anti-bot shields that FlareSolverr can't crack — notably AnimeSail,
 * which layers a real Cloudflare Turnstile over a custom IP-geo + cookie JS flow. We load the page
 * in a genuine Chromium [WebView] so the challenge JS actually runs (Turnstile auto-verifies for a
 * real browser, the geo cookie gets set, the page reloads to the real content), then sample the DOM
 * until it no longer looks like a challenge. The clearance cookies persist in the shared
 * [CookieManager], so this only pays the WebView cost on the first blocked fetch per session.
 *
 * Must touch the WebView on the main thread (Android requirement); the suspend wrapper hops there.
 */
object WebViewGate {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * Only tolerate a bad cert on bare-IP HTTPS mirrors (e.g. AnimeSail = 154.26.137.28) which serve
     * a hostname-mismatched cert by design. Every named host must present a valid chain — a blanket
     * proceed() is a MITM hole (CWE-295) and a Play-policy rejection. ponytail: IP-literal allowlist
     * is the whole rule; tighten to specific mirror IPs if abuse shows up.
     */
    private fun handleSslError(view: WebView, h: SslErrorHandler, e: SslError) {
        val host = android.net.Uri.parse(e.url ?: view.url ?: "").host.orEmpty()
        if (host.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$"""))) h.proceed() else h.cancel()
    }

    /** Returns cleared HTML once [looksCleared] passes, or null on timeout/failure. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun clear(context: Context, url: String, looksCleared: (String) -> Boolean): String? =
        withTimeoutOrNull(45_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    val wv = WebView(context)
                    WebViewCookieJar.enableWebViewCookies()
                    var settled = false

                    fun finish(result: String?) {
                        if (settled) return
                        settled = true
                        handler.removeCallbacksAndMessages(null)
                        // Persist clearance cookies so the OkHttp re-fetch (shared CookieManager) sees them.
                        runCatching { CookieManager.getInstance().flush() }
                        runCatching { wv.stopLoading(); wv.destroy() }
                        if (cont.isActive) cont.resume(result)
                    }

                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.userAgentString = UA
                    wv.settings.databaseEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.webViewClient = object : WebViewClient() {
                        // Bare-IP HTTPS mirrors (AnimeSail = 154.26.137.28) serve a mismatched cert;
                        // proceed anyway — these are public content sites, no app secrets in flight.
                        override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: SslError) = handleSslError(view, h, e)
                    }

                    // Poll the live DOM; the challenge clears itself (Turnstile success -> reload).
                    val poll = object : Runnable {
                        var tries = 0
                        override fun run() {
                            if (settled) return
                            wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                val html = decodeJsString(raw)
                                if (html.length > 1500 && looksCleared(html)) finish(html)
                            }
                            if (++tries < 18) handler.postDelayed(this, 2500) else finish(null)
                        }
                    }

                    cont.invokeOnCancellation { handler.post { finish(null) } }
                    wv.loadUrl(url)
                    handler.postDelayed(poll, 3000)
                }
            }
        }

    /**
     * 2b: enumerate kuramanime's *other* servers by driving the page's `#changeServer` <select> in a
     * real WebView — the switch handler lives in external JS + runs a per-server token flow, so we let
     * the page's own code load each embed, then capture the resulting iframe/source URL. Returns
     * (displayName → embedUrl) for each non-default, non-MEGA server we could capture. Kuramadrive (the
     * default direct mp4) is handled separately by [com.tetonova.core.scraper.KuramanimeSource.servers].
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun kuramanimeServers(context: Context, url: String, stopAfterFirst: Boolean = false): List<Pair<String, String>> =
        withTimeoutOrNull(90_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    val wv = WebView(context)
                    WebViewCookieJar.enableWebViewCookies()
                    var settled = false
                    val out = LinkedHashMap<String, String>()
                    var lastEmbed = ""
                    // server <option value> → display name (skip kuramadrive=default mp4, mega=no extractor).
                    val targets = linkedMapOf(
                        "doodstream" to "DoodStream", "filemoon" to "FileMoon",
                        "rpmshare" to "RPMShare", "streamp2p" to "StreamP2P",
                    )
                    val queue = targets.keys.toMutableList()
                    // Capture the player's current embed: a direct <source>, else the first non-widget iframe.
                    val captureJs = "(function(){var v=document.querySelector('video source');if(v&&v.src)return v.src;" +
                        "var f=document.querySelectorAll('iframe');for(var i=0;i<f.length;i++){var s=f[i].src||'';" +
                        "if(s.indexOf('http')==0&&s.indexOf('kuramachat')<0&&s.indexOf('disqus')<0&&s.indexOf('google')<0&&s.indexOf('recaptcha')<0)return s;}return'';})()"

                    fun finish() {
                        if (settled) return
                        settled = true
                        handler.removeCallbacksAndMessages(null)
                        runCatching { CookieManager.getInstance().flush() }
                        runCatching { wv.stopLoading(); wv.destroy() }
                        if (cont.isActive) cont.resume(out.toList())
                    }

                    lateinit var processNext: () -> Unit
                    fun pollEmbed(name: String) {
                        val poll = object : Runnable {
                            var tries = 0
                            override fun run() {
                                if (settled) return
                                wv.evaluateJavascript(captureJs) { raw ->
                                    val embed = decodeJsString(raw)
                                    if (embed.startsWith("http") && embed != lastEmbed) {
                                        out[name] = embed; lastEmbed = embed
                                        if (stopAfterFirst) finish() else processNext()
                                    } else if (++tries < 8) handler.postDelayed(this, 1200) else processNext()
                                }
                            }
                        }
                        handler.postDelayed(poll, 1800) // let the switch handler swap the embed first
                    }
                    processNext = {
                        if (queue.isEmpty()) finish()
                        else {
                            val v = queue.removeAt(0)
                            wv.evaluateJavascript(
                                "(function(){var s=document.querySelector('#changeServer');if(!s)return'no';" +
                                    "s.value='$v';s.dispatchEvent(new Event('change',{bubbles:true}));return'ok';})()",
                            ) {}
                            pollEmbed(targets[v] ?: v)
                        }
                    }
                    // Wait until #changeServer exists (page loaded + CF cleared) before driving it.
                    val waitReady = object : Runnable {
                        var tries = 0
                        override fun run() {
                            if (settled) return
                            wv.evaluateJavascript("document.querySelector('#changeServer')?1:0") { r ->
                                if (r == "1") processNext()
                                else if (++tries < 20) handler.postDelayed(this, 1500) else finish()
                            }
                        }
                    }

                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    wv.settings.databaseEnabled = true
                    wv.settings.userAgentString = UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: SslError) = handleSslError(view, h, e)
                    }
                    cont.invokeOnCancellation { handler.post { finish() } }
                    wv.loadUrl(url)
                    handler.postDelayed(waitReady, 3000)
                }
            }
        }.orEmpty()

    /** `evaluateJavascript` hands back a JSON-encoded string ("<…"); decode it. */
    private fun decodeJsString(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0, "") }.getOrDefault("")
    }
}
