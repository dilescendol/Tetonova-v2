package com.tetonova.app.feature.player

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.tetonova.app.BuildConfig
import com.tetonova.core.scraper.DutamovieSource
import com.tetonova.core.scraper.IndoMax21Source
import com.tetonova.core.scraper.JavHeySource
import com.tetonova.core.scraper.ExtractResult
import com.tetonova.core.scraper.LiveSource
import com.tetonova.core.scraper.ServerVariant
import com.tetonova.core.scraper.StreamExtractor
import com.tetonova.core.scraper.StreamVariant
import com.tetonova.core.scraper.SubtitleTrack
import com.tetonova.core.scraper.VideoServer
import com.tetonova.app.data.Heartbeat
import com.tetonova.app.data.SettingsStore
import com.tetonova.app.data.SkipResolver
import com.tetonova.app.data.SkipTimes
import com.tetonova.app.data.TnData
import com.tetonova.app.data.WatchProgressStore
import com.tetonova.app.data.WebViewGate
import com.tetonova.app.data.download.DownloadCenter
import com.tetonova.app.ui.PlayerArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Popup props for the player's dropdowns (Source / Resolusi).
 *
 * On Android TV the popup MUST be focusable, otherwise it never takes window focus and every D-pad press
 * falls through to the player's own key handler behind it — the menu opens but can't be navigated at all.
 * On touch we keep it non-focusable (the original behaviour) so the popup doesn't disturb the player's
 * immersive / controls-autohide state.
 */
@Composable
private fun playerPopupProperties(): PopupProperties {
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    return remember(isTv) { PopupProperties(focusable = isTv) }
}

private data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

private fun PlayerView.applyTetoNovaSubtitleStyle() {
    subtitleView?.apply {
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setBottomPaddingFraction(0.12f)
        setStyle(
            CaptionStyleCompat(
                AndroidColor.WHITE,
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                AndroidColor.BLACK,
                null,
            ),
        )
    }
}

@Suppress("DEPRECATION")
private fun playerSystemUiFlags(): Int =
    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

/**
 * In-app player. Servers are scraped live from the episode's watch page (per-episode); the chosen
 * server is resolved by [StreamExtractor] into direct stream variants and played in the app's own
 * ExoPlayer (consistent controls + Resolusi picker). Hosts without an extractor fall back to a
 * WebView embed. Top bar (back · title · Source) is the same regardless of source.
 */
@Composable
fun PlayerScreen(
    arg: PlayerArg,
    onBack: () -> Unit,
    hasNext: Boolean = false,
    onNext: () -> Unit = {},
    hasPrev: Boolean = false,
    onPrev: () -> Unit = {},
) {
    val context = LocalContext.current
    val referer = arg.url.orEmpty()
    // If this episode is already downloaded, play it straight from the offline cache (no network,
    // no server resolution) — the same ExoStage, just fed a cache-backed data source.
    val offlineVariant = remember(arg.url) { DownloadCenter.offlineVariant(arg.url) }

    // Portrait for vertical short-drama, landscape for everything else. Seeded from the source hint so
    // the lock is right before the first frame, then confirmed/corrected from the real video aspect
    // ratio (ExoStage's onVideoSizeChanged) — self-correcting if the hint was wrong.
    var vertical by remember(arg.url) { mutableStateOf(arg.vertical) }

    // Lock the chosen orientation while playing. MainActivity has configChanges=orientation|screenSize so
    // this does NOT recreate the activity. Re-applied when `vertical` flips; restored on exit.
    // Per device tier:
    //   TV     -> always landscape.
    //   Tablet -> dracin follows rotation (both), horizontal locks landscape.
    //   Phone  -> dracin portrait, horizontal landscape.
    // (Immersive is handled by the sticky effect below.)
    val tier = remember { com.tetonova.app.deviceTier(context) }
    DisposableEffect(vertical, tier) {
        val activity = context as? Activity
        val prev = activity?.requestedOrientation
        val chosen = when {
            tier == com.tetonova.app.DeviceTier.TV -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            vertical && tier == com.tetonova.app.DeviceTier.TABLET -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            vertical -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        activity?.requestedOrientation = chosen
        android.util.Log.i("TnPlayer", "orientation=${if (vertical) "PORTRAIT" else "LANDSCAPE"} tier=$tier (hint=${arg.vertical}) url=${arg.url}")
        onDispose { activity?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val decor = window?.decorView
        val prevKeepScreenOn = decor?.keepScreenOn ?: false
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        decor?.keepScreenOn = true
        onDispose {
            decor?.keepScreenOn = prevKeepScreenOn
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // Keep the player truly immersive. Compose popups/emulator windows can briefly reveal system bars,
    // so use both the modern controller and legacy sticky flags, then re-apply on focus changes.
    @Suppress("DEPRECATION")
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val decor = window?.decorView
        val previousUi = decor?.systemUiVisibility ?: android.view.View.SYSTEM_UI_FLAG_VISIBLE
        fun hideBars() {
            val d = decor ?: return
            d.systemUiVisibility = playerSystemUiFlags()
            window?.let {
                WindowCompat.getInsetsController(it, d).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        hideBars()
        val focusL = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) hideBars()
        }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focusL)
        onDispose {
            decor?.viewTreeObserver?.removeOnWindowFocusChangeListener(focusL)
            decor?.systemUiVisibility = previousUi
            window?.let { w -> decor?.let { d -> WindowCompat.getInsetsController(w, d).show(WindowInsetsCompat.Type.systemBars()) } }
        }
    }

    var loading by remember { mutableStateOf(true) }
    var servers by remember { mutableStateOf<List<VideoServer>>(emptyList()) }
    var selected by remember { mutableStateOf<VideoServer?>(null) }
    var retryTick by remember { mutableStateOf(0) } // bumped by the NoSource "Coba lagi" button to re-resolve
    // Servers that failed auto-play (extraction / playback error / 404 / too-slow) — skipped on failover.
    val failed = remember { mutableStateListOf<String>() }
    fun keyOf(s: VideoServer) = s.name

    LaunchedEffect(arg.url, retryTick) {
        loading = true
        if (offlineVariant != null) { loading = false; return@LaunchedEffect } // offline: no server scrape
        failed.clear()
        TnData.preparePremiumProxy()
        // Every playable server, FASTEST-FIRST (pre-resolved direct streams → clean players → embeds),
        // so auto-pick plays the quickest source and failover walks the rest in that order.
        var list = arg.url?.let { runCatching { TnData.servers(it) }.getOrNull() }.orEmpty()
            .filter { StreamExtractor.isPlayable(it.embedUrl) }
        val kuraUrl = arg.url?.takeIf { "kuramanime" in it.lowercase() }
        val kuramadriveOk = list.isNotEmpty() // false when kuramadrive's player token is throttled on a reopen
        // Kuramadrive throttled (empty) → fall back to kuramanime's OTHER servers (DoodStream/etc., which
        // don't use that token), grabbing the first that resolves, so the episode still plays vs NoSource.
        if (kuraUrl != null && !kuramadriveOk) {
            list = WebViewGate.kuramanimeServers(context, kuraUrl, stopAfterFirst = true)
                .map { (name, embed) -> VideoServer(name, embed) }
                .filter { StreamExtractor.isPlayable(it.embedUrl) }
        }
        // DutaMovie exposes a deliberate Server 1..N tab order. Preserve it in the player and picker;
        // global speed sorting made those numbered website tabs appear shuffled.
        val preserveWebsiteOrder = arg.url?.let { url ->
            DutamovieSource.isDutamovie(url) || JavHeySource.isJavHey(url) || IndoMax21Source.isIndoMax21(url)
        } == true
        if (!preserveWebsiteOrder) {
            list = list.sortedWith(compareBy<VideoServer>({ speedRank(it) }, { -bestServerHeight(it) }, { it.name.lowercase() }))
        }
        servers = list
        // Debug smoke-test hook: lets ADB launch the real player on a specific website tab without
        // changing release behaviour or reordering the user-facing Source list.
        val debugServer = if (BuildConfig.DEBUG) {
            (context as? Activity)?.intent?.getIntExtra("player_server", 0)?.also {
                (context as? Activity)?.intent?.removeExtra("player_server")
            } ?: 0
        } else 0
        // Keep JavHey's website order in the Source menu, but start on its native VidStack mirror when
        // available. Otherwise Server 1 can be an ad/captcha embed and make the app look like it never
        // reached our ExoPlayer even though a native HLS mirror is present later in the same six tabs.
        val javHeyNative = if (arg.url?.let(JavHeySource::isJavHey) == true) {
            list.minWithOrNull(compareBy<VideoServer>({ speedRank(it) }, { list.indexOf(it) }))
        } else null
        selected = list.getOrNull(debugServer - 1) ?: javHeyNative ?: list.firstOrNull()
        android.util.Log.i("TnPlayer", "servers (website order): ${list.map { it.name }}; auto-pick '${selected?.name}'")
        loading = false
        // NOTE: kuramadrive's player token is aggressively rate-limited, so we deliberately do NOT
        // auto-enumerate the other servers here — that loaded kuramadrive a 2nd time per open and burned
        // through the limit, leaving only the first (fresh) open working. Kuramadrive is used exactly once
        // per open now; if it's throttled anyway, the fallback above resolves a non-kuramadrive server.
    }

    fun openExternal() {
        if (arg.url.isNullOrBlank()) return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(arg.url))) }
    }

    // Auto-failover: mark the current source failed, drop it from the picker, then continue forward.
    fun onServerFailed(): Boolean {
        val failedServer = selected
        val failedName = failedServer?.name
        failedServer?.let { if (keyOf(it) !in failed) failed.add(keyOf(it)) }
        val currentIndex = failedServer?.let { cur -> servers.indexOfFirst { sameSource(it, cur) } } ?: -1
        // Wrap around so a failed manual pick near the end of the list still falls back to the
        // earlier (possibly just-working) servers; the failed set prevents endless cycling.
        val next = (servers.drop(currentIndex + 1) + servers.take(currentIndex + 1))
            .firstOrNull { keyOf(it) !in failed }
        if (next != null) selected = next
        android.util.Log.i("TnPlayer", "server '$failedName' failed -> ${next?.name ?: "none"}")
        // Say WHY the source changed. Silent failover made a manual pick look like it was ignored — the
        // player would just wander off to another server with no explanation.
        if (failedName != null && next != null) {
            Toast.makeText(context, "Source \"$failedName\" gagal — pindah ke \"${next.name}\"", Toast.LENGTH_SHORT).show()
        }
        return next != null
    }
    // Manual pick starts a fresh attempt for that source; if it fails, failover walks forward from there.
    fun onPickServer(s: VideoServer) {
        failed.remove(keyOf(s))
        selected = s
    }

    val current = selected
    if (offlineVariant != null) {
        // Downloaded → play from cache through ExoStage with a synthetic "Tersimpan" server.
        val offServer = remember(arg.url) { VideoServer("Tersimpan", arg.url.orEmpty()) }
        ExoStage(
            variants = listOf(offlineVariant),
            headers = DownloadCenter.headersFor(arg.url),
            arg = arg, server = offServer, servers = listOf(offServer),
            onPickServer = {}, onBack = onBack, onExternal = ::openExternal, onError = {},
            hasNext = hasNext, onNext = onNext, hasPrev = hasPrev, onPrev = onPrev, onVertical = { vertical = it },
            dataSourceFactory = DownloadCenter.cacheFactory(),
        )
    } else when {
        loading -> LoadingBox("Mencari source…")
        current == null -> NoSourceBox(onRetry = { retryTick++ }, onExternal = ::openExternal, onBack = onBack)
        else -> ServerPlayer(
            current,
            arg,
            servers.filter { keyOf(it) !in failed || sameSource(it, current) },
            referer,
            ::onPickServer,
            ::onServerFailed,
            onBack,
            ::openExternal,
            hasNext,
            onNext,
            hasPrev = hasPrev,
            onPrev = onPrev,
            onVertical = { vertical = it },
        )
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
    onServerFailed: () -> Boolean,
    onBack: () -> Unit,
    onExternal: () -> Unit,
    hasNext: Boolean = false,
    onNext: () -> Unit = {},
    hasPrev: Boolean = false,
    onPrev: () -> Unit = {},
    onVertical: (Boolean) -> Unit = {},
) {
    // Hosts whose token streams 404/403 ExoPlayer (browser-context anti-leech) but play fine in a
    // WebView — JWPlayer (videoplayer.vip). Played in the WebView with the host UI hidden and OUR
    // controls overlaid, driven via the host's JS/postMessage API.
    //
    // Dailymotion is deliberately NOT here: `dmPlayer` builds its wrapper around the Dailymotion SDK at
    // `geo.dailymotion.com/libs/player/<playerId>.js`, and that player id is account+domain locked. The
    // hardcoded default ("xir9o", anixcafe's) 403s for any other site — e.g. loaded with an anichin
    // referer it returns "Forbidden", so createPlayer never exists, nothing plays, and the 12s watchdog
    // silently failed the server. The metadata extractor handles Dailymotion properly instead
    // (qualities.auto[] → a real #EXTM3U master), which also gets us our own controls + Resolusi picker.
    val webPlayer = when {
        isJwPlayerHost(server.embedUrl) || isHydraxEmbed(server.embedUrl) -> jwPlayer(server.embedUrl, referer)
        else -> null
    }
    if (webPlayer != null) {
        WebPlayerStage(webPlayer, server.embedUrl, arg.title, arg.episodeLabel, servers, server, onPickServer, onServerFailed, onBack, onExternal)
        return
    }
    if (isEmbedOnlyHost(server.embedUrl)) {
        WebStage(server, arg, servers, referer, onPickServer, onBack, onExternal)
        return
    }
    var phase by remember(server) { mutableStateOf<Phase>(Phase.Extracting) }
    var retryExtract by remember(server) { mutableStateOf(0) }
    LaunchedEffect(server, retryExtract) {
        // 1) static extractor (ok.ru/dailymotion/rumble/filemoon) → 2) WebView sniffer → 3) WebView embed.
        val res = runCatching { StreamExtractor.extract(server, referer) }.getOrDefault(ExtractResult(emptyList()))
        android.util.Log.i("TnPlayer", "extract '${server.name}' (${server.embedUrl.take(64)}) → ${res.variants.size} variants ${res.variants.map { it.label }}${if (res.variants.isEmpty()) " → sniff" else ""}")
        phase = if (res.variants.isNotEmpty()) Phase.Exo(res.variants, res.headers, res.subtitles) else Phase.Sniffing
    }
    // Hydrax's URL is browser-bound: the iframe plays it, while ExoPlayer can get a 403 for the same
    // token. Keep that source in its working WebView instead of treating a direct-playback failure as
    // a dead server and silently skipping to the next one.
    val onFail = {
        if (isHydraxEmbed(server.embedUrl)) phase = Phase.Web
        else if (!onServerFailed()) phase = Phase.Dead
    }
    when (val p = phase) {
        Phase.Extracting -> LoadingBox("Menyiapkan video…")
        Phase.Sniffing -> SniffStage(
            embedUrl = server.embedUrl, referer = referer,
            onSniffed = { variants, h -> phase = Phase.Exo(variants, h) },
            onFail = onFail,
        )
        is Phase.Exo ->
            // Melolo's encrypted MP4 (trips ExoPlayer's Mp4Extractor with "Invalid NAL length" raw) is
            // decrypted in-flight by MeloloDataSource — see the `#tnk=` netFactory branch in ExoStage.
            ExoStage(
                p.variants,
                p.headers,
                arg,
                server.copy(subtitles = (server.subtitles + p.subtitles).distinctBy { it.url }),
                servers,
                onPickServer,
                onBack,
                onExternal,
                onError = onFail,
                hasNext = hasNext,
                onNext = onNext,
                hasPrev = hasPrev,
                onPrev = onPrev,
                onVertical = onVertical,
            )
        Phase.Dead -> NoSourceBox(onRetry = { phase = Phase.Extracting; retryExtract++ }, onExternal = onExternal, onBack = onBack)
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
    data class Exo(
        val variants: List<StreamVariant>,
        val headers: Map<String, String>,
        val subtitles: List<com.tetonova.core.scraper.SubtitleTrack> = emptyList(),
    ) : Phase
    data object Dead : Phase
    data object Web : Phase
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
// Kicks playback AND reads the resolved stream URL straight from JWPlayer's API (videoplayer.vip
// decrypts its AES config client-side, then jwplayer().setup({...file/sources...}) — so the playlist
// holds the real /hls/ URL without us breaking their crypto). Falls back to clicking play buttons /
// reading a <video> src. Returns the URL string (or "").
private const val READER_JS =
    "(function(){try{var f='';" +
        "if(window.jwplayer){var p=jwplayer();" + // read only — do NOT play() (would consume the single-use token)
        // videoplayer.vip is an "All In One" multi-source JWPlayer — read the ACTIVE item, not [0].
        "var it=(p.getPlaylistItem&&p.getPlaylistItem())||null;" +
        "var pl=(p.getPlaylist&&p.getPlaylist())||[];" +
        "if(!it&&pl[0])it=pl[0];" +
        "if(it)f=it.file||(it.sources&&it.sources[0]&&it.sources[0].file)||'';" +
        "if(!f&&p.getConfig){var c=p.getConfig();if(c&&c.playlist&&c.playlist[0]){var s=c.playlist[0];f=s.file||(s.sources&&s.sources[0]&&s.sources[0].file)||'';}}}" +
        "return f;}catch(e){return '';}})();"

// JS-bridge for JWPlayer hosts (videoplayer.vip): hide the embed's own UI, then drive/read it via API.
private const val JW_SETUP_JS =
    "(function(){" +
        // Force the layout viewport to device width (embed sets width=640 → player rendered small).
        "try{var mv=document.querySelector('meta[name=viewport]');if(!mv){mv=document.createElement('meta');mv.name='viewport';document.head.appendChild(mv);}mv.setAttribute('content','width=device-width,initial-scale=1,user-scalable=no');}catch(e){}" +
        "try{var s=document.createElement('style');s.innerHTML=" +
        // Hide JWPlayer's own control UI (safe).
        "'.jw-controlbar,.jw-icon-display,.jw-title,.jw-logo,.jw-rightclick,.jw-nextup-container,#iframeAds,iframe[id*=\"ads\" i],iframe[id*=\"Ads\"],#btnServer,[id*=\"btnServer\"]{display:none!important;}'+" +
        // Force the player + video to fill the screen (embed defaults to a small 640x360 box → looked black).
        "'html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important;}'+" +
        "'.jwplayer,.jw-wrapper,.jw-aspect,#vplayer,#player{position:fixed!important;top:0!important;left:0!important;width:100%!important;height:100%!important;padding:0!important;max-width:none!important;}'+" +
        "'video{width:100%!important;height:100%!important;object-fit:contain!important;}';" +
        "document.head.appendChild(s);}catch(e){}try{jwplayer().setControls(false);jwplayer().resize(window.innerWidth,window.innerHeight);jwplayer().play();}catch(e){}})();"
private const val JW_STATE_JS = """(function(){try{
var w=window,d=document,p=null;
try{var f=document.querySelector('iframe');if(f&&f.contentWindow){w=f.contentWindow;d=f.contentDocument||w.document;}}catch(e){}
try{if(w.jwplayer)p=w.jwplayer();}catch(e){}
var st='',pos=0,dur=0,q=[],qi=0;
if(p){
 try{p.setControls(false);}catch(e){}
 try{p.resize(w.innerWidth,w.innerHeight);}catch(e){}
 try{if(typeof p.getState==='function')st=p.getState()||'';}catch(e){}
 try{if(typeof p.getPosition==='function')pos=p.getPosition()||0;else if(typeof p.getCurrentTime==='function')pos=p.getCurrentTime()||0;}catch(e){}
 try{if(typeof p.getDuration==='function')dur=p.getDuration()||0;}catch(e){}
 try{var ql=p.getQualityLevels?p.getQualityLevels():[];for(var k=0;k<ql.length;k++){var label=ql[k].label||('Q'+k);if(q.indexOf(label)<0)q.push(label);}qi=p.getCurrentQuality?p.getCurrentQuality():0;}catch(e){}
}
try{var v=d.querySelector('video');if(v){if(!pos)pos=v.currentTime||0;if(!dur||dur<0)dur=v.duration||0;if(!v.paused&&!v.ended&&v.readyState>=2)st='playing';}}catch(e){}
if(!q.length){try{d.querySelectorAll('.jw-settings-submenu-quality .jw-settings-content-item').forEach(function(el){var label=(el.textContent||'').trim();if(label&&q.indexOf(label)<0)q.push(label);});}catch(e){}}
try{var dv=d.querySelectorAll('div');for(var j=0;j<dv.length;j++){var el=dv[j],tx=(el.textContent||'');if(/resume watching|welcome back/i.test(tx)&&tx.length<170)el.style.display='none';}}catch(e){}
return JSON.stringify({st:st,pos:Math.floor(pos||0),dur:Math.floor(dur||0),q:q,qi:qi});
}catch(e){return JSON.stringify({st:'',pos:0,dur:0,q:[],qi:0});}})();"""

private fun isJwPlayerHost(embedUrl: String): Boolean = "videoplayer.vip" in embedUrl
private fun isDailymotionHost(embedUrl: String): Boolean = "dailymotion" in embedUrl

/** Hosts that play ONLY inside their own WebView player — a native player our extractor/sniffer can't
 *  crack that still plays fine in its own iframe. Routed straight to [WebStage] so extract→sniff
 *  doesn't fail-and-DROP the server
 *  (there is no plain-embed fallback after a failed sniff — a dropped server just disappears).
 *
 *  Samehadaku's OLD movies serve only these two hosts, so without this they sniff-fail → NoSource:
 *   - files.fm (samehadaku ships it as `file.fm/embed/playerv2`, 301→files.fm): a VideoJS + WebTorrent
 *     P2P player whose stream is a blob/webseed (`down.php`, IP-gated) — nothing static to sniff.
 *   - gdriveplayer.to: an obfuscated JWPlayer (XOR-decoded config via `file.js`) with ad-gated sources. */
private fun isEmbedOnlyHost(embedUrl: String): Boolean {
    val h = embedUrl.lowercase()
    return "gn1r5n" in h ||
        "file.fm" in h || "files.fm" in h || "gdriveplayer" in h || "playeriframe.sbs" in h ||
        // Byse's current API uses browser fingerprint/captcha attestation. Its own embed remains the
        // reliable path when the native AES-GCM handshake is rejected, so never auto-skip the mirror.
        "byse" in h ||
        // Blogger (anoboy Btube): a Google WIZ player that only requests its googlevideo stream AFTER a
        // real play gesture — the background sniffer can't trigger that (synthetic .click() isn't a
        // trusted gesture), so it plays in its own WebView player (one tap). The app already ranks the
        // sniffable YUp/yourupload mirror ABOVE this, so OUR player is used whenever an episode has one;
        // Blogger is only the fallback for Btube-only episodes.
        "blogger.com/video.g" in h || "blogspot.com/video" in h
}

private fun isHydraxEmbed(embedUrl: String): Boolean {
    val h = embedUrl.lowercase()
    return "hydrax" in h || "abyss.to" in h || "abyssplayer" in h
}

private fun hydraxWrapperHtml(embedUrl: String): String {
    val src = embedUrl.replace("&", "&amp;").replace("\"", "&quot;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body,iframe{margin:0;width:100%;height:100%;border:0;background:#000;overflow:hidden}iframe{display:block}</style>
</head><body><iframe src="$src" allow="autoplay; fullscreen"></iframe></body></html>"""
}

private const val HYDRAX_READER_JS =
    "(function(){try{var f=document.querySelector('iframe');var w=f&&f.contentWindow;" +
        "if(!w||!w.jwplayer)return '[]';var p=w.jwplayer();" +
        "var it=(p.getPlaylistItem&&p.getPlaylistItem())||null;var ss=(it&&it.sources)||[];var out=[];" +
        "for(var i=0;i<ss.length;i++){var u=ss[i].file||'';if(u.indexOf('//')===0)u='https:'+u;" +
        "if(/^https?:/.test(u))out.push({label:ss[i].label||'Auto',url:u});}" +
        "out.sort(function(a,b){return(parseInt(b.label)||0)-(parseInt(a.label)||0);});" +
        "return JSON.stringify(out);}catch(e){return '[]';}})();"

// Dailymotion is cross-origin (no CSS/JS injection) and its raw iframe only posts benchmark telemetry
// to the parent, not the Player API. So we host it via the official Dailymotion Player SDK inside OUR
// wrapper page (loaded with the embedder origin as base URL): createPlayer() returns a player whose
// getState() we poll (videoTime/Duration/Quality/QualitiesList) and whose play/pause/seek/setQuality
// we call. State + commands land in window.__dm / window.__p.
private fun dmWrapperHtml(embedUrl: String): String {
    val playerId = Regex("player/([^/.]+)\\.html").find(embedUrl)?.groupValues?.get(1) ?: "xir9o"
    val videoId = Regex("[?&]video=([^&]+)").find(embedUrl)?.groupValues?.get(1)
        ?: Regex("dailymotion\\.com/(?:embed/)?video/([^_?&/]+)").find(embedUrl)?.groupValues?.get(1) ?: ""
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>html,body{margin:0;padding:0;height:100%;width:100%;background:#000;overflow:hidden}
#dmp{position:fixed!important;top:0;left:0;width:100vw!important;height:100vh!important}
/* The SDK injects its own wrapper div + iframe; force them to fill (some player IDs default to a
   16:9 aspect box that otherwise leaves a black bar). */
#dmp>*,#dmp iframe{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;max-height:none!important;padding:0!important;border:0!important}</style></head>
<body><div id="dmp"></div>
<script>
window.__dm={st:'idle',pos:0,dur:0,q:[],qi:0};
// The SDK sizes #dmp + its iframe to ~640x720 via late !important stylesheet rules (taller than the
// 640x360 viewport → black bar). Beat them with inline !important on #dmp AND the iframe chain,
// re-applied since the SDK builds/resizes async.
// The SDK sizes #dmp with a `padding-bottom:56.25%` aspect hack + late !important rules (→ 640x720,
// taller than the 640x360 viewport = black bar). Beat them with inline !important on #dmp (kill the
// padding) and the iframe chain, re-applied since the SDK builds/resizes async.
function S(el,k,v){el.style.setProperty(k,v,'important');}
function fit(){try{
 var dmp=document.getElementById('dmp');var h=window.innerHeight+'px';
 if(dmp){S(dmp,'position','fixed');S(dmp,'top','0');S(dmp,'left','0');S(dmp,'width',window.innerWidth+'px');S(dmp,'height',h);S(dmp,'max-height',h);S(dmp,'min-height','0');S(dmp,'padding','0');S(dmp,'padding-bottom','0');S(dmp,'box-sizing','border-box');}
 var el=document.querySelector('#dmp iframe');
 while(el&&el!==dmp){S(el,'position','absolute');S(el,'top','0');S(el,'left','0');S(el,'width','100%');S(el,'height','100%');S(el,'max-height','100%');S(el,'min-height','0');S(el,'padding','0');S(el,'margin','0');el=el.parentElement;}
}catch(e){}}
setInterval(fit,400);
window.__dmSetQ=function(i){try{var q=window.__dm.q;if(window.__p&&q&&q[i]!=null)window.__p.setQuality(''+q[i]);}catch(e){}};
window.dailymotion={onScriptLoaded:function(){
 dailymotion.createPlayer('dmp',{video:'$videoId',params:{mute:false}}).then(function(p){
  window.__p=p;try{p.play();}catch(e){}
  setInterval(function(){p.getState().then(function(s){var m=window.__dm;
   m.st=s.playerIsBuffering?'buffering':(s.playerIsPlaying?'playing':'paused');
   if(s.videoTime!=null)m.pos=Math.floor(s.videoTime);
   if(s.videoDuration!=null)m.dur=Math.floor(s.videoDuration);
   if(s.videoQualitiesList)m.q=[].concat(s.videoQualitiesList).map(String);
   if(s.videoQuality!=null){var qi=m.q.indexOf(''+s.videoQuality);if(qi>=0)m.qi=qi;}
  }).catch(function(e){});},500);
 }).catch(function(e){});
}};
</script>
<script src="https://geo.dailymotion.com/libs/player/$playerId.js"></script>
</body></html>"""
}

private const val DM_STATE_JS =
    "(function(){try{var m=window.__dm;if(!m)return '';" +
        "return JSON.stringify({st:m.st,pos:m.pos,dur:m.dur,q:m.q,qi:m.qi});}catch(e){return '';}})();"

/** Host-specific JS surface for [WebPlayerStage] — one Compose scaffold drives both JWPlayer
 *  (videoplayer.vip, jwplayer() API) and Dailymotion (cross-origin iframe + postMessage bridge). */
private class WebPlayer(
    val load: (WebView) -> Unit,
    val setupJs: String,                 // run onPageFinished ("" = none; wrapper self-inits)
    val stateJs: String,                 // polled → {st,pos,dur,q?,qi?}
    val playKick: String,                // JS to (re)start playback
    val toggle: String,                  // JS to toggle play/pause
    val seekAbs: (Long) -> String,       // JS to seek to absolute seconds
    val setQuality: (Int) -> String,     // JS to select quality index
    val allowHost: (String) -> Boolean,  // main-frame nav allowed for this host (else blocked as an ad)
)

private fun jwPlayer(embedUrl: String, referer: String): WebPlayer {
    val framed = isHydraxEmbed(embedUrl)
    val playerLookup =
        "var f=document.querySelector('iframe');var w=(f&&f.contentWindow)||window;" +
            "var p=w.jwplayer&&w.jwplayer();"
    return WebPlayer(
    load = {
        if (framed) {
            // Abyss redirects an embed opened as a top-level page to abyss.to. Keep it in the same-origin
            // iframe shape used by DutaMovie so its player remains loaded and its JW API stays reachable.
            it.loadDataWithBaseURL(embedUrl, hydraxWrapperHtml(embedUrl), "text/html", "UTF-8", embedUrl)
        } else {
            it.loadUrl(embedUrl, mapOf("Referer" to referer))
        }
    },
    setupJs = JW_SETUP_JS, stateJs = JW_STATE_JS,
    playKick = "try{$playerLookup p&&p.play(true);}catch(e){}",
    toggle = "try{$playerLookup if(p)p.getState()=='playing'?p.pause(true):p.play(true);}catch(e){}",
    seekAbs = { "try{$playerLookup p&&p.seek($it);}catch(e){}" },
    setQuality = { index ->
        "try{$playerLookup if(p&&p.setCurrentQuality)p.setCurrentQuality($index);" +
            "else{var d=(f&&f.contentDocument)||document;var q=d.querySelectorAll('.jw-settings-submenu-quality .jw-settings-content-item');if(q[$index])q[$index].click();}}catch(e){}"
    },
    allowHost = { it.endsWith("videoplayer.vip") || isHydraxEmbed(it) },
)
}

private fun dmPlayer(embedUrl: String, referer: String) = WebPlayer(
    load = {
        val base = runCatching { java.net.URI(referer).let { u -> "${u.scheme}://${u.host}/" } }.getOrNull() ?: "https://anixcafe.com/"
        it.loadDataWithBaseURL(base, dmWrapperHtml(embedUrl), "text/html", "utf-8", null)
    },
    setupJs = "", stateJs = DM_STATE_JS,
    playKick = "try{window.__p&&window.__p.play();}catch(e){}",
    toggle = "try{var m=window.__dm;if(window.__p)window.__p[m&&m.st=='playing'?'pause':'play']();}catch(e){}",
    seekAbs = { "try{window.__p&&window.__p.seek($it);}catch(e){}" },
    setQuality = { "try{window.__dmSetQ&&window.__dmSetQ($it);}catch(e){}" },
    allowHost = { it.contains("dailymotion") || it.endsWith("dmcdn.net") },
)

/** Ad/tracker/anti-bot-overlay hosts to block in the JWPlayer WebView (e.g. the ADEX "verify you are
 *  human" interstitial). Blocking them at the network level keeps the video clean behind our controls. */
private val AD_HOSTS = listOf(
    "exceedbronzetooth", "protrafficinspector", "yellowishgather", "255md", "dtscout", "dtscdn", "onaudience", "histats",
    "crwdcntrl", "adex", "doubleclick", "googlesyndication", "kettledrooping", "spendsdetachment",
    "zoologyfibre", "popads", "popcash", "propeller", "adsterra", "hilltopads",
    // popunder / push / native-ad networks behind the gambling interstitials on 4meplayer/blogger embeds
    "monetag", "onclick", "clickadu", "admaven", "juicyads", "exoclick", "trafficjunky", "mgid",
    "adskeeper", "adcash", "richads", "galaksion", "adnxs", "taboola", "outbrain", "revcontent",
    // common betting brands that get injected as overlay iframes
    "1xbet", "melbet", "mostbet", "betvisa", "baji", "babu88", "marvelbet", "jeetbuzz", "crickex",
)
private fun isAdHost(host: String): Boolean = host.lowercase().let { h -> AD_HOSTS.any { it in h } }

/** Empty 200 used to swallow a blocked ad request without erroring the page. */
private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

private fun hydraxChromeClient(ctx: android.content.Context) = object : WebChromeClient() {
    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
        val parent = (ctx as? Activity)?.window?.decorView as? android.widget.FrameLayout ?: return false
        val popup = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
        }
        parent.addView(popup, android.widget.FrameLayout.LayoutParams(1, 1))
        (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
        resultMsg.sendToTarget()
        popup.postDelayed({
            (popup.parent as? ViewGroup)?.removeView(popup)
            popup.destroy()
        }, 1500)
        return true
    }
}

private fun hydraxVariants(result: String?): List<StreamVariant> = runCatching {
    val json = org.json.JSONTokener(result.orEmpty()).nextValue() as? String ?: return@runCatching emptyList()
    val array = org.json.JSONArray(json)
    buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.startsWith("http")) add(StreamVariant(item.optString("label", "Auto"), url))
        }
    }.distinctBy { it.url }
}.getOrDefault(emptyList())

/**
 * JS injected into ad-heavy embeds (4meplayer/blogger) to strip injected betting overlays — the
 * full-screen "BONUS" modal and the floating draggable widget. Removal is tied to an ad URL signal
 * (a betting/ad link or iframe), so it never touches the real `<video>`/player. Re-runs on a timer +
 * MutationObserver because these ad scripts re-inject after a delay.
 */
private const val STRIP_ADS_JS =
    "(function(){if(window.__tnAdClean)return;window.__tnAdClean=1;" +
        "var RE=/(bet|casino|slot|jackpot|bonus|1xbet|melbet|mostbet|baji|babu88|jeetbuzz|crickex|marvelbet|lottery|gambl|aviator)/i;" +
        "function box(el){var n=el;for(var i=0;i<6&&n&&n!==document.body;i++){var s;try{s=getComputedStyle(n);}catch(e){break;}if(s&&(s.position==='fixed'||s.position==='absolute'))return n;n=n.parentElement;}return el;}" +
        "function clean(){try{" +
        "document.querySelectorAll('#uyeouyeo,a[id=\"uyeouyeo\"]').forEach(function(a){a.remove();});" +
        "document.querySelectorAll('a[href]').forEach(function(a){if(RE.test(a.getAttribute('href')||'')){box(a).remove();}});" +
        "document.querySelectorAll('iframe[src]').forEach(function(f){if(RE.test(f.getAttribute('src')||'')){box(f).remove();}});" +
        "}catch(e){}}" +
        "clean();setInterval(clean,800);" +
        "try{new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}})();"

private const val SNIFF_KICK_JS =
    "(function(){try{" +
        "if(window.jwplayer){try{jwplayer().setControls(false);jwplayer().play(true);}catch(e){}}" +
        "if(window.videojs){try{var ids=Object.keys(videojs.players||{});for(var i=0;i<ids.length;i++){var p=videojs(ids[i]);p&&p.play&&p.play();}}catch(e){}}" +
        "var v=document.querySelector('video');if(v){v.muted=false;var pr=v.play&&v.play();if(pr&&pr.catch){pr.catch(function(){v.muted=true;v.play&&v.play();});}}" +
        "var b=document.querySelector('.jw-icon-display,.vjs-big-play-button,.plyr__control--overlaid,button[aria-label*=\"lay\" i],.play-button,.play');if(b)b.click();" +
        "}catch(e){}})();"

/** Ad manifests that LOOK like a stream. Dailymotion serves its VMAP ad-break manifest from
 *  `dmxleo.dailymotion.com/...m3u8?...&af=[APIFRAMEWORKS]&vv=[VASTVERSIONS]` — it ends in `.m3u8` but
 *  returns VMAP **XML**, so sniffing it fed ExoPlayer garbage ("Input does not start with the #EXTM3U
 *  header") and the server got failed+skipped even though the real video was fine. */
private fun isAdManifest(low: String): Boolean =
    "dmxleo." in low || "[vastversions]" in low || "[apiframeworks]" in low

private fun looksLikeStream(u: String): Boolean {
    val low = u.lowercase()
    val path = low.substringBefore('?')
    if (isAdManifest(low)) return false
    // Extensions OR path markers (videoplayer.vip serves HLS at /hls/<token> with no .m3u8 suffix).
    return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") ||
        path.endsWith(".mkv") || path.endsWith(".webm") ||
        ".m3u8" in low || "/hls/" in path || "/manifest" in path ||
        ("cdn.dramabos.video/api/" in low && "/hls" in path) ||
        "videotv.vividshort.com" in low ||
        "videotv.dramaexpo.com" in low ||
        "montagehub.xyz" in low ||
        "janzhoutec.com" in low ||
        // Hydrax's progressive MP4 endpoint is extensionless (`/sora/<id>/<token>`).
        ("sssrr.org" in low && "/sora/" in path) ||
        // Blogger (anoboy Btube) streams from googlevideo's /videoplayback with no file extension
        // and `mime=video/mp4` (not `mime_type=`), so the checks above miss it.
        ("googlevideo.com" in low && "videoplayback" in path) ||
        "mime_type=video_mp4" in low
}

/** True when the resolved URL is HLS (so ExoPlayer is told the MIME type when the URL lacks .m3u8). */
private fun isHls(u: String): Boolean {
    val low = u.lowercase()
    val path = low.substringBefore('?').substringBefore('#')
    return ".m3u8" in low ||
        "/hls/" in path ||
        "/manifest" in path ||
        ("majorplay.net" in low && Regex("/(?:config|data)-\\d+\\.json$").containsMatchIn(path)) ||
        ("cdn.dramabos.video/api/" in low && "/hls" in path) ||
        ("kesbayar.sbs" in low && Regex("\\.\\d{3,4}p$").containsMatchIn(path))
}

private fun isMp4Like(u: String): Boolean {
    val low = u.lowercase()
    val path = low.substringBefore('?').substringBefore('#')
    return path.endsWith(".mp4") ||
        "videotv.vividshort.com" in low ||
        "videotv.dramaexpo.com" in low ||
        "montagehub.xyz" in low ||
        "janzhoutec.com" in low ||
        "awscdn.netshort.com" in low ||
        ("googlevideo.com" in low && "videoplayback" in low) || // Blogger progressive mp4
        "mime_type=video_mp4" in low
}

private fun preferredSubtitleTrack(subtitles: List<SubtitleTrack>): SubtitleTrack? =
    subtitles
        .filter { it.url.isNotBlank() }
        .distinctBy { it.url }
        .minByOrNull { track ->
            when (track.language?.lowercase()) {
                "id", "in" -> 0
                "en" -> 1
                else -> 2
            }
        }

private suspend fun fetchSubtitleCues(url: String): List<SubtitleCue> = withContext(Dispatchers.IO) {
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", DESKTOP_UA)
        }
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { parseSrtCues(it.readText()) }
    }.getOrElse { emptyList() }
}

private fun parseSrtCues(raw: String): List<SubtitleCue> =
    raw.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\n{2,}"))
        .mapNotNull { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) return@mapNotNull null
            val times = lines[timeIndex].split("-->")
            val start = times.getOrNull(0)?.parseSrtTime() ?: return@mapNotNull null
            val end = times.getOrNull(1)?.parseSrtTime() ?: return@mapNotNull null
            val text = lines.drop(timeIndex + 1)
                .joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .trim()
            if (text.isBlank()) null else SubtitleCue(start, end, text)
        }

private fun String.parseSrtTime(): Long? {
    val m = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})").find(this.trim()) ?: return null
    val h = m.groupValues[1].toLongOrNull() ?: return null
    val min = m.groupValues[2].toLongOrNull() ?: return null
    val sec = m.groupValues[3].toLongOrNull() ?: return null
    val ms = m.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: return null
    return (((h * 60L + min) * 60L + sec) * 1000L) + ms
}

/**
 * Loads the embed in an ATTACHED, full-size WebView and watches its traffic for a `.m3u8`/`.mp4`.
 * Off-screen/orphan WebViews don't run media, so it must be in the tree; non-Hydrax players get a
 * play-nudge while Hydrax stays tappable for its own gate. First stream → [onSniffed].
 */
@Composable
private fun SniffStage(embedUrl: String, referer: String, onSniffed: (List<StreamVariant>, Map<String, String>) -> Unit, onFail: () -> Unit) {
    val hydrax = isHydraxEmbed(embedUrl)
    val done = remember(embedUrl) { AtomicBoolean(false) }
    val hydraxFirst = remember(embedUrl) { AtomicReference<Pair<String, Map<String, String>>?>(null) }
    var web by remember(embedUrl) { mutableStateOf<WebView?>(null) }
    LaunchedEffect(embedUrl) {
        delay(if (hydrax) 30_000 else 12_000)
        if (done.compareAndSet(false, true)) onFail()
    }
    LaunchedEffect(web, hydrax) {
        val wv = web ?: return@LaunchedEffect
        if (hydrax) {
            repeat(30) {
                delay(500)
                if (done.get()) return@LaunchedEffect
                wv.evaluateJavascript(HYDRAX_READER_JS) { result ->
                    val variants = hydraxVariants(result)
                    if (variants.isEmpty()) return@evaluateJavascript
                    val headers = hydraxFirst.get()?.second ?: buildMap {
                        put("Referer", embedUrl)
                        val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
                        android.webkit.CookieManager.getInstance().getCookie(origin ?: embedUrl)?.let { put("Cookie", it) }
                    }
                    if (done.compareAndSet(false, true)) onSniffed(variants, headers)
                }
            }
            hydraxFirst.get()?.let { (url, headers) ->
                if (done.compareAndSet(false, true)) onSniffed(listOf(StreamVariant("Auto", url)), headers)
            }
            return@LaunchedEffect
        }
        repeat(8) {
            delay(1200)
            if (done.get()) return@LaunchedEffect
            wv.evaluateJavascript(SNIFF_KICK_JS, null)
            wv.evaluateJavascript(READER_JS) { result ->
                val url = result?.trim('"', ' ')?.replace("\\/", "/")
                    ?.takeIf { it.startsWith("http") && !it.startsWith("blob") }
                if (url != null && done.compareAndSet(false, true)) {
                    val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
                    val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(origin ?: embedUrl) }.getOrNull()
                    val h = buildMap {
                        put("Referer", embedUrl)
                        cookie?.let { put("Cookie", it) } // NO Origin — media fetch is same-origin
                    }
                    onSniffed(listOf(StreamVariant("Auto", url)), h)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    keepScreenOn = true
                    // TV: don't let this WebView grab D-pad focus — keep it on the Compose key handler.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.userAgentString = DESKTOP_UA
                    settings.setSupportMultipleWindows(hydrax)
                    webChromeClient = if (hydrax) hydraxChromeClient(ctx) else WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url.toString()
                            if (isAdHost(request.url.host.orEmpty())) return emptyResponse()
            if (looksLikeStream(u)) {
                val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
                val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(origin ?: embedUrl) }.getOrNull()
                val h = HashMap(request.requestHeaders).apply {
                    putIfAbsent("Referer", if (hydrax) embedUrl else referer)
                    cookie?.let { put("Cookie", it) } // NO Origin — JWPlayer's media fetch doesn't send it
                }
                if (hydrax) {
                    hydraxFirst.compareAndSet(null, u to h)
                    return null
                }
                if (!done.compareAndSet(false, true)) return null
                Handler(Looper.getMainLooper()).post {
                    onSniffed(listOf(StreamVariant("Auto", u)), h)
                                }
                                // Block the WebView's own fetch so a single-use token stays unused → ExoPlayer gets a fresh hit.
                                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(STRIP_ADS_JS, null)
                            if (!hydrax) view.evaluateJavascript(SNIFF_KICK_JS, null)
                        }
                    }
                    if (hydrax) {
                        val base = runCatching {
                            java.net.URI(embedUrl).let { "${it.scheme}://${it.host}/" }
                        }.getOrDefault(referer)
                        loadDataWithBaseURL(base, hydraxWrapperHtml(embedUrl), "text/html", "UTF-8", null)
                    } else {
                        loadUrl(embedUrl, mapOf("Referer" to referer))
                    }
                }
            },
            update = { web = it },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize(),
        )
        if (!hydrax) {
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
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
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
    hasNext: Boolean = false,
    onNext: () -> Unit = {},
    hasPrev: Boolean = false,
    onPrev: () -> Unit = {},
    /** Reports the real video orientation once the first frame's dimensions arrive (portrait → true),
     *  so [PlayerScreen] can lock the matching device orientation even when the source hint was wrong. */
    onVertical: (Boolean) -> Unit = {},
    /** Non-null for offline playback: a cache-backed factory so the downloaded stream plays with no network. */
    dataSourceFactory: DataSource.Factory? = null,
) {
    val context = LocalContext.current
    // ── Watch telemetry: collect playback heartbeats → server XP engine (cultivation / Jalan Kultivasi).
    // The server validates the deltas (skip/idle/background filtered), gates XP at 50% real watch time,
    // and caps per item — see WatchSessionRepository. Plain mutableListOf (not snapshot state): mutated
    // only from the IO poll loop, never read by composition.
    val sessionId = remember(arg.url) { java.util.UUID.randomUUID().toString() }
    val sessionStartRt = remember(arg.url) { android.os.SystemClock.elapsedRealtime() }
    val heartbeats = remember(arg.url) { mutableListOf<Heartbeat>() }
    var lastInteractionAt by remember(arg.url) { mutableLongStateOf(System.currentTimeMillis()) }
    val watchSourceId = remember(arg.url) { TnData.sourceIdForUrl(arg.url.orEmpty()) }
    val watchEpisodeId = remember(arg.url) { TnData.episodeIdForUrl(arg.url.orEmpty()) }
    val watchIsShort = remember(arg.url) { TnData.isShortSource(arg.url) }
    fun flushHeartbeats() {
        if (heartbeats.size < 2) return
        TnData.reportWatchSession(sessionId, watchEpisodeId, watchSourceId, watchIsShort, heartbeats.toList())
        val last = heartbeats.last()
        heartbeats.clear()
        heartbeats.add(last) // keep the last beat so the next batch's cross-flush realtime delta still counts
    }

    // Default to the highest resolution (4K→1080→720→480→360), robust to label variants (4K/UHD/FHD/HD).
    var quality by remember(variants) { mutableStateOf(variants.maxByOrNull { resoHeight(it.label) } ?: variants.first()) }
    val trackSelector = remember { DefaultTrackSelector(context) }
    val exo = remember {
        // Use exactly the headers the resolver said this stream needs (extractor per-host, or the
        // sniffer's captured request headers). Empty = none (Dailymotion's CDN 403s on Referer/Origin).
        val props = headers
        val ua = props.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setDefaultRequestProperties(props.filterKeys { !it.equals("User-Agent", true) })
        // Dailymotion's HLS `sec=` token is bound to the IP that fetched the metadata. The extractor
        // fetches metadata over an IPv4-only OkHttp client, so play the stream through an IPv4-only OkHttp
        // datasource too — otherwise ExoPlayer's HttpURLConnection can egress via IPv6 (a different public
        // address) and the CDN 403s the token. Other hosts stay on DefaultHttpDataSource.
        val isDailymotion = variants.any { val u = it.url.lowercase(); "dailymotion" in u || "dmcdn" in u }
        val httpBase: DataSource.Factory = if (isDailymotion) {
            OkHttpDataSource.Factory(playerOkHttp)
                .setUserAgent(ua)
                .setDefaultRequestProperties(props.filterKeys { !it.equals("User-Agent", true) })
        } else httpFactory
        // wibufile's CDN throttles ANY ranged request (`Range: bytes=…`) to ~30 KB/s but serves a plain
        // full-file GET (no Range) at ~150 KB/s. ExoPlayer's progressive reader issues ranged reads, so its
        // moov fetch crawled and the watchdog killed every wibufile source. Strip the Range on the initial
        // (position-0) read so it takes the fast full-file path — the browser's `<video>` does the same.
        val mediaHttp = if (variants.any { "turboviplay.com" in it.url.lowercase() })
            TurboVipPngTsFactory(httpBase) else httpBase
        val defaultFactory = DefaultDataSource.Factory(context, ParenLiteralFactory(mediaHttp))
        val netFactory: DataSource.Factory = dataSourceFactory
            // Melolo streams (marked by the `#tnk=` key fragment) are AES-CTR encrypted: download+decrypt.
            ?: if (variants.any { "#tnk=" in it.url }) MeloloDataSourceFactory(httpFactory)
            else if (variants.any { "wibufile" in it.url.lowercase() })
                NoInitialRangeFactory(httpFactory)
            else defaultFactory
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(netFactory))
            .setTrackSelector(trackSelector)
            .build().apply { playWhenReady = true }
    }
    // For a single adaptive stream (HLS, e.g. Rumble/LuluStream) the resolutions live as variant tracks
    // inside the manifest — read them so the Resolusi picker can FORCE a specific track (capping the
    // max alone lets ABR still pick a low rung on weak bandwidth → blurry despite a 1080p choice).
    var latestTracks by remember(variants) { mutableStateOf<Tracks?>(null) }
    var trackHeights by remember(variants) { mutableStateOf<List<Int>>(emptyList()) }
    var selHeight by remember(variants) { mutableStateOf<Int?>(null) } // null = Auto (adaptive)
    // Reso priority: default to the HIGHEST track (not ABR "Auto") until the user picks a resolution.
    var autoReso by remember(variants) { mutableStateOf(true) }
    fun applyHeight(h: Int?) {
        selHeight = h
        val p = trackSelector.buildUponParameters()
        if (h == null) {
            p.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            latestTracks?.groups?.firstOrNull { g ->
                g.type == C.TRACK_TYPE_VIDEO && (0 until g.length).any { g.getTrackFormat(it).height == h }
            }?.let { g ->
                val idx = (0 until g.length).first { g.getTrackFormat(it).height == h }
                p.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, idx))
            }
        }
        trackSelector.setParameters(p)
    }

    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) } // Source/Resolusi dropdown open → pause auto-hide
    // AniSkip OP/ED timestamps. Only fetched for matched anime (malId>0, not donghua); stays null
    // otherwise → the manual heuristic chip below. `skip_op` governs whether a present span auto-seeks.
    var skip by remember(arg.url) { mutableStateOf<SkipTimes?>(null) }
    var opSkipped by remember(arg.url) { mutableStateOf(false) }
    var edSkipped by remember(arg.url) { mutableStateOf(false) }
    val autoSkipOn = remember { SettingsStore.getBool("skip_op", false) }
    val skipEligible = arg.malId > 0 && arg.episodeNum != null && !arg.badge.equals("Donghua", true)
    // Auto-next: on STATE_ENDED show a 5s countdown overlay, then advance (cancelable).
    val autoNextOn = remember { SettingsStore.getBool("auto_next", true) }
    var ended by remember(arg.url) { mutableStateOf(false) }
    var nextIn by remember(arg.url) { mutableStateOf(-1) } // >0 = countdown active
    var cancelNext by remember(arg.url) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // TV: explicit hand-offs into the overlay controls. Directional traversal can't reach them on its own
    // because they're children of the fullscreen focusable Box (nothing is "above" it), so Up/Down jump
    // focus straight to the Source pill / the transport row instead.
    val topBarFocus = remember { FocusRequester() }
    val transportFocus = remember { FocusRequester() }
    val preferredSubtitle = remember(server.subtitles) { preferredSubtitleTrack(server.subtitles) }
    var subtitleCues by remember(preferredSubtitle?.url) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    LaunchedEffect(preferredSubtitle?.url) {
        subtitleCues = preferredSubtitle?.url?.let { fetchSubtitleCues(it) }.orEmpty()
        android.util.Log.i("TnPlayer", "subtitle '${preferredSubtitle?.label}' cues=${subtitleCues.size}")
    }
    val subtitleText = subtitleCues.firstOrNull { position in it.startMs..it.endMs }?.text.orEmpty()

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) duration = exo.duration.coerceAtLeast(0L)
                if (s == Player.STATE_ENDED) { ended = true; WatchProgressStore.markFinished(arg.url, exo.duration) }
            }
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
            override fun onVideoSizeChanged(vs: androidx.media3.common.VideoSize) {
                // Real display dimensions (account for anamorphic pixel ratio) → portrait when taller
                // than wide. Confirms/corrects the source hint so short-drama locks portrait reliably.
                val par = if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f
                val w = vs.width * par
                val h = vs.height.toFloat()
                if (w > 0f && h > 0f) {
                    android.util.Log.i("TnPlayer", "videoSize ${vs.width}x${vs.height} par=$par -> vertical=${h > w}")
                    onVertical(h > w)
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                latestTracks = tracks
                val hs = sortedSetOf<Int>(compareByDescending { it })
                tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
                    for (i in 0 until g.length) g.getTrackFormat(i).height.let { if (it > 0) hs.add(it) }
                }
                trackHeights = hs.toList()
                // Honour reso priority (highest-first): force the top track instead of leaving ABR on "Auto".
                if (autoReso) hs.firstOrNull()?.let { if (it != selHeight) applyHeight(it) }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                android.util.Log.e("TnPlayer", "exo error ${e.errorCodeName}: ${e.message}", e)
                onError()
            }
        }
        exo.addListener(l)
        onDispose {
            // Remember where we left off (online + offline) so reopening resumes here.
            val pos = exo.currentPosition; val dur = exo.duration
            if (dur > 0L && pos > 0L) WatchProgressStore.save(arg.url, pos, dur)
            flushHeartbeats() // send the tail of this watch session before tearing down
            exo.removeListener(l); exo.release()
        }
    }
    // (Re)load when the chosen quality changes, preserving position.
    LaunchedEffect(quality) {
        val pos = exo.currentPosition
        val item = MediaItem.Builder()
            .setUri(quality.url)
            .apply {
            if (isHls(quality.url)) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            if (isMp4Like(quality.url)) setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
        }.build()
        exo.setMediaItem(item)
        exo.prepare()
        if (pos > 0) exo.seekTo(pos)
    }
    // Resume "lanjut tonton": once the duration is known, jump to the saved position (online or
    // offline). Initial load only — a mid-watch quality change preserves position via the effect above.
    var resumed by remember(arg.url) { mutableStateOf(false) }
    LaunchedEffect(duration > 0L) {
        if (duration > 0L && !resumed) {
            resumed = true
            val r = WatchProgressStore.resumePositionMs(arg.url)
            if (r in 1 until duration) exo.seekTo(r)
        }
    }
    // Once the episode length is known, fetch real OP/ED timestamps (anime w/ a MAL id only).
    LaunchedEffect(skipEligible, duration > 0L) {
        if (skipEligible && skip == null && duration > 0L) {
            skip = SkipResolver.fetch(arg.malId, arg.episodeNum!!, (duration / 1000).toInt())
        }
    }
    LaunchedEffect(Unit) {
        var saveTick = 0
        while (true) {
            position = exo.currentPosition.coerceAtLeast(0L)
            val d = exo.duration; if (d > 0L) duration = d
            // Auto-skip OP/ED when enabled and real timestamps exist — once per span.
            if (autoSkipOn) skip?.let { s ->
                s.op?.let { if (!opSkipped && position in it.startMs until it.endMs) { opSkipped = true; exo.seekTo(it.endMs) } }
                s.ed?.let { if (!edSkipped && position in it.startMs until it.endMs) { edSkipped = true; exo.seekTo(if (duration > 0L) it.endMs.coerceAtMost(duration - 1000L) else it.endMs) } }
            }
            // Persist watch position ~every 5s so reopening resumes (online + offline).
            if (++saveTick % 10 == 0 && duration > 0L && position > 0L) WatchProgressStore.save(arg.url, position, duration)
            // Emit a watch-telemetry heartbeat ~every 5s; flush the batch to the panel ~every 30s.
            if (saveTick % 10 == 0 && position > 0L) {
                heartbeats.add(
                    Heartbeat(
                        capturedAt = System.currentTimeMillis(),
                        playheadMs = position,
                        realtimeElapsedMs = android.os.SystemClock.elapsedRealtime() - sessionStartRt,
                        playbackRate = exo.playbackParameters.speed.toDouble(),
                        isForeground = true,
                        lastInteractionMs = lastInteractionAt,
                        episodeDurationMs = duration.coerceAtLeast(0L),
                    )
                )
                if (heartbeats.size > 100) heartbeats.subList(0, heartbeats.size - 100).clear()
            }
            if (saveTick % 60 == 0) flushHeartbeats()
            delay(500)
        }
    }
    // End of episode → 5s countdown → next episode (cancelable). Only when enabled + a next exists.
    LaunchedEffect(ended) {
        if (!ended || !hasNext || !autoNextOn) return@LaunchedEffect
        for (n in 5 downTo 1) {
            if (cancelNext) { nextIn = -1; return@LaunchedEffect }
            nextIn = n
            delay(1000)
        }
        nextIn = -1
        if (!cancelNext) onNext()
    }
    // Watchdog ("terlalu lama"): no first frame within the budget = dead/too-slow stream → hand off to
    // the next source via onError (the failover). Re-armed on each Resolusi change. A hard error still
    // fails instantly via onPlayerError; this only catches a stream that's SILENTLY stuck.
    LaunchedEffect(quality) {
        delay(12_000)
        if (exo.currentPosition <= 0L && !playing) {
            // A large progressive mp4 (Samehadaku movies run ~1 GB) may still be locating its moov atom /
            // buffering at 12s — killing it here wrongly drops a source that would play. Only bail now if
            // it's genuinely stuck (idle, nothing buffered); if it's actively buffering, grant more time.
            val progressing = exo.playbackState == Player.STATE_BUFFERING || exo.bufferedPosition > 0L
            if (!progressing) {
                android.util.Log.w("TnPlayer", "playback watchdog timeout for ${server.name} ${quality.label}")
                onError()
                return@LaunchedEffect
            }
            delay(25_000) // ~37s total for slow/large streams before giving up
            if (exo.currentPosition <= 0L && !playing) {
                android.util.Log.w("TnPlayer", "extended playback watchdog timeout for ${server.name} ${quality.label}")
                onError()
            }
        }
    }
    // TV D-pad model. While the fullscreen Box itself holds focus the remote drives PLAYBACK
    // (left/right = ∓10s, center = play/pause). Press Up/Down and we stop consuming, so Compose hands
    // focus to the overlay controls — Source / Resolusi pills above, prev-next transport + seekbar below —
    // where the rose TV focus ring shows the selection and center activates it. Back returns to playback.
    var boxFocused by remember { mutableStateOf(false) }

    // Auto-hide the controls after 4s — but never while the user is navigating them with the D-pad
    // (focus outside the Box), or their focus would be stranded on a hidden control.
    LaunchedEffect(controls, playing, menuOpen, boxFocused) {
        if (controls && playing && !menuOpen && boxFocused) { delay(4000); controls = false }
    }

    LaunchedEffect(menuOpen) { if (!menuOpen) focusRequester.requestFocus() } // re-grab focus after a dropdown closes

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onFocusChanged { boxFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (ev.key == Key.Back) {
                    return@onPreviewKeyEvent when {
                        menuOpen -> false
                        !boxFocused -> { focusRequester.requestFocus(); true } // controls → back to playback
                        controls -> { controls = false; true }
                        else -> { onBack(); true }
                    }
                }
                if (ev.key == Key.MediaPlayPause || ev.key == Key.Spacebar) {
                    exo.playWhenReady = !exo.playWhenReady
                    controls = true
                    lastInteractionAt = System.currentTimeMillis()
                    return@onPreviewKeyEvent true
                }
                // Focus sits on an overlay control → the D-pad belongs to Compose traversal, not playback.
                if (!boxFocused) return@onPreviewKeyEvent false
                // Controls hidden: any D-pad press just reveals them.
                if (!controls) {
                    controls = true
                    lastInteractionAt = System.currentTimeMillis()
                    return@onPreviewKeyEvent true
                }
                when (ev.key) {
                    Key.DirectionLeft -> {
                        // Single press = seek −10s.
                        exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                        lastInteractionAt = System.currentTimeMillis()
                        true
                    }
                    Key.DirectionRight -> {
                        exo.seekTo(exo.currentPosition + 10_000)
                        lastInteractionAt = System.currentTimeMillis()
                        true
                    }
                    // Hand focus to the overlay controls: Up = Source/Resolusi pills, Down = prev/play/next.
                    // (Plain traversal can't get there — they live inside this fullscreen focusable Box.)
                    Key.DirectionUp -> {
                        runCatching { topBarFocus.requestFocus() }.isSuccess
                    }
                    Key.DirectionDown -> {
                        runCatching { transportFocus.requestFocus() }.isSuccess
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        exo.playWhenReady = !exo.playWhenReady
                        lastInteractionAt = System.currentTimeMillis()
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exo
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    applyTetoNovaSubtitleStyle()
                    // TV: keep D-pad focus on the Compose key handler above, not this native view —
                    // otherwise the surface swallows the remote and our controls never react.
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                }
            },
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls; lastInteractionAt = System.currentTimeMillis() },
                    onDoubleTap = { off ->
                        val w = size.width
                        when {
                            off.x < w / 3f -> exo.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0))
                            off.x > w * 2f / 3f -> exo.seekTo(exo.currentPosition + 10_000)
                            else -> exo.playWhenReady = !exo.playWhenReady
                        }
                        controls = true
                        lastInteractionAt = System.currentTimeMillis()
                    },
                )
            },
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        // OP/ED skip is gated ENTIRELY on the "Skip opening" toggle (`autoSkipOn`): OFF → no chip and
        // no auto-skip at all. ON → anime with AniSkip data auto-seeks (handled in the poll loop, no
        // chip needed); donghua / anime without data show a manual heuristic chip (intro +85s, ending →
        // near the end), re-tappable; fine-tune with the seekbar / double-tap ±10s.
        if (autoSkipOn && skip == null) {
            val skipIntro = position in 5_000L..180_000L
            val skipEnding = duration > 0L && position > duration - 150_000L
            if (skipIntro) SkipChip("Lewati Intro ⏭") { exo.seekTo(position + 85_000L) }
            else if (skipEnding) SkipChip("Lewati Ending ⏭") { exo.seekTo((duration - 2_000L).coerceAtLeast(0L)) }
        }
        if (controls) {
            // Gradient scrim: dark at top + bottom (so the bars read), transparent through the middle so
            // the video stays visible — replaces the old flat dim. Matches the Player Redesign handoff.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(0.68f),
                        0.26f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1.0f to Color.Black.copy(0.72f),
                    ),
                ),
            )
            TopBar(arg.title, arg.episodeLabel, onBack, background = Color.Transparent) {
                SourcePill(servers, server, onPickServer, onExternal, onOpenChange = { menuOpen = it }, modifier = Modifier.focusRequester(topBarFocus))
                if (variants.size > 1) {
                    // Per-quality stream URLs (e.g. ok.ru, Rumble mp4 ladder).
                    Spacer(Modifier.width(8.dp))
                    Pill("Resolusi", quality.label, variants, { it.label }, { it.label == quality.label }, onOpenChange = { menuOpen = it }) { quality = it }
                } else if (trackHeights.size > 1) {
                    // Single adaptive HLS stream — pick from the manifest's variant heights (+ Auto).
                    Spacer(Modifier.width(8.dp))
                    val opts = listOf<Int?>(null) + trackHeights
                    Pill("Resolusi", selHeight?.let { "${it}p" } ?: "Auto", opts,
                        itemLabel = { it?.let { h -> "${h}p" } ?: "Auto" }, selected = { it == selHeight }, onOpenChange = { menuOpen = it }) { autoReso = false; applyHeight(it) }
                } else if (serverResolutionChoices(servers, server).size > 1) {
                    Spacer(Modifier.width(8.dp))
                    ServerResolutionPill(servers, server, onPickServer, onOpenChange = { menuOpen = it })
                } else if (preEmbedVariants(server).isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    PreEmbedResolutionPill(server, onPickServer, onOpenChange = { menuOpen = it })
                }
            }
            // bottom: accent progress bar + current / total time below it
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Slider(
                        value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { f -> if (duration > 0) { val p = (f * duration).toLong(); position = p; exo.seekTo(p); lastInteractionAt = System.currentTimeMillis() } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFE23A3A), inactiveTrackColor = Color.White.copy(0.25f)),
                        thumb = { Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White)) },
                    )
                }
                BottomTransportRow(fmtTime(position), fmtTime(duration)) {
                    if (hasPrev) EpisodeStepButton("⏮") { onPrev() }
                    PlayerToggleButton(playing, Modifier.focusRequester(transportFocus)) { exo.playWhenReady = !exo.playWhenReady }
                    if (hasNext) EpisodeStepButton("⏭") { onNext() }
                }
            }
        }
        if (subtitleText.isNotBlank()) SubtitleOverlay(subtitleText, controls)
        // Auto-next countdown overlay (after the episode ends; cancelable).
        if (nextIn > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Episode berikutnya dalam ${nextIn}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.18f))
                                .clickable { cancelNext = true; nextIn = -1 }.focusable().padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text("Batal", color = Color.White, fontSize = 13.sp) }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                                .clickable { onNext() }.focusable().padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text("Tonton sekarang ⏭", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeStepButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clickable(onClick = onClick).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = Color.White, fontSize = 22.sp)
    }
}

@Composable
private fun PlayerToggleButton(playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(42.dp).clickable(onClick = onClick).focusable(), contentAlignment = Alignment.Center) {
        if (playing) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(5.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
            Box(Modifier.size(5.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        } else {
            Text("▶", color = Color.White, fontSize = 22.sp)
        }
    }
}

@Composable
private fun BottomTransportRow(start: String, end: String, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(42.dp)) {
        Text(start, color = Color.White.copy(0.72f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterStart))
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
        Text(end, color = Color.White.copy(0.72f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** The rounded "Lewati Intro/Ending" pill, bottom-end. Used for both the AniSkip-driven manual skip
 *  and the heuristic fallback. */
@Composable
private fun BoxScope.SkipChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 56.dp)
            .clip(RoundedCornerShape(50)).background(Color.White.copy(0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------------------------------------
// WebView stage — fallback for hosts without an extractor (embed's own controls).
// ---------------------------------------------------------------------------------------------

@Composable
private fun BoxScope.SubtitleOverlay(text: String, controlsVisible: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bottomGap = if (maxHeight > maxWidth) maxHeight * 0.34f else if (controlsVisible) 96.dp else 52.dp
        Text(
            text = text,
            color = Color.White,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black.copy(alpha = 0.95f),
                    offset = Offset(1.6f, 1.6f),
                    blurRadius = 3.5f,
                ),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 26.dp)
                .padding(bottom = bottomGap),
        )
    }
}

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
    var menuOpen by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(barVisible, fullscreen, menuOpen) { if (barVisible && !fullscreen && !menuOpen) { delay(4000); barVisible = false } }
    LaunchedEffect(fullscreen) { if (fullscreen) barVisible = false }
    LaunchedEffect(menuOpen) { if (!menuOpen) focusRequester.requestFocus() } // re-grab focus after a dropdown closes
    val allowHost = remember(server.embedUrl) {
        runCatching { coreDomain(java.net.URI(server.embedUrl).host.orEmpty()) }.getOrDefault("")
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Back -> {
                        if (menuOpen) false
                        else if (barVisible && !fullscreen) {
                            barVisible = false
                            true
                        } else {
                            onBack()
                            true
                        }
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!barVisible && !fullscreen) {
                            barVisible = true
                            true
                        } else false
                    }
                    else -> false
                }
            },
    ) {
        key(server.embedUrl) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        keepScreenOn = true
                        // TV: don't let this WebView grab D-pad focus — keep it on the Compose key handler.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webChromeClient = fullscreenChromeClient(
                            ctx,
                            { fullscreen = true },
                            { fullscreen = false },
                        )
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
                TopBar(arg.title, arg.episodeLabel, onBack) {
                    SourcePill(servers, server, onPickServer, onExternal, onOpenChange = { menuOpen = it })
                    if (serverResolutionChoices(servers, server).size > 1) {
                        Spacer(Modifier.width(8.dp))
                        ServerResolutionPill(servers, server, onPickServer, onOpenChange = { menuOpen = it })
                    } else if (preEmbedVariants(server).isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        PreEmbedResolutionPill(server, onPickServer, onOpenChange = { menuOpen = it })
                    }
                }
            } else {
                Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(36.dp).clickable { barVisible = true })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// JWPlayer WebView stage — videoplayer.vip plays in the WebView (its master.txt 404s ExoPlayer),
// with the embed's own UI hidden and OUR controls overlaid, driven via the jwplayer() JS API.
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebPlayerStage(
    player: WebPlayer,
    embedUrl: String,
    title: String,
    episodeLabel: String?,
    servers: List<VideoServer>,
    current: VideoServer,
    onPickServer: (VideoServer) -> Unit,
    onServerFailed: () -> Boolean,
    onBack: () -> Unit,
    onExternal: () -> Unit,
) {
    var web by remember(embedUrl) { mutableStateOf<WebView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) } // seconds
    var duration by remember { mutableLongStateOf(0L) } // seconds
    var controls by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var qualities by remember { mutableStateOf<List<String>>(emptyList()) }
    var qualityIdx by remember { mutableStateOf(0) }
    var started by remember(embedUrl) { mutableStateOf(false) }
    var bridgeLogged by remember(embedUrl) { mutableStateOf(false) }
    // No more servers to try AND this one never started — show a terminal state instead of an endless
    // "Menyiapkan video…" spinner.
    var dead by remember(embedUrl) { mutableStateOf(false) }
    var reload by remember(embedUrl) { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    // TV D-pad, mirroring ExoStage: explicit focus hand-offs into the overlay controls (directional
    // traversal can't reach them — they're children of this fullscreen focusable Box).
    val topBarFocus = remember { FocusRequester() }
    val transportFocus = remember { FocusRequester() }
    var boxFocused by remember { mutableStateOf(false) }

    // Watchdog: JWPlayer/Dailymotion can fail to play (e.g. JW error 232404 — dead/geo-blocked playlist)
    // with no JS error we can observe, so a time budget guards the load. If no frame has rolled, hand off
    // to the next server (same failover as ExoStage/SniffStage); when none remain, stop spinning and let
    // the user open externally / go back instead of hanging forever.
    LaunchedEffect(embedUrl, reload) {
        delay(12_000)
        if (!started && !dead) { if (!onServerFailed()) dead = true }
    }
    // Abyss can start its native decoder while its JW compatibility shim stays silent. Do not throw
    // away a healthy source solely because that shim omitted state callbacks: reveal the attached
    // player after its normal startup window and keep manual Source switching available if it is dead.
    LaunchedEffect(embedUrl, reload) {
        if (isHydraxEmbed(embedUrl)) {
            delay(8_000)
            if (!started && !dead) {
                started = true
                android.util.Log.i("TnPlayer", "web source '${current.name}' revealed after Abyss startup grace")
            }
        }
    }
    if (dead) {
        NoSourceBox(onRetry = { started = false; dead = false; reload++ }, onExternal = onExternal, onBack = onBack)
        return
    }

    // Poll the host's state (and keep its UI hidden) ~every 700ms.
    LaunchedEffect(web) {
        val wv = web ?: return@LaunchedEffect
        while (true) {
            delay(700)
            wv.evaluateJavascript(player.stateJs) { r ->
                // evaluateJavascript JSON-encodes a returned string. Decode the outer JSON value;
                // hand-unescaping quotes silently discarded state on some WebView versions.
                val json = runCatching {
                    when (val decoded = org.json.JSONTokener(r.orEmpty()).nextValue()) {
                        is String -> decoded
                        is org.json.JSONObject -> decoded.toString()
                        else -> null
                    }
                }.getOrNull()?.takeIf { it.startsWith("{") }
                if (json != null) runCatching {
                    val o = org.json.JSONObject(json)
                    val state = o.optString("st")
                    playing = state.equals("playing", ignoreCase = true)
                    o.optLong("dur").let { if (it > 0L) duration = it }
                    position = o.optLong("pos")
                    o.optJSONArray("q")?.let { a -> qualities = (0 until a.length()).map { a.optString(it) } }
                    qualityIdx = o.optInt("qi").coerceAtLeast(0)
                    if (!bridgeLogged) {
                        bridgeLogged = true
                        android.util.Log.i("TnPlayer", "web bridge '${current.name}' state=$state pos=$position dur=$duration qualities=$qualities")
                    }
                    // A confirmed `playing` state is sufficient. Abyss can decode audio/video before its
                    // getCurrentTime clock advances; requiring pos>0 produced a false watchdog failure and
                    // skipped a healthy Server 2. Position/duration remain useful for seek/progress afterward.
                    if (!started) {
                        if (playing) {
                            started = true
                            android.util.Log.i(
                                "TnPlayer",
                                "web source '${current.name}' started state=$state pos=$position dur=$duration qualities=$qualities",
                            )
                        } else {
                            wv.evaluateJavascript(player.playKick, null)
                        }
                    }
                }
            }
        }
    }
    // Never auto-hide while the user is navigating the controls with the D-pad (focus outside the Box),
    // or their focus would be stranded on a control that just disappeared.
    LaunchedEffect(controls, playing, menuOpen, boxFocused) {
        if (controls && playing && !menuOpen && boxFocused) { delay(4000); controls = false }
    }
    LaunchedEffect(menuOpen) { if (!menuOpen) focusRequester.requestFocus() } // re-grab focus after a dropdown closes

    fun js(code: String) { web?.evaluateJavascript(code, null) }
    fun toggle() = js(player.toggle)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onFocusChanged { boxFocused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (ev.key == Key.Back) {
                    return@onPreviewKeyEvent when {
                        menuOpen -> false
                        !boxFocused -> { focusRequester.requestFocus(); true } // controls → back to playback
                        controls && !fullscreen -> { controls = false; true }
                        else -> { onBack(); true }
                    }
                }
                if (ev.key == Key.MediaPlayPause || ev.key == Key.Spacebar) {
                    toggle()
                    controls = true
                    return@onPreviewKeyEvent true
                }
                // Focus sits on an overlay control → the D-pad belongs to Compose traversal, not playback.
                if (!boxFocused) return@onPreviewKeyEvent false
                if (!controls) { // controls hidden: any D-pad press just reveals them
                    controls = true
                    return@onPreviewKeyEvent true
                }
                when (ev.key) {
                    Key.DirectionLeft -> {
                        // Single press = seek −10s.
                        js(player.seekAbs((position - 10).coerceAtLeast(0)))
                        true
                    }
                    Key.DirectionRight -> {
                        js(player.seekAbs(position + 10))
                        true
                    }
                    // Hand focus to the overlay controls: Up = Source/Resolusi pills, Down = prev/play/next.
                    Key.DirectionUp -> runCatching { topBarFocus.requestFocus() }.isSuccess
                    Key.DirectionDown -> runCatching { transportFocus.requestFocus() }.isSuccess
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        toggle()
                        true
                    }
                    else -> false
                }
            },
    ) {
        key(embedUrl, reload) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        keepScreenOn = true
                        // TV: don't let this WebView grab D-pad focus — keep it on the Compose key handler.
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString = DESKTOP_UA
                        // This screen is already immersive fullscreen. Reject the host's custom view so
                        // it cannot cover TetoNova's Source/Resolusi overlay.
                        webChromeClient = embeddedPlayerChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (isAdHost(request.url.host.orEmpty()))
                                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                val mediaUrl = request.url.toString()
                                val low = mediaUrl.lowercase()
                                if (looksLikeStream(mediaUrl) || "/sora/" in low || "/mediastorage/" in low) {
                                    Handler(Looper.getMainLooper()).post {
                                        if (!started) {
                                            started = true
                                            android.util.Log.i("TnPlayer", "web source '${current.name}' media request started: ${mediaUrl.take(96)}")
                                        }
                                    }
                                }
                                return null
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.isForMainFrame && !player.allowHost(request.url.host.orEmpty()) // block ad redirects
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (player.setupJs.isNotEmpty()) view.evaluateJavascript(player.setupJs, null)
                            }
                        }
                        player.load(this)
                    }
                },
                update = { web = it },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // Until the video is actually rolling, cover the WebView with an opaque loading screen so the
        // user never sees JWPlayer's startup clutter (resume dialog, ad ✕, server menu, grey play poster).
        // The WebView stays attached & full-size behind the cover, so Chromium keeps decoding/playing.
        if (!started) {
            LoadingBox("Menyiapkan video…")
            Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).size(38.dp).clickable { onBack() }.focusable(),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            return@Box
        }
        // Tap layer: toggle controls; double-tap L/R seeks ∓10s via jwplayer().
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls },
                    onDoubleTap = { off ->
                        val w = size.width
                        when {
                            off.x < w / 3f -> js(player.seekAbs((position - 10).coerceAtLeast(0)))
                            off.x > w * 2f / 3f -> js(player.seekAbs(position + 10))
                            else -> toggle()
                        }
                        controls = true
                    },
                )
            },
        )
        if (controls && !fullscreen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.25f)))
            TopBar(title, episodeLabel, onBack) {
                SourcePill(servers, current, onPickServer, onExternal, onOpenChange = { menuOpen = it }, modifier = Modifier.focusRequester(topBarFocus))
                if (qualities.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Pill("Resolusi", qualities.getOrElse(qualityIdx) { "Auto" }, qualities.indices.toList(),
                        itemLabel = { qualities[it] }, selected = { it == qualityIdx }, onOpenChange = { menuOpen = it }) { idx ->
                        qualityIdx = idx
                        js(player.setQuality(idx))
                    }
                } else if (serverResolutionChoices(servers, current).size > 1) {
                    Spacer(Modifier.width(8.dp))
                    ServerResolutionPill(servers, current, onPickServer, onOpenChange = { menuOpen = it })
                } else if (preEmbedVariants(current).isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    PreEmbedResolutionPill(current, onPickServer, onOpenChange = { menuOpen = it })
                }
            }
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Slider(
                        value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { f -> if (duration > 0L) { val p = (f * duration).toLong(); position = p; js(player.seekAbs(p)) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)),
                        thumb = { Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White)) },
                    )
                }
                BottomTransportRow(fmtTime(position * 1000), fmtTime(duration * 1000)) {
                    PlayerToggleButton(playing, Modifier.focusRequester(transportFocus)) { toggle() }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.TopBar(title: String, episodeLabel: String?, onBack: () -> Unit, background: Color = Color.Black.copy(0.45f), trailing: @Composable () -> Unit) {
    Row(
        Modifier.align(Alignment.TopStart).fillMaxWidth().background(background).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clickable { onBack() }.focusable(), contentAlignment = Alignment.Center) {
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

/** Strip the source's parenthetical hint (anichin appends "[Ganti kalo gak ada suaranya]", "[ADS]", …)
 *  so the compact pill stays short; the full label is still shown in the dropdown. */
private fun shortServerName(name: String): String =
    name.replace(Regex("\\s*\\[[^\\]]*\\]"), "").trim().ifBlank { name }

private fun sameSource(a: VideoServer, b: VideoServer): Boolean =
    a.name == b.name

private data class SourceProviderChoice(val label: String, val server: VideoServer)

private data class ServerResolutionChoice(val label: String, val server: VideoServer)

private val namedServerResolutionRegex = Regex("""(?i)\b(2160|1440|1080|720|480|360|240)\s*p\b""")

private fun namedServerResolution(server: VideoServer): String? =
    namedServerResolutionRegex.find(shortServerName(server.name))
        ?.groupValues
        ?.getOrNull(1)
        ?.let { "${it}p" }

private fun serverProviderLabel(server: VideoServer): String {
    val cleaned = shortServerName(server.name)
    return namedServerResolutionRegex
        .replace(cleaned, "")
        .replace(Regex("""\s*[-_/|]+\s*"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { cleaned }
}

private fun preferredServer(servers: List<VideoServer>, current: VideoServer, preferResolution: String? = null): VideoServer {
    val currentProvider = serverProviderLabel(current)
    return servers.firstOrNull { sameSource(it, current) }
        ?: servers.firstOrNull { preferResolution != null && serverProviderLabel(it) == currentProvider && namedServerResolution(it) == preferResolution }
        ?: servers.firstOrNull { preferResolution != null && namedServerResolution(it) == preferResolution }
        ?: servers.minWithOrNull(compareBy<VideoServer>({ speedRank(it) }, { -bestServerHeight(it) }, { it.name.lowercase() }))
        ?: servers.first()
}

private fun sourceProviderChoices(servers: List<VideoServer>, current: VideoServer): List<SourceProviderChoice> {
    val currentResolution = namedServerResolution(current)
    val grouped = linkedMapOf<String, MutableList<VideoServer>>()
    servers.forEach { grouped.getOrPut(serverProviderLabel(it)) { mutableListOf() }.add(it) }
    return grouped.map { (label, group) ->
        SourceProviderChoice(label, preferredServer(group, current, currentResolution))
    }
}

private fun serverResolutionChoices(servers: List<VideoServer>, current: VideoServer): List<ServerResolutionChoice> {
    val grouped = linkedMapOf<String, MutableList<VideoServer>>()
    servers.forEach { server ->
        val label = namedServerResolution(server) ?: return@forEach
        grouped.getOrPut(label) { mutableListOf() }.add(server)
    }
    return grouped.map { (label, group) ->
        ServerResolutionChoice(label, preferredServer(group, current, label))
    }.sortedByDescending { resoHeight(it.label) }
}

private fun preEmbedVariants(server: VideoServer): List<ServerVariant> =
    server.variants.takeIf { it.size > 1 }.orEmpty()

private fun currentPreEmbedLabel(server: VideoServer): String =
    server.variants.firstOrNull { it.embedUrl == server.embedUrl }?.label
        ?: server.variants.maxByOrNull { resoHeight(it.label) }?.label
        ?: "Auto"

/** Source-server picker pill + dropdown (with an "open externally" tail). */
@Composable
private fun SourcePill(
    servers: List<VideoServer>,
    current: VideoServer,
    onPick: (VideoServer) -> Unit,
    onExternal: () -> Unit,
    onOpenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val choices = remember(servers, current) { sourceProviderChoices(servers, current) }
    val currentLabel = remember(current) { serverProviderLabel(current) }
    LaunchedEffect(open) { onOpenChange(open) } // let the player pause its controls auto-hide while open
    Box {
        PillRow("Source", currentLabel, modifier) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = playerPopupProperties()) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label + if (choice.label == currentLabel) "  ✓" else "") },
                    onClick = { onPick(choice.server); open = false },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Buka di player luar") }, onClick = { onExternal(); open = false })
        }
    }
}

@Composable
private fun ServerResolutionPill(servers: List<VideoServer>, current: VideoServer, onPickServer: (VideoServer) -> Unit, onOpenChange: (Boolean) -> Unit = {}) {
    val choices = remember(servers, current) { serverResolutionChoices(servers, current) }
    val currentLabel = namedServerResolution(current) ?: choices.firstOrNull { sameSource(it.server, current) }?.label ?: "Auto"
    if (choices.size <= 1) return
    Pill(
        label = "Resolusi",
        value = currentLabel,
        items = choices,
        itemLabel = { it.label },
        selected = { it.label == currentLabel },
        onOpenChange = onOpenChange,
    ) { picked ->
        onPickServer(picked.server)
    }
}

@Composable
private fun PreEmbedResolutionPill(server: VideoServer, onPickServer: (VideoServer) -> Unit, onOpenChange: (Boolean) -> Unit = {}) {
    val variants = preEmbedVariants(server)
    Pill(
        label = "Resolusi",
        value = currentPreEmbedLabel(server),
        items = variants,
        itemLabel = { it.label },
        selected = { it.embedUrl == server.embedUrl },
        onOpenChange = onOpenChange,
    ) { picked ->
        onPickServer(server.copy(embedUrl = picked.embedUrl))
    }
}

/** Generic labelled picker pill (used for Resolusi). */
@Composable
private fun <T> Pill(label: String, value: String, items: List<T>, itemLabel: (T) -> String, selected: (T) -> Boolean, onOpenChange: (Boolean) -> Unit = {}, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpenChange(open) } // let the player pause its controls auto-hide while open
    Box {
        PillRow(label, value) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = playerPopupProperties()) {
            items.forEach { it2 ->
                DropdownMenuItem(text = { Text(itemLabel(it2) + if (selected(it2)) "  ✓" else "") }, onClick = { onPick(it2); open = false })
            }
        }
    }
}

@Composable
private fun PillRow(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.15f)).clickable { onClick() }.focusable().padding(horizontal = 10.dp, vertical = 7.dp),
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
private fun NoSourceBox(onRetry: () -> Unit, onExternal: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Nggak nemu source video buat episode ini", color = Color.White, fontWeight = FontWeight.Bold)
            // Kuramanime's player token is rate-limited on quick re-opens, so a manual retry (after a beat)
            // usually succeeds — make it the primary action.
            Spacer(Modifier.height(12.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { onRetry() }.focusable().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Coba lagi", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("Buka di player luar", color = Color.White.copy(0.7f), modifier = Modifier.clickable { onExternal() }.focusable().padding(8.dp))
            Spacer(Modifier.height(2.dp))
            Text("Kembali", color = Color.White.copy(0.7f), modifier = Modifier.clickable { onBack() }.focusable().padding(8.dp))
        }
    }
}

private fun fmtTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Keep the app's own Source/Resolusi controls above embeds that request HTML5 fullscreen. */
private fun embeddedPlayerChromeClient() = object : WebChromeClient() {
    override fun onShowCustomView(
        view: android.view.View,
        callback: WebChromeClient.CustomViewCallback,
    ) {
        callback.onCustomViewHidden()
    }

    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
        android.util.Log.i(
            "WebStage",
            "console ${msg.messageLevel()}: ${msg.message().take(300)} " +
                "@${msg.sourceId()?.take(80)}:${msg.lineNumber()}",
        )
        return true
    }
}

/** WebChromeClient supporting the embed's HTML5 fullscreen so our overlay bar can hide. */
private fun fullscreenChromeClient(
    ctx: android.content.Context,
    onEnter: () -> Unit,
    onExit: () -> Unit,
) = object : WebChromeClient() {
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
    // Embed players fail silently on-device otherwise — surface their console to logcat.
    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
        android.util.Log.i("WebStage", "console ${msg.messageLevel()}: ${msg.message().take(300)} @${msg.sourceId()?.take(80)}:${msg.lineNumber()}")
        return true
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
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (isAdHost(request.url.host.orEmpty())) return emptyResponse()
        return null
    }
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host.orEmpty()
        if (request.isForMainFrame && allowHost.isNotEmpty() && !host.endsWith(allowHost)) return true // block
        return false
    }
    override fun onPageFinished(view: WebView, url: String?) {
        view.evaluateJavascript(STRIP_ADS_JS, null)
        // Autoplay nudge. Custom players mount their <video> + big play-button
        // asynchronously AFTER onPageFinished, so a one-shot injection runs too early and finds nothing.
        // Poll every 400ms for ~12s: call video.play() (muted-retry when the browser blocks unmuted
        // autoplay), click any known play button, and dispatch a synthetic pointer click at the player's
        // centre (many custom overlays only start on a real gesture). Stops once the video is rolling.
        view.evaluateJavascript(
            "(function(){var n=0;var iv=setInterval(function(){n++;try{" +
                "var v=document.querySelector('video');" +
                "if(v){if(!v.paused&&v.currentTime>0){clearInterval(iv);return;}v.muted=false;var p=v.play&&v.play();if(p&&p.catch){p.catch(function(){v.muted=true;v.play&&v.play();});}}" +
                "var b=document.querySelector('.vjs-big-play-button,.ytp-large-play-button,button[aria-label*=\"lay\" i],.play-button,.play,.jw-icon-display,.plyr__control--overlaid,#play,.vplayer-play,.play-btn,.btn-play,[class*=\"play\"]');" +
                "if(b){b.click();}" +
                "var c=v||document.querySelector('#player,.player,.video-js,#video,.jwplayer')||document.body;" +
                "if(c){var r=c.getBoundingClientRect();var x=r.left+r.width/2,y=r.top+r.height/2;['mousedown','mouseup','click'].forEach(function(t){c.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y}));});}" +
                "}catch(e){}if(n>30)clearInterval(iv);},400);})();",
            null,
        )
    }
}

/**
 * Order servers fastest-first for auto-pick + failover ("terkencang dulu"): pre-resolved direct per-
 * resolution streams (kuramadrive/otakudesu) start instantly; first-party direct mp4 next; clean
 * players we drive in our own controls (JWPlayer/ok.ru); packed-JS HLS embeds; ad-heavy / WebView-only last.
 */
/** Wraps an HTTP [DataSource.Factory] to drop the byte-`Range` on the initial (position-0) read, forcing
 *  a plain full-file GET. wibufile's CDN throttles any ranged request to ~30 KB/s but serves a no-Range
 *  GET at ~150 KB/s, so this lets its progressive mp4 (esp. ~1 GB movies with a 2+ MB moov) buffer in time
 *  instead of crawling until the watchdog fails it. Seeks (position>0) keep their Range as normal. */
@OptIn(UnstableApi::class)
/** IPv4-only OkHttp client for ExoPlayer's Dailymotion streams — must match the extractor's IPv4-only
 *  metadata fetch so the IP-bound HLS token stays valid across resolve → playback (see ExoStage). */
private val playerOkHttp: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                okhttp3.Dns.SYSTEM.lookup(hostname).filterIsInstance<java.net.Inet4Address>()
                    .ifEmpty { okhttp3.Dns.SYSTEM.lookup(hostname) }
        })
        .build()
}

/**
 * Keep `(` and `)` LITERAL in request URLs. Dailymotion's HLS child-playlist tokens are a path segment
 * `sec2(<token>)/…`; ExoPlayer percent-encodes the parens to `%28`/`%29`, which the CDN rejects with 403
 * (verified: literal → 200, encoded → 403, deterministic). The master playlist has no parens (its token
 * is an alphanumeric query param), so it opens fine and only the child 403s — exactly the on-device
 * symptom. Undo the encoding here, right before the HTTP open. No-op for URLs without those bytes.
 */
private class ParenLiteralFactory(private val delegate: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val ds = delegate.createDataSource()
        return object : DataSource by ds {
            override fun open(dataSpec: DataSpec): Long {
                val s = dataSpec.uri.toString()
                val fixed = s.replace("%28", "(").replace("%29", ")")
                return ds.open(if (fixed == s) dataSpec else dataSpec.buildUpon().setUri(Uri.parse(fixed)).build())
            }
        }
    }
}

private const val TS_PACKET_SIZE = 188

internal fun findTsPayloadOffset(data: ByteArray, length: Int = data.size): Int {
    val end = length.coerceAtMost(data.size) - TS_PACKET_SIZE * 2
    for (i in 0 until end) {
        if (data[i] == 0x47.toByte() &&
            data[i + TS_PACKET_SIZE] == 0x47.toByte() &&
            data[i + TS_PACKET_SIZE * 2] == 0x47.toByte()
        ) return i
    }
    return -1
}

/**
 * TurboVIP stores each MPEG-TS segment after a small valid PNG file on Googleusercontent. Media3 sees
 * the PNG signature and rejects the segment as malformed, so expose only the appended TS payload.
 */
private class TurboVipPngTsFactory(private val delegate: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val ds = delegate.createDataSource()
        var unwrap = false
        var prepared = false
        var buffered = ByteArray(0)
        var bufferedAt = 0
        var mediaSkip = 0L

        fun prepare() {
            val startup = ByteArray(16 * 1024)
            var count = 0
            while (count < startup.size) {
                val read = ds.read(startup, count, startup.size - count)
                if (read == C.RESULT_END_OF_INPUT) break
                count += read
                val payloadAt = findTsPayloadOffset(startup, count)
                if (payloadAt < 0) continue

                val from = payloadAt + minOf(mediaSkip, (count - payloadAt).toLong()).toInt()
                mediaSkip -= from - payloadAt
                buffered = startup.copyOfRange(from, count)
                val scratch = ByteArray(8 * 1024)
                while (mediaSkip > 0) {
                    val skipped = ds.read(scratch, 0, minOf(mediaSkip, scratch.size.toLong()).toInt())
                    if (skipped == C.RESULT_END_OF_INPUT) throw java.io.IOException("TurboVIP segment ended while seeking")
                    mediaSkip -= skipped
                }
                prepared = true
                return
            }
            throw java.io.IOException("TurboVIP MPEG-TS payload not found")
        }

        return object : DataSource by ds {
            override fun open(dataSpec: DataSpec): Long {
                unwrap = dataSpec.uri.host?.endsWith("googleusercontent.com", ignoreCase = true) == true
                prepared = false
                buffered = ByteArray(0)
                bufferedAt = 0
                mediaSkip = if (unwrap) dataSpec.position else 0L
                val spec = if (unwrap)
                    dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
                else dataSpec
                val length = ds.open(spec)
                return if (unwrap) C.LENGTH_UNSET.toLong() else length
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (unwrap && !prepared) prepare()
                if (bufferedAt < buffered.size) {
                    val copied = minOf(length, buffered.size - bufferedAt)
                    buffered.copyInto(buffer, offset, bufferedAt, bufferedAt + copied)
                    bufferedAt += copied
                    return copied
                }
                return ds.read(buffer, offset, length)
            }

            override fun close() {
                try {
                    ds.close()
                } finally {
                    unwrap = false
                    prepared = false
                    buffered = ByteArray(0)
                    bufferedAt = 0
                    mediaSkip = 0L
                }
            }
        }
    }
}

private class NoInitialRangeFactory(private val delegate: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        val ds = delegate.createDataSource()
        return object : DataSource by ds {
            override fun open(dataSpec: DataSpec): Long {
                val spec = if (dataSpec.position == 0L && dataSpec.length != C.LENGTH_UNSET.toLong())
                    dataSpec.buildUpon().setLength(C.LENGTH_UNSET.toLong()).build() else dataSpec
                return ds.open(spec)
            }
        }
    }
}

private fun speedRank(s: VideoServer): Int {
    val host = s.embedUrl.lowercase()
    val name = s.name.lowercase()
    return when {
        // Blogger (anoboy Btube): plays only in its own WebView player (embed-only, needs a play tap).
        // It carries resolution variants too, but must rank BELOW the sniffable yourupload (YUp) mirror
        // so OUR ExoPlayer + picker is auto-picked whenever an episode has both; Blogger stays the
        // fallback for Btube-only episodes. Must come before the variants rule (which would give it 0).
        "blogger.com" in host || "blogspot.com" in host -> 4
        s.variants.isNotEmpty() -> 0
        // NontonAnimeID's native servers carry the embed-page URL here; it XOR-decodes to a direct
        // googlevideo/.mp4/.m3u8 stream, so rank it with the other pre-resolved direct streams (not the
        // "else" bucket, where it would lose the default pick to a third-party ok.ru mirror).
        "desustream" in host || "filedon" in host || "googlevideo" in host ||
            "kotakanimeid.link/video-embed" in host || "upns.live" in host ||
            "embed4me.vip" in host || "playerp2p.online" in host || "seekplays.pro" in host ||
            host.substringBefore('?').endsWith(".mp4") -> 1
        isJwPlayerHost(s.embedUrl) || "ok.ru" in host || "okru" in name ||
            // wibufile (Samehadaku): both the "720p/1080p" direct .mp4 rows (caught above) and the "480p"
            // api.wibufile.com/embed JWPlayer resolve to a progressive .mp4 in ExoPlayer, so rank the embed
            // here too — ahead of the WebView-only Blogspot/Mega rows so it wins the default pick.
            "wibufile" in host ||
            // emturbovid (PusatFilm's "Turbovip") is a JWPlayer host our generic extractor resolves to a
            // direct HLS stream — the one PusatFilm mirror that plays in ExoPlayer, so pick it before the
            // packed-JS / WebView-only mirrors (rapidplay/hydrax/gdriveplayer) that need the WebView sniff.
            "emturbovid" in host || "turbovid" in host -> 2
        "playeriframe.sbs" in host -> when {
            "hydrax" in host || "hydrax" in name -> 3
            "cast" in host || "cast" in name -> 4
            "turbovip" in host || "turbovip" in name -> 5
            else -> 6
        }
        "p2p" in name || "hownetwork" in host -> 3
        // Hydrax/Abyss exposes a progressive MP4 after its gate; the sniffer hands that URL to ExoPlayer.
        // Rank it right after the direct stream so it's the
        // FIRST failover when Turbovip is dead (PusatFilm), ahead of the flaky packed-JS mirrors below.
        "playhydrax" in host || "hydrax" in host || "abyss.to" in host || "abyssplayer" in host || "gn1r5n" in host ||
            "filemoon" in host || "filelions" in host || "vidhide" in host || "lulustream" in host || "rumble" in host -> 3
        isDailymotionHost(s.embedUrl) || "[ads]" in name -> 5
        else -> 4
    }
}

/** Resolution height from a variant label, robust to label variants — for highest-first priority. */
private fun resoHeight(label: String): Int {
    val l = label.lowercase()
    return when {
        "2160" in l || "4k" in l || "uhd" in l -> 2160
        "1440" in l || "2k" in l -> 1440
        "1080" in l || "fhd" in l -> 1080
        "720" in l || l == "hd" -> 720
        "480" in l -> 480
        "360" in l -> 360
        "240" in l -> 240
        else -> label.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}

private fun bestServerHeight(server: VideoServer): Int =
    server.variants.maxOfOrNull { resoHeight(it.label) } ?: resoHeight(server.name)
