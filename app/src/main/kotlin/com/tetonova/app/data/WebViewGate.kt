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

    /** Returns cleared HTML once [looksCleared] passes, or null on timeout/failure. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun clear(context: Context, url: String, looksCleared: (String) -> Boolean): String? =
        withTimeoutOrNull(45_000) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    val wv = WebView(context)
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
                        override fun onReceivedSslError(view: WebView, h: SslErrorHandler, e: SslError) = h.proceed()
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

    /** `evaluateJavascript` hands back a JSON-encoded string ("<…"); decode it. */
    private fun decodeJsString(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0, "") }.getOrDefault("")
    }
}
