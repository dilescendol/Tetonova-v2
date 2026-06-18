package com.tetonova.app.data

import android.content.Context
import android.webkit.CookieManager
import com.tetonova.core.scraper.ChallengeSolver
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Android wiring for the pure-JVM [com.tetonova.core.scraper.LiveClient] (set in [TnData.init]).
 *
 * [WebViewCookieJar] bridges OkHttp's cookie store to Android's shared [CookieManager], so clearance
 * cookies set by [WebViewGate] (Turnstile / verify_human) are reused by plain OkHttp requests — fast
 * subsequent fetches and queries the challenge redirect would otherwise strip.
 */
object WebViewCookieJar : CookieJar {
    private val cm get() = runCatching { CookieManager.getInstance() }.getOrNull()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val mgr = cm ?: return
        cookies.forEach { mgr.setCookie(url.toString(), it.toString()) }
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cm?.getCookie(url.toString()).orEmpty()
        if (header.isBlank()) return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

/** Last-resort [ChallengeSolver]: runs the headless [WebViewGate] clearance in a real WebView. */
class WebViewChallengeSolver(private val context: Context) : ChallengeSolver {
    override suspend fun solve(url: String, isCleared: (String) -> Boolean): String? =
        WebViewGate.clear(context, url, isCleared)
}
