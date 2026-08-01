package com.tetonova.app.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Account presence tied to the whole Android process, not playback or a background worker.
 *
 * While foregrounded, an immediate heartbeat is followed by one every 20 seconds. Entering the
 * background queues a best-effort offline update. The server also expires heartbeats after 60
 * seconds, covering force-stop, crashes, lost connectivity, and process death.
 */
object AppPresenceManager : DefaultLifecycleObserver {
    private const val HEARTBEAT_INTERVAL_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkMutex = Mutex()
    private var api: UserApi? = null
    private var heartbeatJob: Job? = null
    private var cachedBearer: String? = null

    @Volatile private var foreground = false
    @Volatile private var signingOut = false

    fun init(panelBaseUrl: String) {
        if (api != null || panelBaseUrl.isBlank()) return
        api = UserApi(panelBaseUrl)
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(this)
        if (foreground) startHeartbeatLoop()
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        if (AuthManager.signedIn) {
            signingOut = false
            startHeartbeatLoop()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        requestSync()
    }

    /** Make a foreground sign-in visible without waiting for the next interval. */
    fun onAuthenticationChanged(signedIn: Boolean) {
        signingOut = !signedIn
        if (foreground && signedIn) {
            startHeartbeatLoop()
        } else {
            heartbeatJob?.cancel()
            heartbeatJob = null
            requestSync()
        }
    }

    /** Queue offline while the last valid Firebase bearer is still available. */
    fun onSigningOut() {
        signingOut = true
        heartbeatJob?.cancel()
        heartbeatJob = null
        requestSync()
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && foreground) {
                syncDesiredState()
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun requestSync() {
        if (api == null) return
        scope.launch { syncDesiredState() }
    }

    /** Re-check state inside the lock so a queued offline cannot overwrite a newer foreground state. */
    private suspend fun syncDesiredState() = networkMutex.withLock {
        val client = api ?: return@withLock
        val shouldBeOnline = foreground && !signingOut && AuthManager.signedIn
        val bearer = if (shouldBeOnline) {
            AuthManager.idToken()?.also { cachedBearer = it }
        } else {
            cachedBearer ?: AuthManager.idToken()?.also { cachedBearer = it }
        } ?: return@withLock

        val sent = client.updatePresence(SettingsStore.installId(), bearer, shouldBeOnline)
        if (sent && !shouldBeOnline && !AuthManager.signedIn) cachedBearer = null
    }
}
