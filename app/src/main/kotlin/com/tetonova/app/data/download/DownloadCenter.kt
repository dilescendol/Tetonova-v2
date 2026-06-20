@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tetonova.app.data.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.tetonova.app.data.SettingsStore
import com.tetonova.core.scraper.ExtractResult
import com.tetonova.core.scraper.LiveSource
import com.tetonova.core.scraper.StreamExtractor
import com.tetonova.core.scraper.StreamVariant
import com.tetonova.core.scraper.VideoServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What an episode's download is doing — drives the EpCard icon + the Downloads tab rows. */
enum class DlUiState { NONE, RESOLVING, QUEUED, DOWNLOADING, COMPLETED, FAILED }

/** Display metadata captured at download time (the source URL is the stable id). */
data class DlMeta(
    val episodeUrl: String,
    val title: String,
    val ep: String,
    val poster: String?,
    val badge: String,
)

/** One row in the Downloads tab — a [Download] joined with its [DlMeta]. */
data class DlItem(
    val id: String,
    val title: String,
    val ep: String,
    val poster: String?,
    val badge: String,
    val streamUrl: String,
    val state: Int,
    val percent: Int,
    val bytes: Long,
    val updatedAt: Long,
)

/**
 * Scrape-once → offline cache. The whole offline-download feature lives here:
 *  - owns the Media3 [DownloadManager] + its [SimpleCache] (app-private, no storage permission) and
 *    a [ResolvingDataSource] that re-attaches each download's per-host HTTP headers (the same
 *    Referer/Origin/Cookie the live player needs);
 *  - [startDownload] runs the SAME resolve pipeline the player uses ([LiveSource.servers] →
 *    [StreamExtractor.extract]) so the stream URL/token is fresh, then enqueues it;
 *  - exposes a live [items] list (snapshot-state-backed) the Downloads tab + episode cards observe,
 *    and [offlineVariant]/[cacheFactory] so a downloaded episode plays back from cache with no network.
 *
 * Media3's offline stack handles both progressive mp4 (ranged download) and HLS m3u8 (segments) — we
 * never write a muxer. Stream metadata is stashed in [DownloadRequest.getData] so no extra DB is needed.
 */
object DownloadCenter {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private lateinit var appContext: Context
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Per-host HTTP headers for the resolving data source (an HLS manifest + its segments share a host). */
    private val headersByHost = ConcurrentHashMap<String, Map<String, String>>()

    /** Live download rows (newest-first), rebuilt from the Media3 index + active downloads. */
    val items: SnapshotStateList<DlItem> = mutableStateListOf()

    /** Episode URLs whose stream is being resolved before enqueue (the EpCard spinner state). */
    val resolving: SnapshotStateList<String> = mutableStateListOf()

    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureManager(appContext)
        startTicker()
    }

    @Synchronized
    fun ensureManager(context: Context): DownloadManager {
        manager?.let { return it }
        val ctx = context.applicationContext
        if (!::appContext.isInitialized) appContext = ctx
        val db = StandaloneDatabaseProvider(ctx)
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tn-downloads")
        val c = SimpleCache(dir, NoOpCacheEvictor(), db)
        cache = c
        val m = DownloadManager(ctx, db, c, upstreamFactory(), Executors.newFixedThreadPool(4))
        m.maxParallelDownloads = 2
        m.requirements = currentRequirements()
        m.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = sync()
            override fun onDownloadChanged(dm: DownloadManager, download: Download, ex: Exception?) = sync()
            override fun onDownloadRemoved(dm: DownloadManager, download: Download) = sync()
        })
        manager = m
        rebuildHeaderMap(m)
        sync()
        return m
    }

    // --- HTTP plumbing -------------------------------------------------------------------------

    /** A data source that re-attaches a download's per-host headers (segments included), UA always set. */
    private fun upstreamFactory(): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true)
        return ResolvingDataSource.Factory(http) { spec ->
            val h = headersByHost[hostOf(spec.uri.toString())]
            if (h.isNullOrEmpty()) spec
            else spec.withRequestHeaders(h.filterKeys { !it.equals("User-Agent", true) })
        }
    }

    /** Read-only cache-backed factory for OFFLINE playback (won't hit the network for a complete file). */
    fun cacheFactory(): DataSource.Factory {
        val c = cache ?: ensureManager(appContext).let { cache!! }
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstreamFactory())
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    // --- Public trigger ------------------------------------------------------------------------

    /** One-tap download: resolve a fresh stream (server auto-pick + cap) then enqueue. Idempotent. */
    fun startDownload(meta: DlMeta) {
        val url = meta.episodeUrl
        if (url.isBlank() || resolving.contains(url)) return
        if (items.any { it.id == url && (it.state == Download.STATE_COMPLETED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED) }) return
        resolving.add(url)
        scope.launch {
            val ok = runCatching { resolveAndEnqueue(meta) }.getOrDefault(false)
            postToMain {
                resolving.remove(url)
                if (!ok) toast("Gagal menyiapkan unduhan. Coba lagi, atau pilih episode lain.")
            }
        }
    }

    fun remove(id: String) =
        DownloadService.sendRemoveDownload(appContext, TnDownloadService::class.java, id, false)

    fun removeAll() =
        DownloadService.sendRemoveAllDownloads(appContext, TnDownloadService::class.java, false)

    /** Re-resolve + re-enqueue (used by "Coba lagi" on a failed download — the token may have died). */
    fun retry(item: DlItem) = startDownload(DlMeta(item.id, item.title, item.ep, item.poster, item.badge))

    fun applyWifiOnly(wifiOnly: Boolean) {
        SettingsStore.setBool("download_wifi_only", wifiOnly)
        manager?.requirements = currentRequirements()
    }

    fun storageBytes(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    // --- Offline-playback / UI queries (read snapshot state — observable in Compose) ------------

    fun isDownloaded(url: String?): Boolean =
        url != null && items.any { it.id == url && it.state == Download.STATE_COMPLETED }

    fun offlineVariant(url: String?): StreamVariant? {
        val it = url?.let { u -> items.firstOrNull { it.id == u && it.state == Download.STATE_COMPLETED } } ?: return null
        return StreamVariant("Tersimpan", it.streamUrl)
    }

    fun headersFor(url: String?): Map<String, String> {
        val it = url?.let { u -> items.firstOrNull { it.id == u } } ?: return emptyMap()
        return headersByHost[hostOf(it.streamUrl)] ?: emptyMap()
    }

    fun uiState(url: String?): DlUiState {
        if (url == null) return DlUiState.NONE
        if (resolving.contains(url)) return DlUiState.RESOLVING
        val it = items.firstOrNull { it.id == url } ?: return DlUiState.NONE
        return when (it.state) {
            Download.STATE_COMPLETED -> DlUiState.COMPLETED
            Download.STATE_FAILED -> DlUiState.FAILED
            Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> DlUiState.DOWNLOADING
            Download.STATE_QUEUED, Download.STATE_STOPPED -> DlUiState.QUEUED
            else -> DlUiState.NONE
        }
    }

    fun percentOf(url: String?): Int = url?.let { u -> items.firstOrNull { it.id == u } }?.percent ?: 0

    // --- Internals -----------------------------------------------------------------------------

    private suspend fun resolveAndEnqueue(meta: DlMeta): Boolean {
        val referer = meta.episodeUrl
        val servers = runCatching { LiveSource.servers(meta.episodeUrl) }.getOrDefault(emptyList())
            .filter { StreamExtractor.isPlayable(it.embedUrl) }
            .filterNot { isWebOnlyHost(it.embedUrl) }
            .sortedWith(compareBy({ downloadRank(it) }, { it.name }))
        val cap = qualityCapHeight(SettingsStore.getStr("download_quality", "auto"))
        Log.i(TAG, "resolve ${meta.episodeUrl}: candidates=${servers.map { it.name }} cap=$cap")
        for (s in servers) {
            val res = runCatching { StreamExtractor.extract(s, referer) }.getOrDefault(ExtractResult(emptyList()))
            val v = pickForCap(res.variants, cap)
            Log.i(TAG, "  try '${s.name}' → ${res.variants.size} variants ${res.variants.map { it.label }}${if (v == null) " (skip)" else ""}")
            if (v == null) continue
            Log.i(TAG, "  PICK '${s.name}' host=${hostOf(v.url)} hls=${isHlsUrl(v.url)} label='${v.label}'")
            enqueue(meta, v.url, res.headers)
            return true
        }
        Log.w(TAG, "no downloadable server for ${meta.episodeUrl}")
        return false
    }

    private const val TAG = "TnDownload"

    private fun enqueue(meta: DlMeta, streamUrl: String, headers: Map<String, String>) {
        val host = hostOf(streamUrl)
        headersByHost[host] = headers
        val req = DownloadRequest.Builder(meta.episodeUrl, Uri.parse(streamUrl))
            .setData(encodeData(meta, host, headers))
            .apply { if (isHlsUrl(streamUrl)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        DownloadService.sendAddDownload(appContext, TnDownloadService::class.java, req, false)
    }

    /** Rebuild the live [items] list from the Media3 index, overlaying live progress of active downloads. */
    private fun sync() {
        val m = manager ?: return
        val rows = mutableListOf<DlItem>()
        val current = m.currentDownloads.associateBy { it.request.id }
        runCatching {
            m.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = current[cursor.download.request.id] ?: cursor.download
                    rows.add(toItem(d))
                }
            }
        }
        postToMain {
            items.clear()
            items.addAll(rows.sortedByDescending { it.updatedAt })
        }
    }

    private fun toItem(d: Download): DlItem {
        val meta = decodeMeta(d.request.data)
        val pct = d.percentDownloaded.let { if (it.isNaN() || it < 0f) 0 else it.toInt() }
        return DlItem(
            id = d.request.id,
            title = meta.title, ep = meta.ep, poster = meta.poster, badge = meta.badge,
            streamUrl = d.request.uri.toString(),
            state = d.state, percent = pct, bytes = d.bytesDownloaded, updatedAt = d.updateTimeMs,
        )
    }

    private fun rebuildHeaderMap(m: DownloadManager) {
        runCatching {
            m.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    val host = hostOf(d.request.uri.toString())
                    decodeHeaders(d.request.data)?.let { headersByHost[host] = it }
                }
            }
        }
    }

    private fun startTicker() {
        scope.launch {
            while (true) {
                val m = manager
                if (m != null && m.currentDownloads.isNotEmpty()) { sync(); delay(1000) } else delay(3000)
            }
        }
    }

    private fun currentRequirements(): Requirements =
        Requirements(if (SettingsStore.getBool("download_wifi_only", false)) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    /** Hosts the player can only show in a WebView (JWPlayer/Dailymotion) — not downloadable via ExoPlayer's HTTP stack. */
    private fun isWebOnlyHost(embed: String): Boolean {
        val h = embed.lowercase()
        return "videoplayer.vip" in h || "dailymotion" in h
    }

    /** Prefer pre-resolved direct streams, then direct files, then HLS embeds — best download targets first. */
    private fun downloadRank(s: VideoServer): Int {
        val host = s.embedUrl.lowercase()
        return when {
            s.variants.isNotEmpty() -> 0
            "desustream" in host || "filedon" in host || "googlevideo" in host ||
                "kotakanimeid.link/video-embed" in host || "pixeldrain" in host ||
                host.substringBefore('?').endsWith(".mp4") -> 1
            "ok.ru" in host || "okru" in s.name.lowercase() -> 2
            "filemoon" in host || "filelions" in host || "vidhide" in host || "lulustream" in host || "rumble" in host -> 3
            else -> 4
        }
    }

    private fun encodeData(meta: DlMeta, host: String, headers: Map<String, String>): ByteArray {
        val o = JSONObject()
            .put("title", meta.title).put("ep", meta.ep)
            .put("poster", meta.poster ?: JSONObject.NULL).put("badge", meta.badge)
            .put("episodeUrl", meta.episodeUrl).put("host", host)
        val h = JSONObject()
        headers.forEach { (k, v) -> h.put(k, v) }
        o.put("headers", h)
        return o.toString().toByteArray()
    }

    private fun decodeMeta(data: ByteArray): DlMeta = runCatching {
        val o = JSONObject(String(data))
        DlMeta(
            episodeUrl = o.optString("episodeUrl"),
            title = o.optString("title").ifBlank { "Unduhan" },
            ep = o.optString("ep"),
            poster = o.optString("poster").takeIf { it.isNotBlank() && it != "null" },
            badge = o.optString("badge").ifBlank { "HD" },
        )
    }.getOrDefault(DlMeta("", "Unduhan", "", null, "HD"))

    private fun decodeHeaders(data: ByteArray): Map<String, String>? = runCatching {
        val h = JSONObject(String(data)).optJSONObject("headers") ?: return null
        buildMap { h.keys().forEach { k -> put(k, h.optString(k)) } }
    }.getOrNull()

    private fun postToMain(block: () -> Unit) { mainHandler.post(block) }
    private fun toast(msg: String) = postToMain { runCatching { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() } }
}
