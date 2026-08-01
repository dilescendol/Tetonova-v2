package com.tetonova.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
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

data class LiveImage(val bytes: ByteArray, val contentType: String)

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
    /**
     * Upstream proxy the FlareSolverr browser routes through, as a full URL
     * (`http://user:pass@gw.dataimpulse.com:823`). **Server-only**: the warmer sets this so geo/hard-CF
     * solves egress from a residential IP (e.g. DataImpulse country=ID for the bare-IP/Turnstile sites
     * datacenter IPs can't reach); the app leaves it blank so proxy credentials never ship to a device
     * (and per-user solves never burn paid residential GB — solves happen once, server-side, then cache).
     * Blank ⇒ no `proxy` field is sent and FlareSolverr uses the host's own IP.
     */
    @Volatile var solverProxy: String = ""
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
        val premiumProxy = LiveRuntimeConfig.proxiedRequestFor(url) != null
        val d = runCatching { fetchDirect(url) }.getOrNull()
        if (d != null && ready(d)) return@withContext d
        if (premiumProxy) return@withContext d

        // A "verify_human" interstitial (oppadrama) must clear in the solver — which persists a
        // reusable cookie — NOT via FlareSolverr: flare clears it but strips the ?order=update query
        // and shares no cookie. Turnstile / IUAM sites still try flare first (cheaper).
        val cookieClearance = d != null && ("verify_human" in d || "Verifying your browser" in d)

        if ((!cookieClearance || challengeSolver == null) && flareEndpoint.isNotBlank()) {
            solveVia(flareEndpoint, url)?.let { if (ready(it)) return@withContext it }
            if (solverProxy.isNotBlank()) solveVia(flareEndpoint, url, useProxy = false)?.let { if (ready(it)) return@withContext it }
            cfEndpoint()?.let { cf ->
                solveVia(cf, url)?.let { if (ready(it)) return@withContext it }
                if (solverProxy.isNotBlank()) solveVia(cf, url, useProxy = false)?.let { if (ready(it)) return@withContext it }
            }
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

    /**
     * Raw API request using the same cookie jar as [getHtml]. This is deliberately separate from
     * [postBypass]: JSON APIs need an application/json body and some playback handshakes require
     * cookies minted by an earlier request to survive across a different redeem host.
     */
    suspend fun requestText(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        ready: (String) -> Boolean = { it.isNotBlank() && !isChallenge(it) },
    ): String? = withContext(Dispatchers.IO) {
        fun attempt(): String? = runCatching {
            val builder = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            headers.forEach { (name, value) -> builder.header(name, value) }
            when (method.uppercase()) {
                "GET" -> builder.get()
                "POST" -> builder.post(body.orEmpty().toRequestBody(contentType.toMediaType()))
                else -> builder.method(method.uppercase(), body?.toRequestBody(contentType.toMediaType()))
            }
            direct.newCall(builder.build()).execute().use { response ->
                response.body?.string().orEmpty().ifBlank { null }
            }
        }.getOrNull()

        attempt()?.let { if (ready(it)) return@withContext it }

        // Let the normal challenge stack establish clearance, then replay the exact API request.
        // GET may itself be the JSON endpoint; POST solves the same-site referer/root first.
        if (method.equals("GET", ignoreCase = true)) {
            getHtml(url, ready)?.let { if (ready(it)) return@withContext it }
        } else {
            val solveUrl = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                ?: runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault(url)
            getHtml(solveUrl)?.let { attempt()?.let { retried -> if (ready(retried)) return@withContext retried } }
        }
        null
    }

    /** Snapshot cookies for [url] so a signed playback session can be replayed to a cross-host redeem URL. */
    fun cookieHeader(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return ""
        return runCatching { cookieJar.loadForRequest(parsed) }
            .getOrDefault(emptyList())
            .filter { it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Fetch a poster/cover image for the panel image proxy. This is server-oriented: proxy
     * credentials never ship to the app. Player/video URLs do not use this path.
     */
    suspend fun getImage(url: String): LiveImage? = withContext(Dispatchers.IO) {
        runCatching { fetchImage(url, cookie = null, userAgent = UA) }.getOrNull()?.let { return@withContext it }
        if (flareEndpoint.isBlank()) return@withContext null
        val clearance = solveImageClearance(flareEndpoint, url, useProxy = false)
            ?: cfEndpoint()?.let { solveImageClearance(it, url, useProxy = false) }
            ?: solverProxy.takeIf { it.isNotBlank() }?.let { solveImageClearance(flareEndpoint, url, useProxy = true) }
            ?: solverProxy.takeIf { it.isNotBlank() }?.let { cfEndpoint()?.let { cf -> solveImageClearance(cf, url, useProxy = true) } }
        if (clearance != null) {
            runCatching { fetchImage(clearance.url, clearance.cookie, clearance.userAgent.ifBlank { UA }) }.getOrNull()
        } else {
            null
        }
    }

    private fun fetchDirect(url: String): String? {
        val proxied = LiveRuntimeConfig.proxiedRequestFor(url)
        val req = Request.Builder().url(proxied?.url ?: url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "id-ID,id;q=0.9,en;q=0.8")
            .apply { proxied?.let { header("Authorization", "Bearer ${it.bearer}") } }
            .get().build()
        direct.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return body.ifBlank { null }
        }
    }

    private data class ImageClearance(val url: String, val cookie: String, val userAgent: String)

    private fun solveImageClearance(endpoint: String, url: String, useProxy: Boolean = true): ImageClearance? = runCatching {
        val payload = JSONObject()
            .put("cmd", "request.get")
            .put("url", url)
            .put("maxTimeout", 60000)
            .withProxy(useProxy)
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
            val html = sol.optString("response")
            val imgUrl = Regex("""<img[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
                ?.replace("&amp;", "&")
                ?.takeIf { it.startsWith("http") }
                ?: url
            val cookies = sol.optJSONArray("cookies")
            val cookieHeader = buildString {
                if (cookies != null) {
                    for (i in 0 until cookies.length()) {
                        val c = cookies.optJSONObject(i) ?: continue
                        val name = c.optString("name")
                        val value = c.optString("value")
                        if (name.isBlank() || value.isBlank()) continue
                        if (isNotEmpty()) append("; ")
                        append(name).append('=').append(value)
                    }
                }
            }
            saveImageCookies(imgUrl, cookies)
            ImageClearance(imgUrl, cookieHeader, sol.optString("userAgent").ifBlank { UA })
        }
    }.getOrNull()

    private fun saveImageCookies(url: String, cookies: JSONArray?) {
        if (cookies == null || cookies.length() == 0) return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val parsed = ArrayList<Cookie>()
        for (i in 0 until cookies.length()) {
            val c = cookies.optJSONObject(i) ?: continue
            val name = c.optString("name")
            val value = c.optString("value")
            if (name.isBlank() || value.isBlank()) continue
            val domain = c.optString("domain").trim().trimStart('.').ifBlank { httpUrl.host }
            val path = c.optString("path").ifBlank { "/" }
            runCatching {
                parsed += Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path(path)
                    .build()
            }
        }
        if (parsed.isNotEmpty()) cookieJar.saveFromResponse(httpUrl, parsed)
    }

    private fun fetchImage(url: String, cookie: String?, userAgent: String): LiveImage? {
        val req = Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Referer", refererFor(url))
            .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
            .get().build()
        direct.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val contentType = body.contentType()?.toString()
                ?: resp.header("Content-Type").orEmpty()
            if (!contentType.startsWith("image/", ignoreCase = true)) return null
            val bytes = readCapped(body.byteStream(), 5 * 1024 * 1024) ?: return null
            return LiveImage(bytes, contentType.substringBefore(';').ifBlank { "image/jpeg" })
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Int): ByteArray? = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            out.write(buf, 0, n)
        }
        out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun refererFor(url: String): String =
        runCatching { URI(url).let { "${it.scheme}://${it.host}/" } }.getOrDefault(url)

    /** POST a FlareSolverr `request.get` and return the solved HTML, or null. */
    private fun solveVia(endpoint: String, url: String, useProxy: Boolean = true): String? = runCatching {
        val payload = JSONObject()
            .put("cmd", "request.get")
            .put("url", url)
            .put("maxTimeout", 60000)
            .withProxy(useProxy)
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
    suspend fun postBypass(url: String, form: String, referer: String? = null, origin: String? = null, isValid: (String) -> Boolean): String? =
        withContext(Dispatchers.IO) {
            runCatching { postDirect(url, form, referer, origin) }.getOrNull()?.let { if (isValid(it)) return@withContext it }
            if (flareEndpoint.isNotBlank()) {
                postViaFlare(flareEndpoint, url, form)?.let { if (isValid(it)) return@withContext it }
                if (solverProxy.isNotBlank()) postViaFlare(flareEndpoint, url, form, useProxy = false)?.let { if (isValid(it)) return@withContext it }
                cfEndpoint()?.let { cf ->
                    postViaFlare(cf, url, form)?.let { if (isValid(it)) return@withContext it }
                    if (solverProxy.isNotBlank()) postViaFlare(cf, url, form, useProxy = false)?.let { if (isValid(it)) return@withContext it }
                }
            }
            null
        }

    private fun postDirect(url: String, form: String, referer: String?, origin: String? = null): String? {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { referer?.let { header("Referer", it) } }
            // Some admin-ajax handlers (NontonAnimeID's kotakanime2 player) reject the request without a
            // same-site Origin — the browser's fetch() sends it, so we replay it when the caller asks.
            .apply { origin?.let { header("Origin", it) } }
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        direct.newCall(req).execute().use { resp ->
            return resp.body?.string().orEmpty().ifBlank { null }
        }
    }

    /** FlareSolverr `request.post`: loads [url] with [postData] in the headless browser and returns
     *  the rendered response body (the JSON wrapped in the browser's page view). */
    private fun postViaFlare(endpoint: String, url: String, postData: String, useProxy: Boolean = true): String? = runCatching {
        val payload = JSONObject()
            .put("cmd", "request.post")
            .put("url", url)
            .put("postData", postData)
            .put("maxTimeout", 60000)
            .withProxy(useProxy)
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

    /** Attach the FlareSolverr `proxy` block when [solverProxy] is set (server-side only); a no-op
     *  on the app, where it stays blank so requests egress from the device's own IP. */
    private fun JSONObject.withProxy(useProxy: Boolean): JSONObject = apply {
        if (!useProxy || solverProxy.isBlank()) return@apply
        val parts = solverProxyParts(solverProxy)
        val proxy = JSONObject().put("url", parts.url)
        if (!parts.username.isNullOrBlank()) proxy.put("username", parts.username)
        if (!parts.password.isNullOrBlank()) proxy.put("password", parts.password)
        put("proxy", proxy)
    }

    private data class SolverProxyParts(val url: String, val username: String?, val password: String?)

    /**
     * Chrome rejects proxy URLs that embed credentials (`ERR_NO_SUPPORTED_PROXIES`). FlareSolverr
     * accepts those credentials as separate JSON fields, so keep the proxy host in `url` and split
     * `user:pass@` out when the server env uses the compact URL form.
     */
    private fun solverProxyParts(raw: String): SolverProxyParts {
        val trimmed = raw.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return SolverProxyParts(trimmed, null, null)
        val prefix = trimmed.substring(0, schemeEnd + 3)
        val rest = trimmed.substring(schemeEnd + 3)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, pathStart)
        val suffix = rest.substring(pathStart)
        val at = authority.lastIndexOf('@')
        if (at <= 0) return SolverProxyParts(trimmed, null, null)

        val userInfo = authority.substring(0, at)
        val host = authority.substring(at + 1)
        val colon = userInfo.indexOf(':')
        val username = decodeProxyPart(if (colon >= 0) userInfo.substring(0, colon) else userInfo)
        val password = if (colon >= 0) decodeProxyPart(userInfo.substring(colon + 1)) else null
        return SolverProxyParts(prefix + host + suffix, username, password)
    }

    private fun decodeProxyPart(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

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
            "attention required!" in h ||
            "sorry, you have been blocked" in h ||
            "this website is using a security service" in h ||
            "verify_human" in h ||
            "err_no_supported_proxies" in h ||
            "err_tunnel_connection_failed" in h ||
            "this site can’t be reached" in h ||
            "this site can't be reached" in h ||
            ("<title>loading" in h && "turnstile" in h) ||
            "checking your browser" in h
    }
}
