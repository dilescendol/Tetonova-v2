package com.tetonova.core.scraper

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pluggable last-resort challenge solver. The Android app supplies a real-[WebView]-backed impl
 * (Turnstile / `verify_human`, and the Kuramanime player); the server-side scraper leaves it null and
 * relies on flare/byparr. Lives here (pure JVM) so [LiveClient] has no Android dependency.
 */
interface ChallengeSolver {
    /** Load [url] in a real browser until [isCleared] passes; return the cleared HTML, or null. */
    suspend fun solve(url: String, isCleared: (String) -> Boolean): String?
}

/** Default in-memory cookie jar. The app swaps in a WebView/CookieManager-backed jar so clearance
 *  cookies set by [ChallengeSolver] are reused by plain OkHttp requests. */
private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.filter { it.expiresAt > System.currentTimeMillis() } ?: emptyList()
}

/**
 * Fetches raw HTML from an upstream source site, transparently handling Cloudflare / anti-bot
 * shields. Strategy, cheapest first:
 *   1. direct OkHttp GET with a desktop-browser UA (works for un-gated sites like anichin.moe);
 *   2. FlareSolverr `/v1` (`request.get`) for plain IUAM challenges;
 *   3. byparr `/v1cf` (derived from the `/v1` URL) for managed-challenge / Turnstile sites;
 *   4. the pluggable [ChallengeSolver] (a real WebView on the app) for shields flare can't crack.
 * If every path still looks like a challenge page we return the best body we got (callers parse
 * defensively and fall back to the bundled seed), so the app never hard-fails.
 *
 * The FlareSolverr endpoint + bearer token come from the panel's `proxyBypass` block; until they're
 * configured they stay blank and only the direct path (+ any [challengeSolver]) is attempted.
 */
object LiveClient {

    @Volatile var flareEndpoint: String = ""
    @Volatile var flareToken: String = ""
    /** Last-resort solver — app sets a WebView impl; server leaves null. */
    @Volatile var challengeSolver: ChallengeSolver? = null
    /** Cookie jar OkHttp uses — app swaps in a WebView/CookieManager-backed jar to share clearance. */
    @Volatile var cookieJar: CookieJar = InMemoryCookieJar()

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // Delegates to the mutable [cookieJar] so the app can swap the jar in after the client is built.
    private val delegatingJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = cookieJar.saveFromResponse(url, cookies)
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieJar.loadForRequest(url)
    }

    private val direct = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(delegatingJar) // reuse clearance cookies
        .build()

    private val solver = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS) // a Turnstile solve can take 60-90s
        .build()

    /** Best-effort HTML for [url]. Returns null only when nothing at all came back. */
    /**
     * Fetch [url]'s HTML through the bypass tiers (direct → flare `/v1` → byparr `/v1cf` → WebView
     * solver). [ready] decides when a tier's response is good enough to return; it defaults to "not a
     * challenge page". Callers that need a JS-rendered result — e.g. kuramanime's per-resolution
     * `<source>` tags, injected *after* the challenge clears + a token flow runs — pass a stricter
     * predicate so the WebView keeps polling the live DOM until that content is actually present
     * (instead of returning the bare cleared page).
     */
    suspend fun getHtml(url: String, ready: (String) -> Boolean = { !isChallenge(it) }): String? = withContext(Dispatchers.IO) {
        val d = runCatching { fetchDirect(url) }.getOrNull()
        if (d != null && ready(d)) return@withContext d

        // A "verify_human" interstitial (oppadrama) must clear in the solver — which persists a
        // reusable cookie — NOT via FlareSolverr: flare clears it but strips the ?order=update query
        // and shares no cookie. Turnstile / IUAM sites still try flare first (cheaper).
        val cookieClearance = d != null && ("verify_human" in d || "Verifying your browser" in d)

        if (!cookieClearance && flareEndpoint.isNotBlank()) {
            solveVia(flareEndpoint, url)?.let { if (ready(it)) return@withContext it }
            cfEndpoint()?.let { cf -> solveVia(cf, url)?.let { if (ready(it)) return@withContext it } }
        }
        // A real in-app WebView runs the challenge JS (Turnstile / verify_human / kuramadrive) and
        // persists the clearance cookie; we then re-fetch directly so a query the challenge stripped is
        // honoured (skipped when the re-fetch can't satisfy [ready], e.g. JS-injected player sources).
        challengeSolver?.let { cs ->
            val cleared = cs.solve(url, ready)
            if (cleared != null && ready(cleared)) {
                runCatching { fetchDirect(url) }.getOrNull()?.let { if (ready(it)) return@withContext it }
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

    /**
     * POST [form] (application/x-www-form-urlencoded) to [url], bypassing Internet-Positif / anti-bot
     * the same way [getHtml] does: a direct OkHttp POST first, then FlareSolverr `request.post` when
     * the direct path is blocked. admin-ajax JSON replies are far shorter than [isChallenge]'s
     * threshold, so the caller supplies [isValid] to recognise a real response. Returns the body (raw
     * JSON on the direct path; FlareSolverr wraps it in the rendered page on the solver path), or null.
     */
    suspend fun postBypass(url: String, form: String, referer: String? = null, isValid: (String) -> Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching { postDirect(url, form, referer) }.getOrNull()?.let { if (isValid(it)) return@withContext it }
            if (flareEndpoint.isNotBlank()) {
                postViaFlare(flareEndpoint, url, form)?.let { if (isValid(it)) return@withContext it }
                cfEndpoint()?.let { cf -> postViaFlare(cf, url, form)?.let { if (isValid(it)) return@withContext it } }
            }
            null
        }

    private fun postDirect(url: String, form: String, referer: String?): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { referer?.let { header("Referer", it) } }
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        direct.newCall(req).execute().use { resp ->
            return resp.body?.string().orEmpty().ifBlank { null }
        }
    }

    /** FlareSolverr `request.post`: loads [url] with [postData] in the headless browser and returns
     *  the rendered response body (the JSON wrapped in the browser's page view). */
    private fun postViaFlare(endpoint: String, url: String, postData: String): String? = runCatching {
        val payload = JSONObject()
            .put("cmd", "request.post")
            .put("url", url)
            .put("postData", postData)
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
