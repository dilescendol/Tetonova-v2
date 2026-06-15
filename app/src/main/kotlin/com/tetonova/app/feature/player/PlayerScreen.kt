package com.tetonova.app.feature.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tetonova.app.data.ExtractResult
import com.tetonova.app.data.LiveSource
import com.tetonova.app.data.StreamExtractor
import com.tetonova.app.data.StreamVariant
import com.tetonova.app.data.VideoServer
import com.tetonova.app.ui.PlayerArg
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app player. Servers are scraped live from the episode's watch page (per-episode); the chosen
 * server is resolved by [StreamExtractor] into direct stream variants and played in the app's own
 * ExoPlayer (consistent controls + Resolusi picker). Hosts without an extractor fall back to a
 * WebView embed. Top bar (back · title · Source) is the same regardless of source.
 */
@Composable
fun PlayerScreen(arg: PlayerArg, onBack: () -> Unit) {
    val context = LocalContext.current
    val referer = arg.url.orEmpty()

    // Landscape + immersive (hide status/nav bars) while playing. MainActivity has
    // configChanges=orientation|screenSize so this does NOT recreate the activity. Restore on exit.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var loading by remember { mutableStateOf(true) }
    var servers by remember { mutableStateOf<List<VideoServer>>(emptyList()) }
    var selected by remember { mutableStateOf<VideoServer?>(null) }

    LaunchedEffect(arg.url) {
        loading = true
        // Show every server except dead file/gated hosts; each is resolved static → sniff → WebView.
        val list = arg.url?.let { runCatching { LiveSource.servers(it) }.getOrNull() }.orEmpty()
            .filter { StreamExtractor.isPlayable(it.embedUrl) }
        servers = list
        selected = pickRecommended(list)
        loading = false
    }

    fun openExternal() {
        if (arg.url.isNullOrBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(arg.url))) }
    }

    val current = selected
    when {
        loading -> LoadingBox("Mencari source…")
        current == null -> NoSourceBox(::openExternal, onBack)
        else -> ServerPlayer(current, arg, servers, referer, { selected = it }, onBack, ::openExternal)
    }
}

/** Resolves the chosen server then plays it: our ExoPlayer when variants extract, WebView otherwise. */
@Composable
private fun ServerPlayer(
    server: VideoServer,
    arg: PlayerArg,
    servers: List<VideoServer>,
    referer: String,
    onPickServer: (VideoServer) -> Unit,
    onBack: () -> Unit,
    onExternal: () -> Unit,
) {
    var phase by remember(server) { mutableStateOf<Phase>(Phase.Extracting) }
    LaunchedEffect(server) {
        // 1) static extractor (ok.ru/dailymotion/rumble/filemoon) → 2) WebView sniffer → 3) WebView embed.
        val res = runCatching { StreamExtractor.extract(server, referer) }.getOrDefault(ExtractResult(emptyList()))
        phase = if (res.variants.isNotEmpty()) Phase.Exo(res.variants, res.headers) else Phase.Sniffing
    }
    when (val p = phase) {
        Phase.Extracting -> LoadingBox("Menyiapkan video…")
        Phase.Sniffing -> SniffStage(
            embedUrl = server.embedUrl, referer = referer,
            onSniffed = { url, h -> phase = Phase.Exo(listOf(StreamVariant("Auto", url)), h) },
            onFail = { phase = Phase.Web },
        )
        is Phase.Exo -> ExoStage(p.variants, p.headers, arg, server, servers, onPickServer, onBack, onExternal, onError = { phase = Phase.Web })
        Phase.Web -> WebStage(server, arg, servers, referer, onPickServer, onBack, onExternal)
    }
}

// ---------------------------------------------------------------------------------------------
// ExoPlayer stage — our consistent controls + Resolusi picker (for extracted direct streams).
// ---------------------------------------------------------------------------------------------

/** Resolution phase for a chosen server: extract → sniff → ExoPlayer / WebView. */
private sealed interface Phase {
    data object Extracting : Phase
    data object Sniffing : Phase
    data class Exo(val variants: List<StreamVariant>, val headers: Map<String, String>) : Phase
    data object Web : Phase
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val NUDGE_JS =
    "(function(){try{var v=document.querySelector('video');if(v){v.muted=true;v.play&&v.play();}" +
        "var c=document.elementFromPoint(innerWidth/2,innerHeight/2);if(c)c.click();" +
        "['#play','.play','.vjs-big-play-button','.play-button','#vplayer','.fp-ui','.jw-icon-display','.plyr__control--overlaid']" +
        ".forEach(function(s){var e=document.querySelector(s);if(e)e.click();});}catch(e){}})();"

private fun looksLikeStream(u: String): Boolean {
    val low = u.lowercase()
    val path = low.substringBefore('?')
    // Extensions OR path markers (videoplayer.vip serves HLS at /hls/<token> with no .m3u8 suffix).
    return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") ||
        ".m3u8" in low || "/hls/" in path || "/manifest" in path
}

/** True when the resolved URL is HLS (so ExoPlayer is told the MIME type when the URL lacks .m3u8). */
private fun isHls(u: String): Boolean {
    val low = u.lowercase()
    return ".m3u8" in low || "/hls/" in low.substringBefore('?') || "/manifest" in low.substringBefore('?')
}

/**
 * Loads the embed in an ATTACHED, full-size WebView (hidden behind an opaque overlay so its ads/UI
 * aren't shown) and watches its traffic for a `.m3u8`/`.mp4`. Off-screen/orphan WebViews don't run
 * media, so it must be in the tree; a play-nudge kicks Playerjs/VPlayer-style players that need a
 * click. First stream → [onSniffed]; timeout → [onFail] (caller drops to a plain WebView embed).
 */
@Composable
private fun SniffStage(embedUrl: String, referer: String, onSniffed: (String, Map<String, String>) -> Unit, onFail: () -> Unit) {
    val done = remember { AtomicBoolean(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(embedUrl) { delay(22_000); if (done.compareAndSet(false, true)) onFail() }
    LaunchedEffect(web) {
        val wv = web ?: return@LaunchedEffect
        repeat(10) { delay(1500); if (done.get()) return@LaunchedEffect; wv.evaluateJavascript(NUDGE_JS, null) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = DESKTOP_UA
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url.toString()
                            if (looksLikeStream(u) && done.compareAndSet(false, true)) {
                                val h = HashMap(request.requestHeaders).apply { putIfAbsent("Referer", referer) }
                                Handler(Looper.getMainLooper()).post { onSniffed(u, h) }
                            }
                            return null
                        }
                        override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(NUDGE_JS, null) }
                    }
                    loadUrl(embedUrl, mapOf("Referer" to referer))
                }
            },
            update = { web = it },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        // Opaque overlay so the embed's ad/UI stays hidden while sniffing.
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(14.dp))
                Text("Menyiapkan video…", color = Color.White.copy(0.85f), fontSize = 13.sp)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoStage(
    variants: List<StreamVariant>,
    headers: Map<String, String>,
    arg: PlayerArg,
    server: VideoServer,
    servers: List<VideoServer>,
    onPickServer: (VideoServer) -> Unit,
    onBack: () -> Unit,
    onExternal: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    var quality by remember(variants) { mutableStateOf(variants.first()) } // first = highest
    val exo = remember {
        // Use exactly the headers the resolver said this stream needs (extractor per-host, or the
        // sniffer's captured request headers). Empty = none (Dailymotion's CDN 403s on Referer/Origin).
        val props = headers
        val ua = props.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(props.filterKeys { !it.equals("User-Agent", true) })
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().apply { playWhenReady = true }
    }

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) duration = exo.duration.coerceAtLeast(0L)
            }
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { onError() }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    // (Re)load when the chosen quality changes, preserving position.
    LaunchedEffect(quality) {
        val pos = exo.currentPosition
        val item = MediaItem.Builder().setUri(quality.url).apply {
            if (isHls(quality.url)) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
        }.build()
        exo.setMediaItem(item)
        exo.prepare()
        if (pos > 0) exo.seekTo(pos)
    }
    LaunchedEffect(Unit) {
        while (true) {
            position = exo.currentPosition.coerceAtLeast(0L)
            val d = exo.duration; if (d > 0L) duration = d
            delay(500)
        }
    }
    LaunchedEffect(controls, playing) { if (controls && playing) { delay(4000); controls = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exo; useController = false; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls },
                    onDoubleTap = { off ->
                        val w = size.width
                        when {
                            off.x < w / 3f -> exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                            off.x > w * 2f / 3f -> exo.seekTo(exo.currentPosition + 10_000)
                            else -> exo.playWhenReady = !exo.playWhenReady
                        }
                        controls = true
                    },
                )
            },
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        // Manual OP/ED skip — no timestamp data for donghua, so a button shows in the likely
        // intro/ending windows and jumps ahead (intro +85s, ending → near the end).
        // Re-tappable through the first ~3 min (intro = anichin card + sponsor ad + OP, length varies),
        // each tap +85s; fine-tune with the seekbar / double-tap ±10s.
        val skipIntro = position in 5_000L..180_000L
        val skipEnding = duration > 0L && position > duration - 150_000L
        if (skipIntro || skipEnding) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 56.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White.copy(0.9f))
                    .clickable { if (skipIntro) exo.seekTo(position + 85_000L) else exo.seekTo((duration - 2_000L).coerceAtLeast(0L)) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(if (skipIntro) "Lewati Intro ⏭" else "Lewati Ending ⏭", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        if (controls) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.35f)))
            TopBar(arg.title, arg.episodeLabel, onBack) {
                SourcePill(servers, server, onPickServer, onExternal)
                Spacer(Modifier.width(8.dp))
                Pill("Resolusi", quality.label, variants, { it.label }, { it.label == quality.label }) { quality = it }
            }
            // center play / pause
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.White.copy(0.18f))
                    .clickable { exo.playWhenReady = !exo.playWhenReady },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp, 22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                    Box(Modifier.size(6.dp, 22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                } else Text("▶", color = Color.White, fontSize = 26.sp)
            }
            // seek bar
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtTime(position), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { f -> if (duration > 0) { val p = (f * duration).toLong(); position = p; exo.seekTo(p) } },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)),
                )
                Text(fmtTime(duration), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// WebView stage — fallback for hosts without an extractor (embed's own controls).
// ---------------------------------------------------------------------------------------------

@Composable
private fun WebStage(
    server: VideoServer,
    arg: PlayerArg,
    servers: List<VideoServer>,
    referer: String,
    onPickServer: (VideoServer) -> Unit,
    onBack: () -> Unit,
    onExternal: () -> Unit,
) {
    var barVisible by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(barVisible, fullscreen) { if (barVisible && !fullscreen) { delay(4000); barVisible = false } }
    LaunchedEffect(fullscreen) { if (fullscreen) barVisible = false }
    val allowHost = remember(server.embedUrl) {
        runCatching { coreDomain(java.net.URI(server.embedUrl).host.orEmpty()) }.getOrDefault("")
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(server.embedUrl) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webChromeClient = fullscreenChromeClient(ctx, { fullscreen = true }, { fullscreen = false })
                        webViewClient = playerWebViewClient(allowHost)
                        loadUrl(server.embedUrl, mapOf("Referer" to referer))
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!fullscreen) {
            if (barVisible) {
                TopBar(arg.title, arg.episodeLabel, onBack) { SourcePill(servers, server, onPickServer, onExternal) }
            } else {
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(36.dp).clickable { barVisible = true })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------

@Composable
private fun BoxScope.TopBar(title: String, episodeLabel: String?, onBack: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color.Black.copy(0.45f)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(0.15f)).clickable { onBack() }, contentAlignment = Alignment.Center) {
            Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            episodeLabel?.let { Text(it, color = Color.White.copy(0.7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

/** Source-server picker pill + dropdown (with an "open externally" tail). */
@Composable
private fun SourcePill(servers: List<VideoServer>, current: VideoServer, onPick: (VideoServer) -> Unit, onExternal: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillRow("Source", current.name) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            servers.forEach { s ->
                DropdownMenuItem(text = { Text(s.name + if (s.embedUrl == current.embedUrl) "  ✓" else "") }, onClick = { onPick(s); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Buka di player luar") }, onClick = { onExternal(); open = false })
        }
    }
}

/** Generic labelled picker pill (used for Resolusi). */
@Composable
private fun <T> Pill(label: String, value: String, items: List<T>, itemLabel: (T) -> String, selected: (T) -> Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillRow(label, value) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { it2 ->
                DropdownMenuItem(text = { Text(itemLabel(it2) + if (selected(it2)) "  ✓" else "") }, onClick = { onPick(it2); open = false })
            }
        }
    }
}

@Composable
private fun PillRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.15f)).clickable { onClick() }.padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, color = Color.White.copy(0.7f), fontSize = 10.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
        Text("▾", color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun LoadingBox(msg: String) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(14.dp))
            Text(msg, color = Color.White.copy(0.85f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun NoSourceBox(onExternal: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Nggak nemu source video buat episode ini", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { onExternal() }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text("Buka di player luar", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Kembali", color = Color.White.copy(0.7f), modifier = Modifier.clickable { onBack() }.padding(8.dp))
        }
    }
}

private fun fmtTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** WebChromeClient supporting the embed's HTML5 fullscreen so our overlay bar can hide. */
private fun fullscreenChromeClient(ctx: android.content.Context, onEnter: () -> Unit, onExit: () -> Unit) = object : WebChromeClient() {
    private var custom: android.view.View? = null
    private var cb: WebChromeClient.CustomViewCallback? = null
    private val decor get() = (ctx as? Activity)?.window?.decorView as? android.widget.FrameLayout
    override fun onShowCustomView(view: android.view.View, callback: WebChromeClient.CustomViewCallback) {
        if (custom != null) { onHideCustomView(); return }
        val parent = decor ?: return
        custom = view; cb = callback
        view.setBackgroundColor(android.graphics.Color.BLACK)
        parent.addView(view, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onEnter()
    }
    override fun onHideCustomView() {
        custom?.let { decor?.removeView(it) }
        custom = null; cb?.onCustomViewHidden(); cb = null
        onExit()
    }
}

/** Registrable-ish domain (last two labels) of a host, e.g. "geo.dailymotion.com" → "dailymotion.com". */
private fun coreDomain(host: String): String =
    host.split('.').let { if (it.size >= 2) it.takeLast(2).joinToString(".") else host }

/**
 * WebView client for embed playback: nudges autoplay, and blocks ad redirects by cancelling
 * top-level navigations that leave the embed host (ad-heavy mirrors fire a popunder/redirect on
 * click — we keep the user on the player). Stream requests are sub-resource/XHR, so unaffected.
 */
private fun playerWebViewClient(allowHost: String) = object : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host.orEmpty()
        if (request.isForMainFrame && allowHost.isNotEmpty() && !host.endsWith(allowHost)) return true // block
        return false
    }
    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');" +
                "if(v){v.muted=false;var p=v.play&&v.play();if(p&&p.catch){p.catch(function(){v.muted=true;v.play&&v.play();});}}" +
                "var b=document.querySelector('.vjs-big-play-button,.ytp-large-play-button,button[aria-label*=\"lay\" i],.play-button,.play');" +
                "if(b){b.click();}}catch(e){}})();",
            null,
        )
    }
}

/** Prefer a clean, high-quality server (ok.ru / dailymotion) over the ad-heavy mirrors. */
private fun pickRecommended(servers: List<VideoServer>): VideoServer? {
    val pref = listOf("ok.ru", "okru", "dailymotion")
    return servers.firstOrNull { s -> pref.any { s.name.lowercase().contains(it) } }
        ?: servers.firstOrNull { !it.name.contains("[Ads]", ignoreCase = true) }
        ?: servers.firstOrNull()
}
