package com.tetonova.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates per-account library sync. When signed in ([AuthManager]), it mirrors the three local
 * stores (Followed / History / resume) to the account and back: it POSTs the full local set, the
 * server merges last-write-wins, and the merged set is folded back in. Triggered on sign-in, on app
 * start (if already signed in), and — debounced — after any local follow/history/progress change.
 *
 * Local-first: no-op when signed out, when Firebase isn't configured, or when the panel is
 * unreachable; the local stores remain the source of truth and never block on the network. Sign-out
 * keeps local data and just stops syncing.
 */
object LibrarySync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var api: LibrarySyncApi? = null

    @Volatile private var debounce: Job? = null
    private const val DEBOUNCE_MS = 2_500L

    /** "Cadangan otomatis" cloud half: only sync when the toggle is on (key written by AppState.autoBackup). */
    private fun autoBackupOn() = SettingsStore.getBool("auto_backup", true)

    /** Wire the panel base URL (from the Application). Kicks an initial sync if already signed in. */
    fun init(panelBaseUrl: String) {
        api = LibrarySyncApi(panelBaseUrl)
        if (AuthManager.signedIn && autoBackupOn()) scope.launch { fullSync() }
    }

    /** Sign-in just succeeded → pull the account's library and push anything local. */
    fun onSignedIn() {
        if (autoBackupOn()) scope.launch { fullSync() }
    }

    /** Sign-out → keep local data, stop syncing (cancel any pending push). */
    fun onSignedOut() {
        debounce?.cancel()
    }

    /** A local store changed → schedule a debounced push (coalesces bursts of edits/heartbeats). */
    fun onLocalChange() {
        if (api == null || !AuthManager.signedIn || !autoBackupOn()) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(DEBOUNCE_MS)
            fullSync()
        }
    }

    private suspend fun fullSync() {
        val client = api ?: return
        val token = AuthManager.idToken() ?: return
        mutex.withLock {
            val payload = LibraryPayload(
                followed = FollowedStore.exportForSync(),
                history = HistoryStore.exportForSync(),
                progress = WatchProgressStore.exportForSync(),
            )
            val merged = client.sync(token, payload) ?: return
            // mergeFromSync applies server rows newer than local; it does NOT re-trigger onLocalChange.
            FollowedStore.mergeFromSync(merged.followed)
            HistoryStore.mergeFromSync(merged.history)
            WatchProgressStore.mergeFromSync(merged.progress)
            HistoryStore.refresh() // recompute progress bars from any merged resume positions
        }
    }
}
