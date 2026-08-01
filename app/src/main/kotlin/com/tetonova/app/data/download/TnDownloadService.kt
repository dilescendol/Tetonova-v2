@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tetonova.app.data.download

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.tetonova.app.R

/**
 * Foreground service that runs the Media3 [DownloadManager] from [DownloadCenter]. Declared in the
 * manifest with `foregroundServiceType="dataSync"` + a RESTART intent-filter so in-flight downloads
 * survive the app being backgrounded / the device rebooting. The progress notification is built by
 * hand (no extra deps); the channel is created for us by [DownloadService] from [R.string.tn_download_channel].
 */
class TnDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.tn_download_channel,
    0,
) {
    override fun getDownloadManager(): DownloadManager = DownloadCenter.ensureManager(applicationContext)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val active = downloads.count { it.state == Download.STATE_DOWNLOADING }
        val pcts = downloads.mapNotNull { d -> d.percentDownloaded.takeIf { it >= 0f && !it.isNaN() } }
        val pct = if (pcts.isEmpty()) -1 else pcts.average().toInt()
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active > 0) "Mengunduh $active episode" else "Unduhan TetoNova")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (pct in 0..100) b.setContentText("$pct%").setProgress(100, pct, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "tn_downloads"
    }
}
