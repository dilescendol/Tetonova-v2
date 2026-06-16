package com.tetonova.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.util.concurrent.TimeUnit

/** A directly-playable stream variant resolved from an embed host (label = "720p"/"Auto", url = mp4/m3u8). */
data class StreamVariant(val label: String, val url: String)

/** Extracted variants + the HTTP headers their CDN needs (per-host: Dailymotion wants NONE, while
 *  OK.ru/Rumble/Filemoon want the embed host's Referer/Origin). */
data class ExtractResult(val variants: List<StreamVariant>, val headers: Map<String, String> = emptyMap())

/**
 * Resolves an embed host's iframe URL into direct stream variants so they play in the app's own
 * ExoPlayer (consistent controls + Resolusi picker) instead of the host's WebView UI. Per-host and
 * inherently fragile — when a host changes its page, its extractor needs updating. Hosts without a
 * working extractor return empty and the player falls back to a WebView embed.
 */
object StreamExtractor {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

    /** Hosts to hide from the picker — file-lockers / gated pages that won't yield a video stream
     *  even via the WebView sniffer. Everything else is shown and resolved by static extractor →
     *  WebView sniffer → WebView embed. */
    private val DEAD = listOf("terabox", "streamwish", "wishfast", "mega.nz", "krakenfiles", "drive.google", "racaty")

    fun isPlayable(embedUrl: String): Boolean {
        val u = embedUrl.lowercase()
        return DEAD.none { it in u }
    }

    /** DoodStream family domains (anixcafe ships it as "playmogo.com"). */
    private val DOOD = listOf("dood", "playmogo", "dsvplay", "d-s.io", "ds2play", "do0od", "doodstream")
    private fun isDoodHost(u: String): Boolean = u.lowercase().let { l -> DOOD.any { it in l } }

    suspend fun extract(server: VideoServer, referer: String): ExtractResult = runCatching {
        val u = server.embedUrl
        when {
            "ok.ru" in u || "odnoklassniki" in u -> ExtractResult(okru(u), originHeaders(u))
            // Dailymotion verifies the embedder: fetch metadata AS the real embedder (anixcafe) to get a
            // valid token, then play the manifest with the dailymotion.com referer (mimics the iframe).
            "dailymotion" in u -> ExtractResult(
                dailymotion(u, referer),
                mapOf("Referer" to "https://www.dailymotion.com/", "Origin" to "https://www.dailymotion.com"),
            )
            "rumble.com" in u -> ExtractResult(rumble(u), originHeaders(u))
            isDoodHost(u) -> dood(u)
            else -> generic(u, referer).let { ExtractResult(it, if (it.isEmpty()) emptyMap() else originHeaders(u)) }
        }
    }.getOrElse { ExtractResult(emptyList()) }

    private fun originHeaders(embedUrl: String): Map<String, String> {
        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: return emptyMap()
        return mapOf("Referer" to "$origin/", "Origin" to origin)
    }

    private suspend fun fetch(url: String, referer: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val rb = Request.Builder().url(url).header("User-Agent", UA)
            referer?.let { rb.header("Referer", it) }
            http.newCall(rb.build()).execute().use { it.body?.string() }
        }.getOrNull()
    }

    // ---- OK.ru: data-options → flashvars.metadata.videos[] (progressive mp4 per quality) ----
    private suspend fun okru(embedUrl: String): List<StreamVariant> {
        val html = LiveClient.getHtml(embedUrl) ?: return emptyList()
        val opts = Regex("data-options=\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return emptyList()
        val flash = JSONObject(Parser.unescapeEntities(opts, false)).optJSONObject("flashvars") ?: return emptyList()
        val metaStr = flash.optString("metadata").takeIf { it.isNotBlank() } ?: return emptyList()
        val videos = JSONObject(metaStr).optJSONArray("videos") ?: return emptyList()
        val out = ArrayList<StreamVariant>()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            val url = v.optString("url")
            if (url.startsWith("http")) out.add(StreamVariant(okLabel(v.optString("name")), url))
        }
        return out.asReversed() // OK.ru lists low→high; present high→low
    }

    private fun okLabel(name: String) = when (name) {
        "mobile" -> "144p"; "lowest" -> "240p"; "low" -> "360p"
        "sd" -> "480p"; "hd" -> "720p"; "full" -> "1080p"; "quad" -> "1440p"; "ultra" -> "2160p"; else -> name
    }

    // ---- Dailymotion: metadata API → qualities.auto[] HLS master (ExoPlayer adapts) ----
    private suspend fun dailymotion(embedUrl: String, referer: String): List<StreamVariant> {
        val id = Regex("[?&]video=([^&]+)").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("dailymotion\\.com/(?:embed/)?video/([^_?&/]+)").find(embedUrl)?.groupValues?.get(1)
            ?: return emptyList()
        // Fetch metadata AS the embedder (anixcafe) so Dailymotion authorizes a playable token.
        val json = fetch("https://www.dailymotion.com/player/metadata/video/$id", referer.ifBlank { "https://www.dailymotion.com/" }) ?: return emptyList()
        val q = JSONObject(json).optJSONObject("qualities") ?: return emptyList()
        val auto = q.optJSONArray("auto") ?: return emptyList()
        for (i in 0 until auto.length()) {
            val url = auto.getJSONObject(i).optString("url")
            if (url.contains("m3u8")) return listOf(StreamVariant("Auto", url))
        }
        return emptyList()
    }

    // ---- DoodStream family: GET /pass_md5/<id>/<token> (with the embed's cookies) → CDN base URL,
    //      then the playable mp4 = base + 10 random chars + ?token=<token>&expiry=<now>. mp4 wants the
    //      dood host as Referer (no Origin). The pass_md5 token is short-lived so we fetch it fresh. ----
    private suspend fun dood(embedUrl: String): ExtractResult = withContext(Dispatchers.IO) {
        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
            ?: return@withContext ExtractResult(emptyList())
        // Per-call cookie jar so the cookie the embed page sets is sent on the /pass_md5 request.
        val jar = object : okhttp3.CookieJar {
            private val store = mutableListOf<okhttp3.Cookie>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) { store += cookies }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store
        }
        val client = http.newBuilder().cookieJar(jar).build()
        fun get(url: String, referer: String, xhr: Boolean): String? = runCatching {
            val rb = Request.Builder().url(url).header("User-Agent", UA).header("Referer", referer)
            if (xhr) rb.header("X-Requested-With", "XMLHttpRequest")
            client.newCall(rb.build()).execute().use { it.body?.string() }
        }.getOrNull()

        val html = get(embedUrl, "$origin/", false) ?: return@withContext ExtractResult(emptyList())
        val pass = Regex("/pass_md5/[^\"'\\s]+").find(html)?.value ?: return@withContext ExtractResult(emptyList())
        val token = pass.substringAfterLast('/')
        val base = get("$origin$pass", embedUrl, true)?.trim()?.takeIf { it.startsWith("http") }
            ?: return@withContext ExtractResult(emptyList())
        val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rand = buildString { repeat(10) { append(pool[kotlin.random.Random.nextInt(pool.length)]) } }
        val url = "$base$rand?token=$token&expiry=${System.currentTimeMillis()}"
        ExtractResult(listOf(StreamVariant("Auto", url)), mapOf("Referer" to "$origin/"))
    }

    // ---- Rumble: embedJS → ua.hls.auto + ua.mp4[quality] ----
    private suspend fun rumble(embedUrl: String): List<StreamVariant> {
        val id = Regex("rumble\\.com/embed/([^/?]+)").find(embedUrl)?.groupValues?.get(1) ?: return emptyList()
        val json = fetch("https://rumble.com/embedJS/u3/?request=video&ver=2&v=$id", "https://rumble.com/") ?: return emptyList()
        val ua = JSONObject(json).optJSONObject("ua") ?: return emptyList()
        val out = ArrayList<StreamVariant>()
        ua.optJSONObject("hls")?.optJSONObject("auto")?.optString("url")?.takeIf { it.contains("m3u8") }
            ?.let { out.add(StreamVariant("Auto", it)) }
        ua.optJSONObject("mp4")?.let { mp4 ->
            mp4.keys().asSequence().sortedByDescending { it.toIntOrNull() ?: 0 }.forEach { k ->
                mp4.optJSONObject(k)?.optString("url")?.takeIf { it.startsWith("http") }
                    ?.let { out.add(StreamVariant("${k}p", it)) }
            }
        }
        return out
    }

    // ---- Generic Filemoon-family: unpack packed JS → m3u8 (filelions/streamwish/short.ink/…) ----
    private suspend fun generic(embedUrl: String, referer: String): List<StreamVariant> {
        val html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: return emptyList()
        val m3u8 = findStream(html) ?: return emptyList()
        return listOf(StreamVariant("Auto", m3u8))
    }

    private fun findStream(html: String): String? {
        val text = (unpack(html) ?: "") + "\n" + html
        Regex("https?://[^\"'\\\\\\s]+\\.m3u8[^\"'\\\\\\s]*").find(text)?.let { return it.value }
        Regex("file\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?.takeIf { it.contains(".m3u8") || it.contains(".mp4") }?.let { return it }
        return null
    }

    // ---- Dean Edwards p,a,c,k,e,d unpacker ----
    private fun unpack(js: String): String? {
        val m = Regex("""\}\s*\(\s*'(.*?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(js) ?: return null
        var p = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\").replace("\\n", "\n")
        val a = m.groupValues[2].toIntOrNull() ?: return null
        val c = m.groupValues[3].toIntOrNull() ?: return null
        val k = m.groupValues[4].split("|")
        for (i in c - 1 downTo 0) {
            val key = k.getOrNull(i)
            if (!key.isNullOrEmpty()) {
                p = p.replace(Regex("\\b" + Regex.escape(enc(i, a)) + "\\b"), Regex.escapeReplacement(key))
            }
        }
        return p
    }

    /** packer's `e(c)`: base-`a` token using 0-9a-z then A-Z for digits >35. */
    private fun enc(c: Int, a: Int): String {
        val prefix = if (c < a) "" else enc(c / a, a)
        val r = c % a
        return prefix + (if (r > 35) ('a'.code + r - 36 + ('A' - 'a')).toChar().toString() else r.toString(36))
    }
}
