package com.tetonova.app.data

import com.tetonova.app.ui.AppState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Encode/decode the `.tnova` backup file — the single source of truth for what "Ekspor data" writes
 * and "Impor dari file" reads. A full snapshot: app settings + the three local library stores
 * (Followed / History / resume), reusing each store's [exportForSync]/[mergeFromSync] (last-write-wins
 * by `updatedAt`), so import merges cleanly with whatever is already on the device and — when signed in
 * — propagates to the account via [LibrarySync].
 */
object BackupCodec {

    @Serializable
    data class BackupSettings(
        val theme: String = "system",
        val accent: String = "rose",
        val quality: String = "auto",
        val dataSaver: Boolean = false,
        val autoNext: Boolean = true,
        val skipOpening: Boolean = false,
        val mature: Boolean = false,
        val notifFollow: Boolean = true,
        val notifForum: Boolean = true,
        val pushLocal: Boolean = false,
        val downloadQuality: String = "auto",
        val downloadWifiOnly: Boolean = false,
        val lite: Boolean = false,
    )

    @Serializable
    data class BackupFile(
        val app: String = "TetoNova",
        val backupVersion: Int = 1,
        val exportedAt: Long = 0,
        val settings: BackupSettings = BackupSettings(),
        val library: LibraryPayload = LibraryPayload(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * Serialize the current settings + library to the `.tnova` JSON text. Reads settings straight from
     * [SettingsStore] (not an [AppState]) so it can run without the UI — e.g. the periodic local backup
     * from [AutoBackup]. The keys mirror `AppState`'s `persistedBool`/`persistedString` defaults.
     */
    fun export(): String {
        val s = SettingsStore
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            settings = BackupSettings(
                theme = s.getStr("theme_mode", "system"),
                accent = s.getStr("accent_id", "rose"),
                quality = s.getStr("quality", "auto"),
                dataSaver = s.getBool("data_saver", false),
                autoNext = s.getBool("auto_next", true),
                skipOpening = s.getBool("skip_op", false),
                mature = s.getBool("mature", false),
                notifFollow = s.getBool("notif_follow", true),
                notifForum = s.getBool("notif_forum", true),
                pushLocal = s.getBool("push_local", false),
                downloadQuality = s.getStr("download_quality", "auto"),
                downloadWifiOnly = s.getBool("download_wifi_only", false),
                lite = s.getBool("lite_mode", false),
            ),
            library = LibraryPayload(
                followed = FollowedStore.exportForSync(),
                history = HistoryStore.exportForSync(),
                progress = WatchProgressStore.exportForSync(),
            ),
        )
        return json.encodeToString(BackupFile.serializer(), file)
    }

    /**
     * Parse a `.tnova` file, apply its settings to [state] (the [AppState] setters persist to
     * [SettingsStore]) and merge its library into the local stores. Returns the number of library
     * entries written, or a failure for malformed/foreign files (UI shows a Toast either way).
     */
    fun import(text: String, state: AppState): Result<Int> = runCatching {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        with(file.settings) {
            state.themeMode = theme.takeIf { it in setOf("system", "light", "dark") } ?: "system"
            state.accentId = accent
            state.quality = quality
            state.dataSaver = dataSaver
            state.autoNext = autoNext
            state.skipOp = skipOpening
            state.mature = mature
            state.notifFollow = notifFollow
            state.notifForum = notifForum
            state.pushLocal = pushLocal
            state.downloadQuality = downloadQuality
            state.downloadWifiOnly = downloadWifiOnly
            state.lite = lite
        }
        FollowedStore.mergeFromSync(file.library.followed)
        HistoryStore.mergeFromSync(file.library.history)
        WatchProgressStore.mergeFromSync(file.library.progress)
        HistoryStore.refresh() // recompute progress bars from any merged resume positions
        LibrarySync.onLocalChange() // push merged result to the account if signed in (no-op otherwise)
        file.library.followed.size + file.library.history.size + file.library.progress.size
    }
}
