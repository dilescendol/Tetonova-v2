package com.tetonova.app.data

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bridges OkHttp's cookie store to Android's shared [CookieManager], so clearance cookies set by
 * [WebViewGate] (Turnstile / verify_human) are reused by plain OkHttp requests — fast subsequent
 * fetches and queries that the challenge redirect would otherwise strip.
 */
private object WebViewCookieJar : CookieJar {
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

/**
 * Fetches raw HTML from an upstream source site, transparently handling Cloudflare / anti-bot
 * shields. Strategy, cheapest first:
 *   1. direct OkHttp GET with a desktop-browser UA (works for un-gated sites like anichin.moe);
 *   2. FlareSolverr `/v1` (`request.get`) for plain IUAM challenges;
 *   3. byparr `/v1cf` (derived from the `/v1` URL) for managed-challenge / Turnstile sites.
 * If every path still looks like a challenge page we return the best body we got (callers parse
 * defensively and fall back to the bundled seed), so the app never hard-fails.
 *
 * The FlareSolverr endpoint + bearer token come from the panel's `proxyBypass` block; until the
 * panel is fetched they stay blank and only the direct path is attempted.
 */
object LiveClient {

    @Volatile var flareEndpoint: String = ""
    @Volatile var flareToken: String = ""
    /** Application context for the WebView clearance fallback (set in [TnData.init]). */
    @Volatile var appContext: Context? = null

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val direct = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(WebViewCookieJar) // reuse WebView clearance cookies
        .build()

    private val solver = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS) // a Turnstile solve can take 60-90s
        .build()

    /** Best-effort HTML for [url]. Returns null only when nothing at all came back. */
    suspend fun getHtml(url: String): String? = withContext(Dispatchers.IO) {
        val d = runCatching { fetchDirect(url) }.getOrNull()
        if (d != null && !isChallenge(d)) return@withContext d

        // A "verify_human" interstitial (oppadrama) must clear in the WebView — which persists a
        // reusable cookie — NOT via FlareSolverr: flare clears it but strips the ?order=update query
        // and shares no cookie. Turnstile / IUAM sites still try flare first (cheaper).
        val cookieClearance = d != null && ("verify_human" in d || "Verifying your browser" in d)

        if (!cookieClearance && flareEndpoint.isNotBlank()) {
            solveVia(flareEndpoint, url)?.let { if (!isChallenge(it)) return@withContext it }
            cfEndpoint()?.let { cf -> solveVia(cf, url)?.let { if (!isChallenge(it)) return@withContext it } }
        }
        // A real in-app WebView runs the challenge JS (Turnstile / verify_human) and persists the
        // clearance cookie; we then re-fetch directly so a query the challenge stripped is honoured.
        appContext?.let { ctx ->
            val cleared = WebViewGate.clear(ctx, url) { html -> !isChallenge(html) }
            if (cleared != null && !isChallenge(cleared)) {
                runCatching { fetchDirect(url) }.getOrNull()?.let { if (!isChallenge(it)) return@withContext it }
                return@withContext cleared
            }
        }
        d // give callers whatever we have; they parse defensively
    }

    private fun fetchDirect(url: String): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .get().build()
        direct.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return body.ifBlank { null }
        }
    }

    /** POST a FlareSolverr `request.get` and return the solved HTML, or null. */
    private fun solveVia(endpoint: String, url: String): String? = runCatching {
        val payload = JSONObject()
            .put("cmd", "request.get")
            .put("url", url)
            .put("maxTimeout", 60000)
            .toString()
        val req = Request.Builder().url(endpoint)
            .header("Content-Type", "application/json")
            .apply { if (flareToken.isNotBlank()) header("Authorization", "Bearer $flareToken") }
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        solver.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@runCatching null
            val sol = JSONObject(body).optJSONObject("solution") ?: return@runCatching null
            sol.optString("response").ifBlank { null }
        }
    }.getOrNull()

    /** Derive the byparr `/v1cf` sibling from the configured `/v1` FlareSolverr URL. */
    private fun cfEndpoint(): String? {
        val e = flareEndpoint
        return Regex("/v1[a-z]*(/?)(\\?|$)").replace(e, "/v1cf$1$2").takeIf { it != e }
    }

    /** Heuristic: does this body look like an interstitial rather than real content? */
    private fun isChallenge(html: String): Boolean {
        if (html.length < 600) return true
        val h = html.take(4000).lowercase()
        return "just a moment" in h ||
            "challenges.cloudflare.com" in h ||
            "cf-browser-verification" in h ||
            "_cf_chl_opt" in h ||
            ("<title>loading" in h && "turnstile" in h) ||
            "checking your browser" in h
    }
}
