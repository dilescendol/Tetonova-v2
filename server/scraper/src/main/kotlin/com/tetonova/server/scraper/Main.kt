package com.tetonova.server.scraper

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.tetonova.core.scraper.LiveClient
import com.tetonova.core.scraper.LiveDetail
import com.tetonova.core.scraper.LiveItem
import com.tetonova.core.scraper.LiveSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Server-side scraper service = `config.scraper.base_url`. Wraps the shared `core:scraper` parser
 * behind the HTTP contract the PHP panel's `ScraperProxy` already calls:
 *   GET /health
 *   GET /sources                         → list of known source keys
 *   GET /sources/{key}/home?page=        → {sections:[{title,url,items[]}]}
 *   GET /sources/{key}/search?q=&page=   → {items[]}
 *   GET /sources/{key}/detail/{id}       → detail object   (id = url-encoded source URL)
 *   GET /sources/{key}/playback/{id}     → {servers:[]}    (player resolves live in-app; stub)
 * All but /health require header `X-TN-Scraper-Token`. The panel caches these responses; this stays
 * stateless. Sources needing a real browser (animesail/oppadrama/kuramanime-player) can't be solved
 * here (no WebView ChallengeSolver) → they return empty → panel 503 → app live-fallback.
 *
 * Env: PORT, SCRAPER_TOKEN, PANEL_URL, FLARE_ENDPOINT, FLARE_TOKEN, SOLVER_PROXY (residential egress).
 */
fun main() {
    val port = (System.getenv("PORT") ?: "8090").toInt()
    val token = System.getenv("SCRAPER_TOKEN").orEmpty()
    val panel = (System.getenv("PANEL_URL") ?: "https://tetonova.biz.id").trimEnd('/')
    LiveClient.flareEndpoint = System.getenv("FLARE_ENDPOINT").orEmpty()
    LiveClient.flareToken = System.getenv("FLARE_TOKEN").orEmpty()
    // Server-only: route FlareSolverr's browser through a residential proxy (e.g. DataImpulse
    // country=ID) so geo/hard-CF solves egress from a residential IP. Blank ⇒ host's own IP.
    LiveClient.solverProxy = System.getenv("SOLVER_PROXY").orEmpty()

    SourceMap.start(panel)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(32)
    server.createContext("/") { ex -> ex.use { handle(it, token) } }
    server.start()
    println("tn-scraper listening on :$port (panel=$panel, flare=${LiveClient.flareEndpoint.ifBlank { "none" }})")
}

private inline fun HttpExchange.use(block: (HttpExchange) -> Unit) {
    try { block(this) } catch (e: Exception) { runCatching { respond(this, 500, err(e.message ?: "error")) } } finally { close() }
}

private fun handle(ex: HttpExchange, token: String) {
    val rawPath = ex.requestURI.rawPath
    if (rawPath == "/health") { respond(ex, 200, JSONObject().put("ok", true)); return }
    if (token.isNotEmpty() && ex.requestHeaders.getFirst("X-TN-Scraper-Token") != token) {
        respond(ex, 401, err("unauthorized")); return
    }
    if (rawPath == "/sources") { respond(ex, 200, JSONObject().put("sources", JSONArray(SourceMap.keys()))); return }
    if (rawPath == "/image") {
        val url = parseQuery(ex.requestURI.rawQuery)["url"].orEmpty()
        if (!isAllowedImageUrl(url)) { respond(ex, 422, err("invalid_image_url")); return }
        val image = runBlocking { LiveClient.getImage(url) }
        if (image == null) { respond(ex, 502, err("image_unreachable")); return }
        respondBytes(ex, 200, image.contentType, image.bytes)
        return
    }

    val seg = rawPath.trim('/').split('/')
    if (seg.size < 3 || seg[0] != "sources") { respond(ex, 404, err("not_found")); return }
    val key = dec(seg[1])
    val action = seg[2]
    val src = SourceMap.get(key) ?: run { respond(ex, 404, err("unknown_source")); return }
    val q = parseQuery(ex.requestURI.rawQuery)

    when (action) {
        "home" -> respond(ex, 200, homeJson(src, q["page"]?.toIntOrNull(), q["url"]?.takeIf { it.isNotBlank() }))
        "search" -> {
            val term = q["q"].orEmpty()
            if (term.isBlank()) { respond(ex, 422, err("invalid_query")); return }
            respond(ex, 200, itemsJson(runBlocking { LiveSource.search(src.baseUrl, term) }))
        }
        "detail" -> {
            val id = seg.getOrNull(3)?.let { dec(it) }.orEmpty()
            if (id.isBlank()) { respond(ex, 422, err("missing_id")); return }
            respond(ex, 200, detailJson(runBlocking { LiveSource.detail(id) }))
        }
        // Per-episode stream resolution stays live in the app (ephemeral/tokened); not cached here.
        "playback" -> respond(ex, 200, JSONObject().put("servers", JSONArray()))
        else -> respond(ex, 404, err("unknown_action"))
    }
}

private fun homeJson(src: Src, page: Int?, urlOverride: String?): JSONObject = runBlocking {
    val sections = JSONArray()
    val allLinks = src.homeLinks.ifEmpty { listOf("Latest" to src.baseUrl) }
    val links = when {
        !urlOverride.isNullOrBlank() && isAllowedListUrl(src, urlOverride) -> listOf("More" to urlOverride)
        !urlOverride.isNullOrBlank() -> emptyList()
        else -> page?.takeIf { it > 0 }?.let { p -> allLinks.drop(p - 1).take(1) } ?: allLinks
    }
    for ((label, url) in links) {
        val pageData = runCatching { LiveSource.listPage(url) }.getOrDefault(com.tetonova.core.scraper.LivePage(emptyList()))
        sections.put(
            JSONObject()
                .put("title", label)
                .put("url", url)
                .put("items", itemsArray(pageData.items))
                .putOpt("nextUrl", pageData.nextUrl)
        )
    }
    JSONObject().put("sections", sections)
}

private fun itemsArray(items: List<LiveItem>): JSONArray {
    val a = JSONArray()
    for (it in items) a.put(
        JSONObject().put("title", it.title).put("url", it.url)
            .putOpt("cover", it.cover).putOpt("type", it.type).putOpt("status", it.status),
    )
    return a
}

private fun itemsJson(items: List<LiveItem>): JSONObject = JSONObject().put("items", itemsArray(items))

private fun detailJson(d: LiveDetail?): JSONObject {
    if (d == null) return err("not_found")
    val eps = JSONArray()
    for (e in d.episodes) eps.put(JSONObject().put("num", e.num).put("title", e.title).put("url", e.url).putOpt("thumb", e.thumb))
    return JSONObject()
        .put("title", d.title).put("url", d.url)
        .putOpt("cover", d.cover).putOpt("synopsis", d.synopsis).putOpt("status", d.status)
        .putOpt("type", d.type).putOpt("studio", d.studio).putOpt("released", d.released)
        .putOpt("country", d.country).putOpt("seriesUrl", d.seriesUrl)
        .put("genres", JSONArray(d.genres)).put("episodes", eps)
}

private fun respond(ex: HttpExchange, code: Int, json: JSONObject) {
    val bytes = json.toString().toByteArray(UTF_8)
    ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun respondBytes(ex: HttpExchange, code: Int, contentType: String, bytes: ByteArray) {
    ex.responseHeaders.add("Content-Type", contentType)
    ex.responseHeaders.add("Cache-Control", "public, max-age=604800")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

private fun err(message: String): JSONObject = JSONObject().put("error", message)
private fun dec(s: String): String = runCatching { URLDecoder.decode(s, UTF_8) }.getOrDefault(s)
private fun parseQuery(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('&').mapNotNull {
        val i = it.indexOf('='); if (i < 0) null else dec(it.take(i)) to dec(it.substring(i + 1))
    }.toMap()
}

/** label → url home rails + the source base, resolved from the panel's /api/v1/sources. */
private fun isAllowedImageUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    if (host == "winbu.net" || host.endsWith(".winbu.net")) return true
    if (host == "nekopoi.care" || host.endsWith(".nekopoi.care")) return true
    if (host == "nontonanimeid.boats" || host.endsWith(".nontonanimeid.boats")) return true
    if (Regex("""^i[0-3]\.wp\.com$""").matches(host)) {
        return uri.rawPath.orEmpty().lowercase().contains("nontonanimeid")
    }
    return false
}

private fun isAllowedListUrl(src: Src, url: String): Boolean {
    val target = runCatching { URI(url) }.getOrNull() ?: return false
    val targetHost = target.host?.lowercase() ?: return false
    if (target.scheme?.lowercase() !in setOf("http", "https")) return false
    val allowedHosts = buildSet {
        runCatching { URI(src.baseUrl).host?.lowercase() }.getOrNull()?.let(::add)
        src.homeLinks.forEach { (_, link) ->
            runCatching { URI(link).host?.lowercase() }.getOrNull()?.let(::add)
        }
    }
    return allowedHosts.any { targetHost == it || targetHost.endsWith(".$it") }
}

private data class Src(val baseUrl: String, val homeLinks: List<Pair<String, String>>)

private object SourceMap {
    @Volatile private var map: Map<String, Src> = emptyMap()
    private val http = panelHttpClient()
    private var panelUrl: String = ""

    fun start(panel: String) {
        panelUrl = panel
        runCatching { refresh() }.onFailure { System.err.println("source map refresh failed: ${it.message}") }
        thread(isDaemon = true) {
            while (true) { Thread.sleep(600_000); runCatching { refresh() } }
        }
    }

    fun keys(): List<String> = map.keys.sorted()

    fun get(key: String): Src? {
        val k = key.lowercase()
        return map[k] ?: map[k.removeSuffix("-compat")] ?: map["$k-compat"]
    }

    private fun refresh() {
        val body = http.newCall(Request.Builder().url("$panelUrl/api/v1/sources").build())
            .execute().use { it.body?.string() } ?: return
        val root = JSONObject(body)
        // Auto-pull flare creds from the panel's proxyBypass block so CF-shielded sources
        // (otakudesu/kuramanime/…) get solved server-side instead of returning a challenge page.
        // Cache + cron mean this only hits the flare box at cron-rate, not per user.
        root.optJSONObject("proxyBypass")?.let { pb ->
            val ep = pb.optString("flareSolverrEndpoint")
            if (ep.isNotBlank()) {
                LiveClient.flareEndpoint = ep
                LiveClient.flareToken = pb.optString("flareSolverrToken")
            }
        }
        val arr = root.optJSONArray("sources") ?: return
        val m = HashMap<String, Src>()
        val accessCodes = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.optString("sourceId").lowercase()
            if (id.isBlank()) continue
            val base = s.optString("apiBaseUrl").ifBlank { s.optString("webBaseUrl") }
            val code = s.optString("accessCode").trim()
            if (code.isNotBlank()) {
                val bases = buildList {
                    add(base)
                    s.optString("webBaseUrl").takeIf { it.isNotBlank() }?.let(::add)
                    if (id.contains("reelshort", ignoreCase = true)) add("https://reelshort.goodbos.online")
                }
                bases.filter { it.isNotBlank() }.distinct().forEach { accessCodes[it] = code }
            }
            // Warm EVERY rail the source can display, not just "Semua" mode: a source's per-chip rails
            // (showOnClick — e.g. nekopoi's 2D Animation / 3D Hentai / JAV) must be cached too, else the
            // app finds those rails uncached and falls back to its stale bundled catalog. The cache is
            // per-source and mode-agnostic, so it should hold both showAll and showOnClick URLs.
            val links = ArrayList<Pair<String, String>>()
            val seen = HashSet<String>()
            val homeLinks = s.optJSONObject("homeLinks")
            for (group in listOf("showAll", "showOnClick")) {
                val arr = homeLinks?.optJSONArray(group) ?: continue
                for (j in 0 until arr.length()) {
                    val l = arr.getJSONObject(j)
                    val u = l.optString("url")
                    if (u.isNotBlank() && seen.add(u)) links.add(l.optString("label").ifBlank { "Latest" } to u)
                }
            }
            m[id] = Src(base, links)
        }
        LiveSource.configureAccessCodes(accessCodes)
        map = m
        println("source map: ${m.size} sources, flare=${LiveClient.flareEndpoint.ifBlank { "none" }}")
    }
}

private fun panelHttpClient(): OkHttpClient {
    val trustAll = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    }
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .build()
}
