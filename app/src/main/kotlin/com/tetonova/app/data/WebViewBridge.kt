package com.tetonova.app.data

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.tetonova.core.scraper.ChallengeSolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Android wiring for the pure-JVM [com.tetonova.core.scraper.LiveClient] (set in [TnData.init]).
 *
 * [WebViewCookieJar] bridges OkHttp's cookie store to Android's shared [CookieManager], so clearance
 * cookies set by [WebViewGate] (Turnstile / verify_human) are reused by plain OkHttp requests — fast
 * subsequent fetches and queries the challenge redirect would otherwise strip.
 */
object WebViewCookieJar : CookieJar {
    @Volatile private var disabled = false
    @Volatile private var webViewCookiesEnabled = false
    private val memory = ConcurrentHashMap<String, List<Cookie>>()

    /** Called only after a real WebView has already been created for a challenge. */
    fun enableWebViewCookies() {
        webViewCookiesEnabled = true
    }

    private fun cm(): CookieManager? {
        // CookieManager.getInstance() boots Chromium. Ordinary Home HTTP must stay lightweight.
        if (disabled || !webViewCookiesEnabled) return null
        return runCatching { CookieManager.getInstance() }
            .onFailure {
                disabled = true
                Log.w("TnCookieJar", "WebView CookieManager unavailable; continuing without shared cookies", it)
            }
            .getOrNull()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        memory.compute(url.host) { _, old ->
            (old.orEmpty().filter { saved ->
                saved.expiresAt > now && cookies.none {
                    it.name == saved.name && it.domain == saved.domain && it.path == saved.path
                }
            } + cookies.filter { it.expiresAt > now })
        }
        cm()?.let { mgr ->
            cookies.forEach { cookie ->
                runCatching { mgr.setCookie(url.toString(), cookie.toString()) }
                    .onFailure { disabled = true }
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val local = memory.values.asSequence().flatten().filter { it.expiresAt > now && it.matches(url) }.toList()
        val header = cm()?.let { mgr ->
            runCatching { mgr.getCookie(url.toString()) }
                .onFailure { disabled = true }
                .getOrNull()
                .orEmpty()
        }.orEmpty()
        if (header.isBlank()) return local
        val shared = header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        return (local + shared).distinctBy { Triple(it.name, it.domain, it.path) }
    }
}

/** Last-resort [ChallengeSolver]: runs the headless [WebViewGate] clearance in a real WebView. */
class WebViewChallengeSolver(private val context: Context) : ChallengeSolver {
    override suspend fun solve(url: String, isCleared: (String) -> Boolean): String? =
        WebViewGate.clear(context, url, isCleared)
}
