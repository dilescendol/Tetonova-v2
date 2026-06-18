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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors
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
 * Env: PORT, SCRAPER_TOKEN, PANEL_URL, FLARE_ENDPOINT, FLARE_TOKEN.
 */
fun main() {
    val port = (System.getenv("PORT") ?: "8090").toInt()
    val token = System.getenv("SCRAPER_TOKEN").orEmpty()
    val panel = (System.getenv("PANEL_URL") ?: "https://tetonova.dilcendol.web.id").trimEnd('/')
    LiveClient.flareEndpoint = System.getenv("FLARE_ENDPOINT").orEmpty()
    LiveClient.flareToken = System.getenv("FLARE_TOKEN").orEmpty()

    SourceMap.start(panel)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(8)
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

    val seg = rawPath.trim('/').split('/')
    if (seg.size < 3 || seg[0] != "sources") { respond(ex, 404, err("not_found")); return }
    val key = dec(seg[1])
    val action = seg[2]
    val src = SourceMap.get(key) ?: run { respond(ex, 404, err("unknown_source")); return }
    val q = parseQuery(ex.requestURI.rawQuery)

    when (action) {
        "home" -> respond(ex, 200, homeJson(src))
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

private fun homeJson(src: Src): JSONObject = runBlocking {
    val sections = JSONArray()
    val links = src.homeLinks.ifEmpty { listOf("Latest" to src.baseUrl) }
    for ((label, url) in links) {
        val items = runCatching { LiveSource.list(url) }.getOrDefault(emptyList())
        sections.put(JSONObject().put("title", label).put("url", url).put("items", itemsArray(items)))
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
    for (e in d.episodes) eps.put(JSONObject().put("num", e.num).put("title", e.title).put("url", e.url))
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

private fun err(message: String): JSONObject = JSONObject().put("error", message)
private fun dec(s: String): String = runCatching { URLDecoder.decode(s, UTF_8) }.getOrDefault(s)
private fun parseQuery(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split('&').mapNotNull {
        val i = it.indexOf('='); if (i < 0) null else dec(it.take(i)) to dec(it.substring(i + 1))
    }.toMap()
}

/** label → url home rails + the source base, resolved from the panel's /api/v1/sources. */
private data class Src(val baseUrl: String, val homeLinks: List<Pair<String, String>>)

private object SourceMap {
    @Volatile private var map: Map<String, Src> = emptyMap()
    private val http = OkHttpClient()
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
        val arr = JSONObject(body).optJSONArray("sources") ?: return
        val m = HashMap<String, Src>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.optString("sourceId").lowercase()
            if (id.isBlank()) continue
            val base = s.optString("apiBaseUrl").ifBlank { s.optString("webBaseUrl") }
            val links = ArrayList<Pair<String, String>>()
            s.optJSONObject("homeLinks")?.optJSONArray("showAll")?.let { sa ->
                for (j in 0 until sa.length()) {
                    val l = sa.getJSONObject(j)
                    val u = l.optString("url")
                    if (u.isNotBlank()) links.add(l.optString("label").ifBlank { "Latest" } to u)
                }
            }
            m[id] = Src(base, links)
        }
        map = m
        println("source map: ${m.size} sources")
    }
}
