package com.tetonova.app

import android.app.Application
import com.google.firebase.messaging.FirebaseMessaging
import com.tetonova.app.data.AppPresenceManager
import com.tetonova.app.data.AuthManager
import com.tetonova.app.data.AutoBackup
import com.tetonova.app.data.CoverResolver
import com.tetonova.app.data.FcmRegistration
import com.tetonova.app.data.FcmTokenHolder
import com.tetonova.app.data.FollowCheckWorker
import com.tetonova.app.data.LibrarySync
import com.tetonova.app.data.NotificationHelper
import com.tetonova.app.data.OmdbResolver
import com.tetonova.app.data.SettingsStore
import com.tetonova.app.data.SubscriptionExpiryReminder
import com.tetonova.app.data.TnData
import com.tetonova.app.data.download.DownloadCenter
import com.tetonova.core.designsystem.CoverProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App entry. Local-first: keeps working even when the control panel / upstream sources are
 * unreachable. Loads the bundled repo-tn registry on startup (cheap, index only); catalog
 * content is parsed lazily on first use.
 */
class TetoNovaApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Chromium is intentionally initialized lazily by player/challenge WebViews. Starting it
        // here causes multi-second frame stalls on Home even when no web player is being used.
        SettingsStore.init(this)
        TnData.init(this)
        // Offline downloads: build the Media3 DownloadManager + cache and load any persisted downloads.
        DownloadCenter.init(this)
        // Account sign-in (Firebase) + per-account library sync. Both degrade gracefully when Firebase
        // isn't configured (no google-services.json) or the panel is unreachable — local-first.
        AuthManager.init(this)
        AppPresenceManager.init(Secrets.controlPanelUrl)
        TnData.warmPanel(Secrets.controlPanelUrl)
        LibrarySync.init(Secrets.controlPanelUrl)
        // "Cadangan otomatis" local half: write a .tnova snapshot to the chosen folder if ≥24h due.
        AutoBackup.maybeRun(this)
        // Let the design system resolve real poster covers by title (Jikan/MAL).
        CoverProvider.resolve = { CoverResolver.resolve(it) }
        // OMDb (IMDB) lookups for Movie/Drama detail.
        OmdbResolver.apiKey = Secrets.omdbKey
        // Create notification channels for Android O+
        NotificationHelper.createChannels(this)
        registerFcmToken()
        // Schedule periodic follow episode check
        FollowCheckWorker.schedule(this)
        // Paid Premium expiry: background H-5..H check, deduplicated to one notification per day.
        SubscriptionExpiryReminder.schedule(this)
        appScope.launch { SubscriptionExpiryReminder.checkNow(this@TetoNovaApplication) }
    }

    private fun registerFcmToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (token.isNullOrBlank()) return@addOnSuccessListener
                FcmTokenHolder.token = token
                appScope.launch { FcmRegistration.registerToken(token) }
            }
        } catch (_: Throwable) {
            // Firebase can be absent on local/dev builds.
        }
    }
}
