package com.tetonova.core.scraper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A directly-playable stream variant resolved from an embed host (label = "720p"/"Auto", url = mp4/m3u8). */
data class StreamVariant(val label: String, val url: String)

/** Extracted variants + the HTTP headers their CDN needs (per-host: Dailymotion wants NONE, while
 *  OK.ru/Rumble/Filemoon want the embed host's Referer/Origin). */
data class ExtractResult(
    val variants: List<StreamVariant>,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    /**
     * The host positively told us the file is gone (deleted / never existed), as opposed to us merely
     * failing to parse it. Only set from an explicit upstream signal — never from "we found nothing",
     * because that is the normal case for the many hosts that only play inside their own WebView.
     * The player uses it to skip the sniff, which can only time out on a file that does not exist.
     */
    val gone: Boolean = false,
)

/**
 * Explicit "this file is gone" markers from an embed host's error page. Deliberately narrow: a false
 * positive here skips a WebView sniff that might have worked, so only unambiguous phrases belong.
 */
private val GONE_MARKERS = Regex(
    """(?i)\b(file (?:was )?(?:deleted|not found)|video (?:not found|has been (?:deleted|removed))|"""
        + """no longer available|not found\s*!|deleted by (?:the )?(?:owner|user)|file (?:is )?expired)\b"""
)

internal fun looksGone(html: String): Boolean = GONE_MARKERS.containsMatchIn(html)

/**
 * Resolves an embed host's iframe URL into direct stream variants so they play in the app's own
 * ExoPlayer (consistent controls + Resolusi picker) instead of the host's WebView UI. Per-host and
 * inherently fragile — when a host changes its page, its extractor needs updating. Hosts without a
 * working extractor return empty and the player falls back to a WebView embed.
 */
object StreamExtractor {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    // IPv4-only so a host that pins a token to the requesting IP (Dailymotion's HLS `sec=`) hands us a
    // token the app's ExoPlayer — which also uses an IPv4-only OkHttp datasource — can actually replay.
    // On a dual-stack phone OkHttp (metadata) and HttpURLConnection (playback) otherwise pick different
    // v4/v6 public addresses, so the token 403s even though the URL+headers are identical.
    private val ipv4Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            okhttp3.Dns.SYSTEM.lookup(hostname).filterIsInstance<java.net.Inet4Address>()
                .ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
    }
    private val http = OkHttpClient.Builder()
        .dns(ipv4Dns)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

    /** Hosts to hide from the picker — file-lockers / gated pages that won't yield a video stream
     *  even via the WebView sniffer. Everything else is shown and resolved by static extractor →
     *  WebView sniffer → WebView embed. */
    private val DEAD = listOf(
        "terabox", "streamwish", "wishfast", "mega.nz", "krakenfiles", "drive.google", "racaty",
        // File-lockers that Samehadaku and other sites commonly use (but Mega works via WebView)
        "streamsb", "ssbstream", "sbplay", "streamtape", "uqload",
        "filerio", "fastclick", "子上" // Japanese ad redirects
    )

    fun isPlayable(embedUrl: String): Boolean {
        val u = embedUrl.lowercase()
        return DEAD.none { it in u }
    }

    /** Friendly label from an embed URL (for fallback naming when server name is blank). */
    fun playableHostLabel(url: String): String {
        val h = url.lowercase()
        return when {
            "ok.ru" in h || "odnoklassniki" in h -> "OK.ru"
            "dailymotion" in h -> "Dailymotion"
            "rumble" in h -> "Rumble"
            "videoplayer.vip" in h -> "Player VIP"
            "dood" in h || "playmogo" in h || "myvidplay" in h -> "DoodStream"
            "blogger.com" in h || "blogspot.com" in h -> "Blogger"
            "4meplayer" in h -> "4MePlayer"
            "playeriframe.sbs" in h -> "PlayerIframe"
            "filedon" in h -> "Filedon"
            "pixeldrain" in h -> "PixelDrain"
            "desustream" in h -> "Desustream"
            "kotakanime" in h -> "KotaAnime"
            "kuramanime" in h || "kuramadrive" in h -> "Kuramanime"
            "anixcafe" in h -> "AnixCafe"
            "oploverz" in h -> "Oploverz"
            "otakudesu" in h -> "Otakudesu"
            else -> runCatching {
                java.net.URI(url).host.orEmpty().removePrefix("www.").split(".").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Embed"
            }.getOrDefault("Embed")
        }
    }

    /** True when the URL is itself a directly-playable stream (path ends in a media extension before
     *  any query) rather than an embed host page — e.g. kuramadrive's signed .mp4. */
    private fun isDirectVideo(url: String): Boolean {
        val low = url.lowercase()
        val path = low.substringBefore('?').substringBefore('#')
        return path.endsWith(".mp4") ||
            path.endsWith(".m3u8") ||
            path.endsWith(".mkv") ||
            // Majorplay deliberately disguises HLS master/media playlists as signed config/data JSON.
            // The response body is still #EXTM3U and Media3 is told its MIME type by PlayerScreen.
            ("majorplay.net" in low && Regex("/(?:config|data)-\\d+\\.json$").containsMatchIn(path)) ||
            "awscdn.netshort.com" in low ||
            "mime_type=video_mp4" in low ||
            ("bilitv.goodbos.online/api/proxy" in low && ".m3u8" in low) ||
            ("goodshort.goodbos.online" in low && "/hls/" in path) ||
            ("cdn.dramabos.video/api/" in low && "/hls" in path) ||
            "videotv.vividshort.com" in low ||
            "videotv.dramaexpo.com" in low ||
            "montagehub.xyz" in low ||
            "janzhoutec.com" in low ||
            ("kesbayar.sbs" in low && Regex("\\.\\d{3,4}p$").containsMatchIn(path))
    }

    /** DoodStream family domains (anixcafe ships it as "playmogo.com"; kuramanime as "myvidplay.com"). */
    private val DOOD = listOf("dood", "playmogo", "dsvplay", "d-s.io", "ds2play", "do0od", "doodstream", "myvidplay")
    private fun isDoodHost(u: String): Boolean = u.lowercase().let { l -> DOOD.any { it in l } }

    suspend fun extract(server: VideoServer, referer: String): ExtractResult {
        val extracted = if (server.variants.isNotEmpty()) extractVariants(server.variants, referer)
        else extractEmbed(server.embedUrl, referer)
        // Source-issued streams (Idlix's short-lived HLS is one example) can require the same
        // Referer/Origin/Cookie context that minted the URL. Source headers intentionally win.
        val merged = if (server.headers.isEmpty()) extracted
        else extracted.copy(headers = extracted.headers + server.headers)
        // DutaMovie's tabs are Sources, while each tab's HLS master can contain its own 480p/720p/etc.
        // Expand that ladder here so the app presents it under Resolusi instead of flattening qualities
        // into the Source picker. Hosts that expose JWPlayer qualities are handled by PlayerScreen.
        return if (
            DutamovieSource.isDutamovie(referer) ||
            IndoMax21Source.isIndoMax21(referer) ||
            JavHeySource.isJavHey(referer)
        ) expandHlsLadder(merged) else merged
    }

    /** Resolve a single embed-host iframe URL into direct stream variants + the headers its CDN needs. */
    private suspend fun extractEmbed(embedUrl: String, referer: String): ExtractResult = runCatching {
        val u = embedUrl
        System.out.println("[StreamExtractor] extractEmbed: $u")
        val result = when {
            // Already a direct, playable stream (kuramadrive's per-resolution .mp4) — pass it through.
            isDirectVideo(u) -> {
                // Wibufile serves .mp4 but needs the wibufile.com referer to play
                if ("wibufile.com" in u) {
                    ExtractResult(listOf(StreamVariant("Auto", u)), mapOf("Referer" to "https://www.wibufile.com/"))
                } else {
                    ExtractResult(listOf(StreamVariant("Auto", u)))
                }
            }
            "kotakanimeid.link/video-embed" in u -> kotakanime(u, referer)
            "ok.ru" in u || "odnoklassniki" in u -> ExtractResult(okru(u), originHeaders(u))
            // Dailymotion verifies the embedder: fetch metadata AS the real embedder (anixcafe) to get a
            // valid token, then play the manifest with the dailymotion.com referer (mimics the iframe).
            "dailymotion" in u -> dailymotion(u, referer).let {
                it.copy(headers = if (it.variants.isEmpty()) emptyMap()
                    else mapOf("Referer" to "https://www.dailymotion.com/", "Origin" to "https://www.dailymotion.com"))
            }
            "rumble.com" in u -> ExtractResult(rumble(u), originHeaders(u))
            "embedpyrox" in u || "pyrox" in u -> pyrox(u, referer)
            isByseHost(u) -> byse(u, referer)
            isVidStackHost(u) -> vidStack(u)
            "voe.sx" in u || "voe-network.net" in u -> voe(u, referer)
            isDoodHost(u) -> dood(u)
            "filedon" in u -> ExtractResult(filedon(u, referer)) // presigned R2 URL → no headers
            "yourupload" in u -> ExtractResult(yourupload(u), mapOf("Referer" to "https://www.yourupload.com/"))
            "mp4upload.com" in u -> ExtractResult(mp4upload(u), mapOf("Referer" to "https://www.mp4upload.com/"))
            "gofile.io" in u -> gofile(u)
            "mega.nz" in u -> ExtractResult(emptyList()) // Mega embed works via WebView (token-gated)
            "pixeldrain.com" in u -> ExtractResult(listOf(StreamVariant("Auto", pixeldrainDirect(u)))) // range-served file → no headers
            "desustream" in u -> desustream(u, referer) // googlevideo plays raw → no headers
            "hownetwork.xyz" in u -> hownetwork(u, referer)
            // Wibufile API embed (api.wibufile.com/embed/<uuid>): a JWPlayer whose inline `"file":"…mp4"`
            // is the SAME s0.wibufile.com .mp4 the "720p/1080p" direct rows expose — crack it so the
            // "480p" server plays in ExoPlayer instead of taking a WebView hop. Needs the wibufile referer.
            "wibufile.com" in u -> wibufile(u, referer)
            // Samehadaku common hosts: blogger (Google WIZ), 4meplayer (JWPlayer), videoplayer.vip (JWPlayer)
            "blogger.com" in u || "blogspot.com" in u -> ExtractResult(emptyList()) // Blogger WIZ is JS-only, no static stream → WebView
            "4meplayer" in u -> ExtractResult(emptyList()) // JWPlayer with betting ads → WebView with controls
            "videoplayer.vip" in u -> ExtractResult(emptyList()) // JWPlayer → WebPlayerStage (already handled in PlayerScreen)
            else -> generic(u, referer).let { it.copy(headers = if (it.variants.isEmpty()) emptyMap() else originHeaders(u)) }
        }
        System.out.println("[StreamExtractor] extractEmbed result: ${result.variants.size} variants")
        result
    }.getOrElse { ExtractResult(emptyList()) }

    /**
     * Otakudesu-style source: each [variants] entry is the SAME host at a different resolution (e.g.
     * vidhide 360p/480p/720p, each its own iframe), so resolve them in parallel and relabel each with
     * the site's resolution — that becomes the player's Resolusi picker. One host throughout, so one
     * headers map (the first non-empty). Presented high→low (the order [OtakudesuSource] built).
     */
    private suspend fun extractVariants(variants: List<ServerVariant>, referer: String): ExtractResult = coroutineScope {
        val resolved = variants.map { v -> async { v to extractEmbed(v.embedUrl, referer) } }.awaitAll()
        val out = ArrayList<StreamVariant>()
        var headers: Map<String, String> = emptyMap()
        for ((v, res) in resolved) {
            val stream = res.variants.firstOrNull() ?: continue
            if (headers.isEmpty()) headers = res.headers
            out.add(StreamVariant(v.label, stream.url))
        }
        ExtractResult(out, headers)
    }

    private fun originHeaders(embedUrl: String): Map<String, String> {
        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: return emptyMap()
        return mapOf("Referer" to "$origin/", "Origin" to origin)
    }

    /** DutaMovie's dm21.upns/embed4me/playerp2p mirrors all run the same VidStack API. The fragment
     *  is the video id; `/api/v1/video` returns hex AES-CBC JSON containing the short-lived HLS URL. */
    private fun isVidStackHost(url: String): Boolean {
        val low = url.lowercase()
        return "upns.live" in low || "embed4me.vip" in low || "playerp2p.online" in low ||
            "4meplayer.com" in low || "p2pplay.pro" in low ||
            "seekplays.pro" in low ||
            "streamcasthub" in low || "server1.uns.bio" in low
    }

    private fun isByseHost(url: String): Boolean {
        val low = url.lowercase()
        return "byse.sx" in low || "bysebuho" in low || "bysezejataos" in low ||
            "bysevepoin" in low || Regex("https?://[^/]*byse[^/]*/").containsMatchIn(low)
    }

    /** JavHey's Byse mirrors expose an AES-GCM encrypted playback descriptor through two JSON APIs. */
    private suspend fun byse(embedUrl: String, parentReferer: String): ExtractResult {
        fun origin(url: String): String? = runCatching {
            java.net.URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrNull()
        fun code(url: String): String? = Regex("/(?:e|v|d)/([a-zA-Z0-9]+)")
            .find(url)?.groupValues?.getOrNull(1)
            ?: runCatching { java.net.URI(url).path.trim('/').substringAfterLast('/').takeIf(String::isNotBlank) }
                .getOrNull()
        fun decodeUrlBase64(raw: String): ByteArray? = runCatching {
            val fixed = raw.replace('-', '+').replace('_', '/')
            val padded = fixed + "=".repeat((4 - fixed.length % 4) % 4)
            Base64.getDecoder().decode(padded)
        }.getOrNull()

        val firstOrigin = origin(embedUrl) ?: return ExtractResult(emptyList())
        val firstCode = code(embedUrl) ?: return ExtractResult(emptyList())
        val detailsText = LiveClient.requestText("$firstOrigin/api/videos/$firstCode/embed/details")
            ?: return ExtractResult(emptyList())
        val frameUrl = runCatching { JSONObject(detailsText).optString("embed_frame_url") }
            .getOrNull()?.takeIf { it.startsWith("http") } ?: return ExtractResult(emptyList())
        val frameOrigin = origin(frameUrl) ?: return ExtractResult(emptyList())
        val frameCode = code(frameUrl) ?: return ExtractResult(emptyList())
        val playbackText = LiveClient.requestText(
            "$frameOrigin/api/videos/$frameCode/embed/playback",
            headers = mapOf(
                "Accept" to "*/*",
                "Referer" to frameUrl,
                "x-embed-parent" to parentReferer,
            ),
        ) ?: return ExtractResult(emptyList())
        val playback = runCatching { JSONObject(playbackText).optJSONObject("playback") }
            .getOrNull() ?: return ExtractResult(emptyList())
        val parts = playback.optJSONArray("key_parts") ?: return ExtractResult(emptyList())
        if (parts.length() < 2) return ExtractResult(emptyList())
        val key = (decodeUrlBase64(parts.optString(0)) ?: return ExtractResult(emptyList())) +
            (decodeUrlBase64(parts.optString(1)) ?: return ExtractResult(emptyList()))
        val iv = decodeUrlBase64(playback.optString("iv")) ?: return ExtractResult(emptyList())
        val payload = decodeUrlBase64(playback.optString("payload")) ?: return ExtractResult(emptyList())
        val clear = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                String(doFinal(payload), Charsets.UTF_8).removePrefix("\uFEFF")
            }
        }.getOrNull() ?: return ExtractResult(emptyList())
        val sources = runCatching { JSONObject(clear).optJSONArray("sources") }.getOrNull()
            ?: return ExtractResult(emptyList())
        val stream = (0 until sources.length()).asSequence()
            .mapNotNull { sources.optJSONObject(it)?.optString("url") }
            .firstOrNull { it.startsWith("http") } ?: return ExtractResult(emptyList())
        return ExtractResult(
            listOf(StreamVariant("Auto", stream.replace("\\/", "/"))),
            mapOf("Referer" to "$firstOrigin/", "Origin" to firstOrigin),
        )
    }

    /** IndoMax21 Pyrox embeds resolve through the player's same-origin XHR endpoint. */
    private suspend fun pyrox(embedUrl: String, parentReferer: String): ExtractResult {
        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
            ?: return ExtractResult(emptyList())
        val id = embedUrl.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
            ?: return ExtractResult(emptyList())
        val form = "hash=${java.net.URLEncoder.encode(id, "UTF-8")}" +
            "&r=${java.net.URLEncoder.encode(parentReferer, "UTF-8")}"
        val response = LiveClient.postBypass(
            "$origin/player/index.php?data=${java.net.URLEncoder.encode(id, "UTF-8")}&do=getVideo",
            form,
            referer = embedUrl,
            origin = origin,
            isValid = { ".m3u8" in it || ".mp4" in it || ".txt" in it },
        ) ?: return ExtractResult(emptyList())
        val normalized = response.replace("\\/", "/")
        val stream = Regex("""https?://[^"'\s]+\.(?:m3u8|mp4|txt)[^"'\s]*""", RegexOption.IGNORE_CASE)
            .find(normalized)?.value ?: return ExtractResult(emptyList())
        return ExtractResult(listOf(StreamVariant("Auto", stream)), mapOf("Referer" to embedUrl, "Origin" to origin))
    }

    private suspend fun vidStack(embedUrl: String): ExtractResult {
        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
            ?: return ExtractResult(emptyList())
        val id = embedUrl.substringAfterLast('#').substringAfterLast('/').substringBefore('&').trim()
        if (id.isBlank() || id == embedUrl) return ExtractResult(emptyList())
        val encrypted = fetch("$origin/api/v1/video?id=${java.net.URLEncoder.encode(id, "UTF-8")}", "$origin/")
            ?.trim()?.takeIf { it.length >= 32 && it.length % 2 == 0 }
            ?: return ExtractResult(emptyList())
        val decrypted = listOf("1234567890oiuytr", "0123456789abcdef").firstNotNullOfOrNull { iv ->
            runCatching { decryptHexAesCbc(encrypted, "kiemtienmua911ca", iv) }.getOrNull()
        } ?: return ExtractResult(emptyList())
        val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: return ExtractResult(emptyList())
        // VidStack's `cfNative` master currently expands to absolute segment URLs on a generated
        // technologysystems.space host that has no usable DNS/certificate on Android. The origin
        // `source` is healthy, but is published as HTTPS on a raw IP with a mismatched certificate.
        // That same origin explicitly serves the complete HLS ladder over HTTP (manifest, init and
        // media segments all 200), so use that narrowly-scoped cleartext form instead of failing Exo
        // after the master and silently falling through to JavHey's captcha WebView mirror.
        val originSource = json.optString("source").replace("\\/", "/")
        val rawIpHttps = Regex("^https://(?:\\d{1,3}\\.){3}\\d{1,3}/", RegexOption.IGNORE_CASE)
        val playableOrigin = originSource.takeIf { rawIpHttps.containsMatchIn(it) }
            ?.replaceFirst("https://", "http://", ignoreCase = true)
        val source = sequenceOf(playableOrigin, json.optString("cfNative"), json.optString("cf"), originSource)
            .filterNotNull()
            .firstOrNull { it.startsWith("http") } ?: return ExtractResult(emptyList())
        val subtitles = buildList {
            json.optJSONObject("subtitle")?.let { sub ->
                sub.keys().forEach { language ->
                    val raw = sub.optString(language).substringBefore('#').replace("\\/", "/")
                    if (raw.isNotBlank()) add(SubtitleTrack(language, absoluteUrl(origin, raw), language))
                }
            }
        }
        System.out.println("[StreamExtractor] VidStack $id -> HLS, subtitles=${subtitles.size}")
        return ExtractResult(
            variants = listOf(StreamVariant("Auto", source.replace("\\/", "/"))),
            headers = mapOf("Referer" to "$origin/", "Origin" to origin),
            subtitles = subtitles,
        )
    }

    private fun decryptHexAesCbc(input: String, key: String, iv: String): String {
        val bytes = ByteArray(input.length / 2) { index -> input.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(bytes), Charsets.UTF_8)
    }

    /** Turn a single adaptive HLS master into explicit quality URLs for TetoNova's Resolusi picker. */
    private suspend fun expandHlsLadder(result: ExtractResult): ExtractResult {
        val stream = result.variants.singleOrNull() ?: return result
        if (".m3u8" !in stream.url.lowercase()) return result
        val manifest = fetchWithHeaders(stream.url, result.headers)
            ?.takeIf { it.trimStart().startsWith("#EXTM3U") && "#EXT-X-STREAM-INF" in it }
            ?: return result
        data class HlsRendition(val height: Int, val bandwidth: Long, val url: String)
        val lines = manifest.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val renditions = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) return@forEachIndexed
                val height = Regex("(?i)RESOLUTION=\\d+x(\\d+)").find(line)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return@forEachIndexed
                val bandwidth = Regex("(?i)(?:AVERAGE-)?BANDWIDTH=(\\d+)").find(line)
                    ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val uri = lines.drop(index + 1).firstOrNull { !it.startsWith('#') }
                    ?: return@forEachIndexed
                add(HlsRendition(height, bandwidth, absoluteUrl(stream.url, uri)))
            }
        }
        val variants = renditions
            .groupBy { it.height }
            .mapNotNull { (height, choices) ->
                choices.maxByOrNull { it.bandwidth }?.let { StreamVariant("${height}p", it.url) }
            }
            .sortedByDescending { it.label.removeSuffix("p").toIntOrNull() ?: 0 }
        if (variants.size < 2) return result
        System.out.println("[StreamExtractor] HLS ladder -> ${variants.map { it.label }}")
        return result.copy(variants = variants)
    }

    private suspend fun fetchWithHeaders(url: String, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", UA).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            http.newCall(request).execute().use { response ->
                response.body?.string().takeIf { response.isSuccessful }
            }
        }.getOrNull()
    }

    /** Current VOE embeds wrap their JSON through rot13 → token substitutions → base64 → char shift
     *  → reverse → base64. Ported into the native resolver so VOE stays inside TetoNova's ExoPlayer. */
    private suspend fun voe(embedUrl: String, referer: String): ExtractResult {
        var html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: return ExtractResult(emptyList())
        Regex("window\\.location\\.href\\s*=\\s*'([^']+)'").find(html)?.groupValues?.get(1)?.let { redirect ->
            html = fetch(absoluteUrl(embedUrl, redirect), referer.ifBlank { embedUrl }) ?: html
        }
        val encoded = Jsoup.parse(html, embedUrl).selectFirst("script[type=application/json]")?.data()?.trim()
            ?.substringAfter("[\"")?.substringBeforeLast("\"]") ?: return ExtractResult(emptyList())
        val json = runCatching {
            var value = encoded.map { char ->
                when (char) {
                    in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
                    in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
                    else -> char
                }
            }.joinToString("")
            listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").forEach { value = value.replace(it, "_") }
            value = value.replace("_", "")
            value = String(Base64.getDecoder().decode(value), Charsets.UTF_8)
                .map { (it.code - 3).toChar() }.joinToString("").reversed()
            JSONObject(String(Base64.getDecoder().decode(value), Charsets.UTF_8))
        }.getOrNull() ?: return ExtractResult(emptyList())
        val variants = buildList {
            json.optString("source").takeIf { it.startsWith("http") }?.let { add(StreamVariant("HLS", it)) }
            json.optString("direct_access_url").takeIf { it.startsWith("http") }?.let { add(StreamVariant("MP4", it)) }
        }
        return ExtractResult(variants, originHeaders(embedUrl))
    }

    /** GoFile's current API requires a disposable account token plus the website token from config.js. */
    private suspend fun gofile(embedUrl: String): ExtractResult = withContext(Dispatchers.IO) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(embedUrl)?.groupValues?.get(1)
            ?: return@withContext ExtractResult(emptyList())
        fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): String? = runCatching {
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (method == "POST") builder.post(FormBody.Builder().build()) else builder.get()
            http.newCall(builder.build()).execute().use { it.body?.string() }
        }.getOrNull()
        val account = request("https://api.gofile.io/accounts", "POST") ?: return@withContext ExtractResult(emptyList())
        val token = runCatching { JSONObject(account).optJSONObject("data")?.optString("token") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return@withContext ExtractResult(emptyList())
        val config = request("https://gofile.io/dist/js/config.js") ?: return@withContext ExtractResult(emptyList())
        val websiteToken = Regex("appdata\\.wt\\s*=\\s*[\"']([^\"']+)").find(config)?.groupValues?.get(1)
            ?: return@withContext ExtractResult(emptyList())
        val jsonText = request(
            "https://api.gofile.io/contents/$id?contentFilter=&page=1&pageSize=1000&sortField=name&sortDirection=1",
            headers = mapOf("Authorization" to "Bearer $token", "X-Website-Token" to websiteToken),
        ) ?: return@withContext ExtractResult(emptyList())
        val children = runCatching { JSONObject(jsonText).optJSONObject("data")?.optJSONObject("children") }.getOrNull()
            ?: return@withContext ExtractResult(emptyList())
        val variants = children.keys().asSequence().mapNotNull { key ->
            val file = children.optJSONObject(key) ?: return@mapNotNull null
            if (file.optString("type") != "file") return@mapNotNull null
            val link = file.optString("link").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val name = file.optString("name")
            val label = Regex("(\\d{3,4})[pP]").find(name)?.groupValues?.get(1)?.let { "${it}p" }
                ?: name.take(24).ifBlank { "Auto" }
            StreamVariant(label, link)
        }.toList()
        ExtractResult(variants, mapOf("Cookie" to "accountToken=$token"))
    }

    private fun absoluteUrl(base: String, value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        else -> runCatching { java.net.URI(base).resolve(value).toString() }.getOrDefault(value)
    }

    private suspend fun hownetwork(embedUrl: String, referer: String): ExtractResult = withContext(Dispatchers.IO) {
        val id = Regex("[?&]id=([^&#]+)").find(embedUrl)?.groupValues?.get(1) ?: return@withContext ExtractResult(emptyList())
        val origin = "https://cloud.hownetwork.xyz"
        val body = FormBody.Builder()
            .add("r", referer.ifBlank { "https://playeriframe.sbs/" })
            .add("d", "cloud.hownetwork.xyz")
            .build()
        val req = Request.Builder()
            .url("$origin/api2.php?id=${java.net.URLEncoder.encode(id, "UTF-8")}")
            .header("User-Agent", UA)
            .header("Referer", embedUrl)
            .post(body)
            .build()
        val json = runCatching { http.newCall(req).execute().use { it.body?.string() } }.getOrNull()
            ?: return@withContext ExtractResult(emptyList())
        val file = runCatching { JSONObject(json).optString("file") }.getOrNull()
            ?.takeIf { it.startsWith("http") }
            ?: return@withContext ExtractResult(emptyList())
        ExtractResult(listOf(StreamVariant("Auto", file)), mapOf("Referer" to "$origin/", "Origin" to origin))
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
        // Most embeds inline the metadata JSON, but some only give a `metadataUrl` that must be POSTed for
        // it — those used to bail here with NO resolutions. Fetch it so their qualities show up too.
        val metaStr = flash.optString("metadata").takeIf { it.isNotBlank() }
            ?: flash.optString("metadataUrl").takeIf { it.isNotBlank() }
                ?.let { postForOkMetadata(absoluteUrl(embedUrl, it), embedUrl) }
            ?: return emptyList()
        val videos = JSONObject(metaStr).optJSONArray("videos") ?: return emptyList()
        val out = ArrayList<StreamVariant>()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            val url = v.optString("url")
            if (url.startsWith("http")) out.add(StreamVariant(okLabel(v.optString("name")), url))
        }
        return out.asReversed() // OK.ru lists low→high; present high→low
    }

    // ---- mp4upload.com/<id> (or /embed-<id>.html): the embed page's video.js `player.src({src:"…mp4"})`
    //      is a direct a*.mp4upload.com mp4 (token in the path). One quality per id; anoboy lists
    //      240/360/480/720/1080 as separate ids in its Download section. Needs the mp4upload referer. ----
    private suspend fun mp4upload(url: String): List<StreamVariant> {
        val id = Regex("mp4upload\\.com/(?:embed-)?([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: return emptyList()
        val html = fetch("https://www.mp4upload.com/embed-$id.html", "https://www.mp4upload.com/") ?: return emptyList()
        val file = Regex("""src\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(StreamVariant("Auto", file))
    }

    // ---- yourupload.com/embed/<id>: jwplayer with an inline `file: '…video.mp4'` (a vidcache.net CDN
    //      URL, token in the path). One quality per embed; anoboy exposes 240/360/480/720 as separate ids. ----
    private suspend fun yourupload(embedUrl: String): List<StreamVariant> {
        val html = fetch(embedUrl, "https://www.yourupload.com/") ?: return emptyList()
        val file = Regex("""file\s*:\s*['"](https?://[^'"]+\.mp4[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        return listOf(StreamVariant("Auto", file))
    }

    /** ok.ru serves the player metadata via a POST to `metadataUrl` when it isn't inlined. */
    private suspend fun postForOkMetadata(metadataUrl: String, referer: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(metadataUrl)
                .header("User-Agent", UA).header("Referer", referer)
                .post(FormBody.Builder().build())
                .build()
            http.newCall(req).execute().use { it.body?.string() }
        }.getOrNull()?.takeIf { it.trimStart().startsWith("{") }
    }

    private fun okLabel(name: String) = when (name) {
        "mobile" -> "144p"; "lowest" -> "240p"; "low" -> "360p"
        "sd" -> "480p"; "hd" -> "720p"; "full" -> "1080p"; "quad" -> "1440p"; "ultra" -> "2160p"; else -> name
    }

    // ---- Dailymotion: metadata API → qualities.auto[] HLS master (ExoPlayer adapts) ----
    private suspend fun dailymotion(embedUrl: String, referer: String): ExtractResult {
        val id = Regex("[?&]video=([^&]+)").find(embedUrl)?.groupValues?.get(1)
            ?: Regex("dailymotion\\.com/(?:embed/)?video/([^_?&/]+)").find(embedUrl)?.groupValues?.get(1)
            ?: return ExtractResult(emptyList())
        // Fetch metadata AS the embedder (anixcafe) so Dailymotion authorizes a playable token.
        val json = fetch("https://www.dailymotion.com/player/metadata/video/$id", referer.ifBlank { "https://www.dailymotion.com/" })
            ?: return ExtractResult(emptyList())
        val root = JSONObject(json)
        // Dailymotion answers 200 even for a dead video and puts the real status in `error`. Two shapes
        // seen in the wild: a takedown (`code` DM005, `status_code` 410) and a hard miss (`code` "404",
        // `error_data.reason` object_not_found). Both mean no stream will ever appear, so report it as
        // gone rather than letting the player burn a sniff on it. Other errors (geo-block, password)
        // are NOT terminal — leave those to the sniff.
        root.optJSONObject("error")?.let { err ->
            val status = err.optInt("status_code", err.optString("code").toIntOrNull() ?: 0)
            val reason = err.optJSONObject("error_data")?.optString("reason").orEmpty()
            if (status == 404 || status == 410 || reason == "object_not_found") {
                return ExtractResult(emptyList(), gone = true)
            }
        }
        val auto = root.optJSONObject("qualities")?.optJSONArray("auto") ?: return ExtractResult(emptyList())
        for (i in 0 until auto.length()) {
            val url = auto.getJSONObject(i).optString("url")
            if (url.contains("m3u8")) return ExtractResult(listOf(StreamVariant("Auto", url)))
        }
        return ExtractResult(emptyList())
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
        // A deleted dood file still serves a 200 embed page — it just has no /pass_md5 and says so in
        // the body (the d0o0d → playmogo migration keeps that shape). Distinguish "gone" from "we could
        // not parse it" so the player doesn't sniff a file that isn't there.
        val pass = Regex("/pass_md5/[^\"'\\s]+").find(html)?.value
            ?: return@withContext ExtractResult(emptyList(), gone = looksGone(html))
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

    // ---- Generic Filemoon-family: unpack packed JS → m3u8 (filelions/streamwish/odvidhide/short.ink/…) ----
    private suspend fun generic(embedUrl: String, referer: String): ExtractResult {
        // Direct first (with the embedder referer many hosts require); if that host is Internet-Positif
        // blocked the direct fetch fails → fall back to the flare-capable client (odvidhide etc.).
        val html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: LiveClient.getHtml(embedUrl)
            ?: return ExtractResult(emptyList())
        val m3u8 = findStream(html)
            // No stream found: this is either a host we simply can't parse (→ let the sniff try) or a
            // file-host error page saying the file is gone (→ nothing to sniff). Only the latter is
            // reported as gone, on an explicit phrase — see GONE_MARKERS.
            ?: return ExtractResult(emptyList(), gone = looksGone(html))
        return ExtractResult(listOf(StreamVariant("Auto", m3u8)))
    }

    // ---- pixeldrain: the share URL (/u/<id>) maps to the direct file endpoint (/api/file/<id>),
    //      which is range-served (HTTP 206) with no auth/headers — ExoPlayer streams it straight. ----
    private fun pixeldrainDirect(url: String): String {
        val id = Regex("pixeldrain\\.com/(?:u|api/file)/([^/?#]+)").find(url)?.groupValues?.get(1) ?: return url
        return "https://pixeldrain.com/api/file/$id"
    }

    // ---- filedon.co: historically a presigned Cloudflare-R2 .mp4/.mkv in a JSON `"url":"…"` field
    //      (still used by oploverz — entity-escaped `&quot;`, `\/`-escaped, self-authenticating query).
    //      Newer files (samehadaku) are a Laravel SPA that streams `media/<slug>/hls.m3u8` instead; that
    //      route only exists once the server-side transcode is done (`hls_status` flips from "pending"),
    //      so we probe it and only return it when it's actually live — otherwise empty → WebView. ----
    private suspend fun filedon(embedUrl: String, referer: String): List<StreamVariant> {
        val html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: LiveClient.getHtml(embedUrl) ?: return emptyList()
        val text = org.jsoup.parser.Parser.unescapeEntities(html, false)
        // Filedon serves the file in its native container — often `.mkv` (e.g. oploverz 1080p), not just
        // `.mp4`. Media3 plays Matroska/WebM directly, so accept those too instead of dropping to the
        // ad-laden Filedon web player.
        Regex(""""(https?:[^"]+?\.(?:mp4|mkv|webm)[^"]*)"""").find(text)?.groupValues?.get(1)?.let {
            return listOf(StreamVariant("Auto", it.replace("\\/", "/")))
        }
        // No inline direct file → try the HLS master for this embed slug, but only if it's transcoded.
        val slug = Regex("filedon\\.co/embed/([^/?#]+)").find(embedUrl)?.groupValues?.get(1) ?: return emptyList()
        val hls = "https://filedon.co/media/$slug/hls.m3u8"
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(Request.Builder().url(hls).header("User-Agent", UA).header("Referer", "https://filedon.co/").build())
                    .execute().use { it.isSuccessful && (it.header("Content-Type")?.contains("mpegurl", true) == true || it.code == 200) }
            }.getOrDefault(false)
        }
        return if (ok) listOf(StreamVariant("Auto", hls)) else emptyList()
    }

    // ---- api.wibufile.com/embed/<uuid>: a JWPlayer whose inline `"file":"https://s0.wibufile.com/…mp4"`
    //      is a direct progressive .mp4 (the exact file the "720p/1080p" rows link straight to). Media3
    //      streams it with the wibufile.com Referer; without the extractor this fell back to a WebView. ----
    private suspend fun wibufile(embedUrl: String, referer: String): ExtractResult {
        val html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: LiveClient.getHtml(embedUrl)
            ?: return ExtractResult(emptyList())
        val text = org.jsoup.parser.Parser.unescapeEntities(html, false)
        val file = Regex(""""file"\s*:\s*"([^"]+?\.(?:mp4|m3u8)[^"]*)"""").find(text)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return ExtractResult(emptyList()) // no inline file → WebPlayerStage handles it with referer
        return ExtractResult(listOf(StreamVariant("Auto", file)), mapOf("Referer" to "https://www.wibufile.com/"))
    }

    // ---- desustream.info: otakudesu's own player. The `ondesu/new/hd` player serves a direct
    //      <source googlevideo mp4>. The `updesu/v5` player wraps a Blogger video.g iframe that's now a
    //      JS-only Google WIZ app (no static stream) — but the SAME desustream id IS served directly by
    //      the ondesu player, so we rewrite the path to it instead of trying to crack Blogger. ----
    private suspend fun desustream(embedUrl: String, referer: String): ExtractResult {
        val html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: LiveClient.getHtml(embedUrl)
        html?.let { googleVideoFrom(it) }?.let { return ExtractResult(listOf(StreamVariant("Auto", it))) }
        // No direct <source> (updesu/Blogger) → borrow the ondesu/new/hd player for the same id.
        val alt = embedUrl.replace(Regex("""/dstream/[^/]+/(?:[^/?]+/)*index\.php"""), "/dstream/ondesu/new/hd/index.php")
        if (alt != embedUrl) {
            val altHtml = fetch(alt, referer.ifBlank { alt }) ?: LiveClient.getHtml(alt)
            altHtml?.let { googleVideoFrom(it) }?.let { return ExtractResult(listOf(StreamVariant("Auto", it))) }
        }
        return ExtractResult(emptyList())
    }

    // ---- NontonAnimeID native player (s1/s2.kotakanimeid.link/video-embed): the embed inlines a
    //      `(function(){var KEY=[…];var CT=atob("…");/* plain[i]=CT[i]^KEY[i%len] */ (0,eval)(decoded)})()`
    //      — a repeating-XOR (NOT AES; the key sits inline next to the ciphertext, name randomized per
    //      embed). The decoded JS is a jwplayer.setup whose `file` is the real stream: a direct
    //      googlevideo mp4, a kotakanimeid .mp4, or an HLS hop `…/go/dl/?url=<b64>` that 302s to a
    //      master m3u8 (followed automatically). googlevideo plays raw (a Referer makes it 403); the
    //      kotakanimeid CDN files want the embed host's Referer/Origin. ----
    private suspend fun kotakanime(embedUrl: String, referer: String): ExtractResult {
        val html = fetch(embedUrl, referer.ifBlank { embedUrl }) ?: LiveClient.getHtml(embedUrl)
            ?: return ExtractResult(emptyList())
        val m = Regex("""var\s+\w+=\[([\d,]+)];\s*var\s+\w+=atob\("([^"]+)"\)""").find(html)
            ?: return ExtractResult(emptyList())
        val key = m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() }
        val ct = runCatching { Base64.getMimeDecoder().decode(m.groupValues[2]) }.getOrNull()
        if (key.isEmpty() || ct == null) return ExtractResult(emptyList())
        val decoded = String(ByteArray(ct.size) { ((ct[it].toInt() and 0xFF) xor key[it % key.size]).toByte() })
        val file = Regex(""""file":"([^"]+)"""").find(decoded)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: return ExtractResult(emptyList())
        val label = Regex(""""label":"([^"]+)"""").find(decoded)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "Auto"
        val headers = if ("googlevideo.com" in file) emptyMap() else originHeaders(embedUrl)
        return ExtractResult(listOf(StreamVariant(label, file)), headers)
    }

    private fun googleVideoFrom(html: String): String? =
        Regex("""<source[^>]+src=["']([^"']*googlevideo\.com[^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""https?://[^"'\s\\]+googlevideo\.com/videoplayback[^"'\s\\]*""").find(html)?.value

    private fun findStream(html: String): String? {
        val text = (unpack(html) ?: "") + "\n" + html
        System.out.println("[StreamExtractor] findStream: looking in ${text.length} chars")
        Regex("https?://[^\"'\\\\\\s]+\\.m3u8[^\"'\\\\\\s]*").find(text)?.let {
            System.out.println("[StreamExtractor] Found m3u8: ${it.value.take(80)}")
            return it.value
        }
        Regex("file\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?.takeIf { it.contains(".m3u8") || it.contains(".mp4") }?.let {
                System.out.println("[StreamExtractor] Found file: $it")
                return it
            }
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
