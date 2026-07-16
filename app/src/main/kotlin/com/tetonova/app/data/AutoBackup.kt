package com.tetonova.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Cadangan otomatis" — the local half. When the toggle is on and the user has picked a destination
 * folder, this writes a `.tnova` snapshot (via [BackupCodec]) into that folder at most once per
 * [INTERVAL_MS]. Best-effort + local-first: any failure (folder permission revoked, no folder set) is
 * a silent no-op — never blocks startup. Runs on app start, mirroring how the cloud half ([LibrarySync])
 * syncs on app start; no WorkManager / background job.
 *
 * The cloud half is driven directly by the toggle inside [LibrarySync] (gated on `auto_backup`).
 */
object AutoBackup {
    private const val FOLDER_KEY = "backup_folder_uri"
    private const val LAST_KEY = "last_local_backup"
    private const val ENABLED_KEY = "auto_backup"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    private val nameFmt = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

    /** The chosen backup folder's tree URI, or null if none picked yet. */
    fun folderUri(): Uri? = SettingsStore.getStr(FOLDER_KEY, "").ifBlank { null }?.let(Uri::parse)

    /** Display name of the chosen folder for the Settings row, or null. */
    fun folderName(context: Context): String? =
        folderUri()?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }

    /** Persist the user's folder pick and take a long-lived permission on it. */
    fun setFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        SettingsStore.setStr(FOLDER_KEY, uri.toString())
    }

    /** Write a `.tnova` snapshot now if due (toggle on, folder set, ≥24h since the last one). */
    fun maybeRun(context: Context) {
        if (!SettingsStore.getBool(ENABLED_KEY, true)) return
        val folder = folderUri() ?: return
        val now = System.currentTimeMillis()
        if (now - SettingsStore.getLong(LAST_KEY, 0) < INTERVAL_MS) return
        runCatching {
            val dir = DocumentFile.fromTreeUri(context, folder) ?: return
            if (!dir.canWrite()) return
            val name = "tetonova-backup-${nameFmt.format(Date(now))}.tnova"
            val doc = dir.createFile("application/json", name) ?: return
            context.contentResolver.openOutputStream(doc.uri)?.use { it.write(BackupCodec.export().toByteArray()) }
            SettingsStore.setLong(LAST_KEY, now)
        }
    }
}
