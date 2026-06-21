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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.tetonova.core.scraper.ExtractResult
import com.tetonova.core.scraper.LiveSource
import com.tetonova.core.scraper.StreamExtractor
import com.tetonova.core.scraper.StreamVariant
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
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app player. Servers are scraped live from the episode's watch page (per-episode); the chosen
 * server is resolved by [StreamExtractor] into direct stream variants and played in the app's own
 * ExoPlayer (consistent controls + Resolusi picker). Hosts without an extractor fall back to a
 * WebView embed. Top bar (back · title · Source) is the same regardless of source.
 */
@Composable
fun PlayerScreen(arg: PlayerArg, onBack: () -> Unit, hasNext: Boolean = false, onNext: () -> Unit = {}) {
    val context = LocalContext.current
    val referer = arg.url.orEmpty()
    // If this episode is already downloaded, play it straight from the offline cache (no network,
    // no server resolution) — the same ExoStage, just fed a cache-backed data source.
    val offlineVariant = remember(arg.url) { DownloadCenter.offlineVariant(arg.url) }

    // Force landscape while playing. MainActivity has configChanges=orientation|screenSize so this does
    // NOT recreate the activity. Restore on exit. (Immersive is handled by the sticky effect below.)
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    // Opening a Compose dropdown (Source/Resolusi) spawns a focusable popup window; while it's focused
    // the system shows the bars, and on close the activity doesn't re-assert immersive on its own. Use
    // legacy IMMERSIVE_STICKY AND re-apply it whenever the activity window regains focus (popup closed).
    @Suppress("DEPRECATION")
    DisposableEffect(Unit) {
        val decor = (context as? Activity)?.window?.decorView
        val sticky = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        decor?.systemUiVisibility = sticky
        val focusL = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) decor?.systemUiVisibility = sticky
        }
        decor?.viewTreeObserver?.addOnWindowFocusChangeListener(focusL)
        onDispose {
            decor?.viewTreeObserver?.removeOnWindowFocusChangeListener(focusL)
            decor?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    var loading by remember { mutableStateOf(true) }
    var servers by remember { mutableStateOf<List<VideoServer>>(emptyList()) }
    var selected by remember { mutableStateOf<VideoServer?>(null) }
    var retryTick by remember { mutableStateOf(0) } // bumped by the NoSource "Coba lagi" button to re-resolve
    // Servers that failed auto-play (extraction / playback error / 404 / too-slow) — skipped on failover.
    val failed = remember { mutableStateListOf<String>() }
    fun keyOf(s: VideoServer) = s.name + "|" + s.embedUrl

    LaunchedEffect(arg.url, retryTick) {
        loading = true
        if (offlineVariant != null) { loading = false; return@LaunchedEffect } // offline: no server scrape
        failed.clear()
        // Every playable server, FASTEST-FIRST (pre-resolved direct streams → clean players → embeds),
        // so auto-pick plays the quickest source and failover walks the rest in that order.
        var list = arg.url?.let { runCatching { LiveSource.servers(it) }.getOrNull() }.orEmpty()
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
        list = list.sortedWith(compareBy({ speedRank(it) }, { it.name }))
        servers = list
        selected = list.firstOrNull() // auto-pick the fastest source
        android.util.Log.i("TnPlayer", "servers (fastest-first): ${list.map { it.name }}; auto-pick '${list.firstOrNull()?.name}'")
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

    // Auto-failover: mark the current server failed and jump to the next-fastest untried one. Returns
    // false when none remain (caller then drops to the WebView fallback for the last server).
    fun onServerFailed(): Boolean {
        val failedName = selected?.name
        selected?.let { if (keyOf(it) !in failed) failed.add(keyOf(it)) }
        val next = servers.firstOrNull { keyOf(it) !in failed }
        if (next != null) selected = next
        android.util.Log.i("TnPlayer", "server '$failedName' failed → ${next?.name ?: "none (WebView fallback)"}")
        return next != null
    }
    // Manual pick: try the chosen source; if it can't play it still auto-switches to the next that can.
    fun onPickServer(s: VideoServer) { failed.remove(keyOf(s)); selected = s }

    val current = selected
    if (offlineVariant != null) {
        // Downloaded → play from cache through ExoStage with a synthetic "Tersimpan" server.
        val offServer = remember(arg.url) { VideoServer("Tersimpan", arg.url.orEmpty()) }
        ExoStage(
            variants = listOf(offlineVariant),
            headers = DownloadCenter.headersFor(arg.url),
            arg = arg, server = offServer, servers = listOf(offServer),
            onPickServer = {}, onBack = onBack, onExternal = ::openExternal, onError = {},
            hasNext = hasNext, onNext = onNext,
            dataSourceFactory = DownloadCenter.cacheFactory(),
        )
    } else when {
        loading -> LoadingBox("Mencari source…")
        current == null -> NoSourceBox(onRetry = { retryTick++ }, onExternal = ::openExternal, onBack = onBack)
        else -> ServerPlayer(current, arg, servers, referer, ::onPickServer, ::onServerFailed, onBack, ::openExternal, hasNext, onNext)
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
) {
    // Hosts whose token streams 404/403 ExoPlayer (browser-context anti-leech) but play fine in a
    // WebView — JWPlayer (videoplayer.vip) and Dailymotion. Play them in the WebView with the host UI
    // hidden and OUR controls overlaid, driven via the host's JS/postMessage API.
    val webPlayer = when {
        isJwPlayerHost(server.embedUrl) -> jwPlayer(server.embedUrl, referer)
        isDailymotionHost(server.embedUrl) -> dmPlayer(server.embedUrl, referer)
        else -> null
    }
    if (webPlayer != null) {
        WebPlayerStage(webPlayer, server.embedUrl, arg.title, arg.episodeLabel, servers, server, onPickServer, onServerFailed, onBack, onExternal)
        return
    }
    var phase by remember(server) { mutableStateOf<Phase>(Phase.Extracting) }
    LaunchedEffect(server) {
        // 1) static extractor (ok.ru/dailymotion/rumble/filemoon) → 2) WebView sniffer → 3) WebView embed.
        val res = runCatching { StreamExtractor.extract(server, referer) }.getOrDefault(ExtractResult(emptyList()))
        android.util.Log.i("TnPlayer", "extract '${server.name}' (${server.embedUrl.take(64)}) → ${res.variants.size} variants ${res.variants.map { it.label }}${if (res.variants.isEmpty()) " → sniff" else ""}")
        phase = if (res.variants.isNotEmpty()) Phase.Exo(res.variants, res.headers) else Phase.Sniffing
    }
    // A server that won't play (sniff failed / playback error / too slow) hands off to the next-fastest
    // candidate; only when none remain do we drop to the WebView embed for the last server.
    val onFail = { if (!onServerFailed()) phase = Phase.Web }
    when (val p = phase) {
        Phase.Extracting -> LoadingBox("Menyiapkan video…")
        Phase.Sniffing -> SniffStage(
            embedUrl = server.embedUrl, referer = referer,
            onSniffed = { url, h -> phase = Phase.Exo(listOf(StreamVariant("Auto", url)), h) },
            onFail = onFail,
        )
        is Phase.Exo -> ExoStage(p.variants, p.headers, arg, server, servers, onPickServer, onBack, onExternal, onError = onFail, hasNext = hasNext, onNext = onNext)
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
private const val JW_STATE_JS =
    "(function(){try{if(!window.jwplayer)return '';var p=jwplayer();if(!p.getState)return '';" +
        "try{p.setControls(false);}catch(e){}" +
        // Kill full-screen fixed overlays that contain no <video> (ad/click-catchers covering the player).
        "try{for(var i=0;i<8;i++){var t0=document.elementFromPoint(innerWidth/2,innerHeight/2);" +
        "if(t0&&(t0.tagName=='DIV'||t0.tagName=='IFRAME')&&!t0.querySelector('video')){t0.style.display='none';}else break;}}catch(e){}" +
        "try{p.resize(window.innerWidth,window.innerHeight);}catch(e){}" +
        // Hide videoplayer.vip's \"Welcome back / resume watching?\" dialog (the small div holding that text).
        "try{var dv=document.querySelectorAll('div');for(var j=0;j<dv.length;j++){var e=dv[j];var tx=(e.textContent||'');if(/resume watching|welcome back/i.test(tx)&&tx.length<170){e.style.display='none';}}}catch(e){}" +
        "var q=[];var qi=0;try{var ql=p.getQualityLevels()||[];for(var k=0;k<ql.length;k++)q.push(ql[k].label||('Q'+k));qi=p.getCurrentQuality();}catch(e){}" +
        "return JSON.stringify({st:p.getState(),pos:Math.floor(p.getPosition()||0),dur:Math.floor(p.getDuration()||0),q:q,qi:qi});}catch(e){return '';}})();"

private fun isJwPlayerHost(embedUrl: String): Boolean = "videoplayer.vip" in embedUrl
private fun isDailymotionHost(embedUrl: String): Boolean = "dailymotion" in embedUrl

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

private fun jwPlayer(embedUrl: String, referer: String) = WebPlayer(
    load = { it.loadUrl(embedUrl, mapOf("Referer" to referer)) },
    setupJs = JW_SETUP_JS, stateJs = JW_STATE_JS,
    playKick = "try{jwplayer().play(true);}catch(e){}",
    toggle = "try{var p=jwplayer();p.getState()=='playing'?p.pause(true):p.play(true);}catch(e){}",
    seekAbs = { "try{jwplayer().seek($it);}catch(e){}" },
    setQuality = { "try{jwplayer().setCurrentQuality($it);}catch(e){}" },
    allowHost = { it.endsWith("videoplayer.vip") },
)

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
    "exceedbronzetooth", "protrafficinspector", "255md", "dtscout", "dtscdn", "onaudience", "histats",
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
        "document.querySelectorAll('a[href]').forEach(function(a){if(RE.test(a.getAttribute('href')||'')){box(a).remove();}});" +
        "document.querySelectorAll('iframe[src]').forEach(function(f){if(RE.test(f.getAttribute('src')||'')){box(f).remove();}});" +
        "}catch(e){}}" +
        "clean();setInterval(clean,800);" +
        "try{new MutationObserver(clean).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}})();"

private fun looksLikeStream(u: String): Boolean {
    val low = u.lowercase()
    val path = low.substringBefore('?')
    // Extensions OR path markers (videoplayer.vip serves HLS at /hls/<token> with no .m3u8 suffix).
    return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") ||
        path.endsWith(".mkv") || path.endsWith(".webm") ||
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
        repeat(14) {
            delay(1500)
            if (done.get()) return@LaunchedEffect
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
                    onSniffed(url, h)
                }
            }
        }
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
                                val origin = runCatching { java.net.URI(embedUrl).let { "${it.scheme}://${it.host}" } }.getOrNull()
                                val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(origin ?: embedUrl) }.getOrNull()
                                val h = HashMap(request.requestHeaders).apply {
                                    putIfAbsent("Referer", referer) // request headers already carry the embed Referer
                                    cookie?.let { put("Cookie", it) } // NO Origin — JWPlayer's media fetch doesn't send it
                                }
                                Handler(Looper.getMainLooper()).post { onSniffed(u, h) }
                                // Block the WebView's own fetch so a single-use token stays unused → ExoPlayer gets a fresh hit.
                                return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }
                        override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(READER_JS, null) }
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
    hasNext: Boolean = false,
    onNext: () -> Unit = {},
    /** Non-null for offline playback: a cache-backed factory so the downloaded stream plays with no network. */
    dataSourceFactory: DataSource.Factory? = null,
) {
    val context = LocalContext.current
    // ── Watch telemetry: collect playback heartbeats → server XP engine (cultivation / Jalan Kultivasi).
    // The server validates the deltas (skip/idle/background filtered), gates XP at 70% real watch time,
    // and caps per item — see WatchSessionRepository. Plain mutableListOf (not snapshot state): mutated
    // only from the IO poll loop, never read by composition.
    val sessionId = remember(arg.url) { java.util.UUID.randomUUID().toString() }
    val sessionStartRt = remember(arg.url) { android.os.SystemClock.elapsedRealtime() }
    val heartbeats = remember(arg.url) { mutableListOf<Heartbeat>() }
    var lastInteractionAt by remember(arg.url) { mutableLongStateOf(System.currentTimeMillis()) }
    val watchSourceId = remember(arg.url) { TnData.sourceIdForUrl(arg.url.orEmpty()) }
    val watchEpisodeId = remember(arg.url) { TnData.episodeIdForUrl(arg.url.orEmpty()) }
    fun flushHeartbeats() {
        if (heartbeats.size < 2) return
        TnData.reportWatchSession(sessionId, watchEpisodeId, watchSourceId, heartbeats.toList())
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
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory ?: httpFactory))
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

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING
                if (s == Player.STATE_READY) duration = exo.duration.coerceAtLeast(0L)
                if (s == Player.STATE_ENDED) { ended = true; WatchProgressStore.markFinished(arg.url, exo.duration) }
            }
            override fun onIsPlayingChanged(p: Boolean) { playing = p }
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
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) { onError() }
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
        val item = MediaItem.Builder().setUri(quality.url).apply {
            if (isHls(quality.url)) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
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
    // the next source via onError (the failover). Re-armed on each Resolusi change.
    LaunchedEffect(quality) {
        delay(12_000)
        if (exo.currentPosition <= 0L && !playing) onError()
    }
    LaunchedEffect(controls, playing, menuOpen) { if (controls && playing && !menuOpen) { delay(4000); controls = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exo; useController = false; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
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
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.35f)))
            TopBar(arg.title, arg.episodeLabel, onBack) {
                SourcePill(servers, server, onPickServer, onExternal, onOpenChange = { menuOpen = it })
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
                }
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
                    onValueChange = { f -> if (duration > 0) { val p = (f * duration).toLong(); position = p; exo.seekTo(p); lastInteractionAt = System.currentTimeMillis() } },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)),
                )
                Text(fmtTime(duration), color = Color.White, fontSize = 12.sp)
            }
        }
        // Auto-next countdown overlay (after the episode ends; cancelable).
        if (nextIn > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Episode berikutnya dalam ${nextIn}s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(0.18f))
                                .clickable { cancelNext = true; nextIn = -1 }.padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text("Batal", color = Color.White, fontSize = 13.sp) }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(Color.White)
                                .clickable { onNext() }.padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text("Tonton sekarang ⏭", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                }
            }
        }
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
    LaunchedEffect(barVisible, fullscreen, menuOpen) { if (barVisible && !fullscreen && !menuOpen) { delay(4000); barVisible = false } }
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
                TopBar(arg.title, arg.episodeLabel, onBack) { SourcePill(servers, server, onPickServer, onExternal, onOpenChange = { menuOpen = it }) }
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
    var qualities by remember { mutableStateOf<List<String>>(emptyList()) }
    var qualityIdx by remember { mutableStateOf(0) }
    var started by remember(embedUrl) { mutableStateOf(false) }
    // No more servers to try AND this one never started — show a terminal state instead of an endless
    // "Menyiapkan video…" spinner.
    var dead by remember(embedUrl) { mutableStateOf(false) }
    var reload by remember(embedUrl) { mutableStateOf(0) }

    // Watchdog: JWPlayer/Dailymotion can fail to play (e.g. JW error 232404 — dead/geo-blocked playlist)
    // with no JS error we can observe, so a time budget guards the load. If no frame has rolled, hand off
    // to the next server (same failover as ExoStage/SniffStage); when none remain, stop spinning and let
    // the user open externally / go back instead of hanging forever.
    LaunchedEffect(embedUrl, reload) {
        delay(20_000)
        if (!started && !dead) { if (!onServerFailed()) dead = true }
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
                val json = r?.removeSurrounding("\"")?.replace("\\\"", "\"")?.takeIf { it.startsWith("{") }
                if (json != null) runCatching {
                    val o = org.json.JSONObject(json)
                    playing = o.optString("st") == "playing"
                    o.optLong("dur").let { if (it > 0L) duration = it }
                    position = o.optLong("pos")
                    o.optJSONArray("q")?.let { a -> qualities = (0 until a.length()).map { a.optString(it) } }
                    qualityIdx = o.optInt("qi").coerceAtLeast(0)
                    // Kick auto-play until it actually starts (the host's play() can fire before it's ready).
                    // Reveal only once frames are rolling (pos>0) so the startup clutter stays behind the cover.
                    if (!started) { if (playing && position > 0L) started = true else wv.evaluateJavascript(player.playKick, null) }
                }
            }
        }
    }
    LaunchedEffect(controls, playing) { if (controls && playing) { delay(4000); controls = false } }

    fun js(code: String) { web?.evaluateJavascript(code, null) }
    fun toggle() = js(player.toggle)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(embedUrl, reload) {
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
                        settings.userAgentString = DESKTOP_UA
                        webChromeClient = fullscreenChromeClient(ctx, { fullscreen = true }, { fullscreen = false })
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (isAdHost(request.url.host.orEmpty()))
                                    WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                else null
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
                Modifier.align(Alignment.TopStart).padding(10.dp).size(38.dp).clip(CircleShape)
                    .background(Color.White.copy(0.15f)).clickable { onBack() },
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
                SourcePill(servers, current, onPickServer, onExternal)
                if (qualities.size > 1) {
                    Spacer(Modifier.width(8.dp))
                    Pill("Resolusi", qualities.getOrElse(qualityIdx) { "Auto" }, qualities.indices.toList(),
                        itemLabel = { qualities[it] }, selected = { it == qualityIdx }) { idx ->
                        qualityIdx = idx
                        js(player.setQuality(idx))
                    }
                }
            }
            Box(
                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.White.copy(0.18f)).clickable { toggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(width = 6.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                    Box(Modifier.size(width = 6.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                } else Text("▶", color = Color.White, fontSize = 26.sp)
            }
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtTime(position * 1000), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { f -> if (duration > 0L) { val p = (f * duration).toLong(); position = p; js(player.seekAbs(p)) } },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.3f)),
                )
                Text(fmtTime(duration * 1000), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

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

/** Strip the source's parenthetical hint (anichin appends "[Ganti kalo gak ada suaranya]", "[ADS]", …)
 *  so the compact pill stays short; the full label is still shown in the dropdown. */
private fun shortServerName(name: String): String =
    name.replace(Regex("\\s*\\[[^\\]]*\\]"), "").trim().ifBlank { name }

/** Source-server picker pill + dropdown (with an "open externally" tail). */
@Composable
private fun SourcePill(servers: List<VideoServer>, current: VideoServer, onPick: (VideoServer) -> Unit, onExternal: () -> Unit, onOpenChange: (Boolean) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpenChange(open) } // let the player pause its controls auto-hide while open
    Box {
        PillRow("Source", shortServerName(current.name)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
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
private fun <T> Pill(label: String, value: String, items: List<T>, itemLabel: (T) -> String, selected: (T) -> Boolean, onOpenChange: (Boolean) -> Unit = {}, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpenChange(open) } // let the player pause its controls auto-hide while open
    Box {
        PillRow(label, value) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
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
private fun NoSourceBox(onRetry: () -> Unit, onExternal: () -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Nggak nemu source video buat episode ini", color = Color.White, fontWeight = FontWeight.Bold)
            // Kuramanime's player token is rate-limited on quick re-opens, so a manual retry (after a beat)
            // usually succeeds — make it the primary action.
            Spacer(Modifier.height(12.dp))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Color.White).clickable { onRetry() }.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("Coba lagi", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Text("Buka di player luar", color = Color.White.copy(0.7f), modifier = Modifier.clickable { onExternal() }.padding(8.dp))
            Spacer(Modifier.height(2.dp))
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
        view.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');" +
                "if(v){v.muted=false;var p=v.play&&v.play();if(p&&p.catch){p.catch(function(){v.muted=true;v.play&&v.play();});}}" +
                "var b=document.querySelector('.vjs-big-play-button,.ytp-large-play-button,button[aria-label*=\"lay\" i],.play-button,.play');" +
                "if(b){b.click();}}catch(e){}})();",
            null,
        )
    }
}

/**
 * Order servers fastest-first for auto-pick + failover ("terkencang dulu"): pre-resolved direct per-
 * resolution streams (kuramadrive/otakudesu) start instantly; first-party direct mp4 next; clean
 * players we drive in our own controls (JWPlayer/ok.ru); packed-JS HLS embeds; ad-heavy / WebView-only last.
 */
private fun speedRank(s: VideoServer): Int {
    val host = s.embedUrl.lowercase()
    val name = s.name.lowercase()
    return when {
        s.variants.isNotEmpty() -> 0
        // NontonAnimeID's native servers carry the embed-page URL here; it XOR-decodes to a direct
        // googlevideo/.mp4/.m3u8 stream, so rank it with the other pre-resolved direct streams (not the
        // "else" bucket, where it would lose the default pick to a third-party ok.ru mirror).
        "desustream" in host || "filedon" in host || "googlevideo" in host ||
            "kotakanimeid.link/video-embed" in host || host.substringBefore('?').endsWith(".mp4") -> 1
        isJwPlayerHost(s.embedUrl) || "ok.ru" in host || "okru" in name -> 2
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
