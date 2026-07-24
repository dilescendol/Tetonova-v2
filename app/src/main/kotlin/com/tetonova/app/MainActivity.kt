package com.tetonova.app

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.tetonova.app.ui.TetoNovaRoot
import com.tetonova.app.ui.detailDeepLinkUri

class MainActivity : ComponentActivity() {
    private val deepLink = mutableStateOf<Uri?>(null)
    private val subscriptionRequest = mutableIntStateOf(0)
    private val showPaidPlans = mutableStateOf(false)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // App-shell orientation policy. The player overrides this per-video and restores it on exit.
        //   Phone  -> portrait only (landscape home/browse looks broken).
        //   Tablet -> follow rotation (both portrait & landscape).
        //   TV     -> landscape.
        requestedOrientation = when (deviceTier(this)) {
            DeviceTier.PHONE -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            DeviceTier.TABLET -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            DeviceTier.TV -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        deepLink.value = intent.toDetailUri()
        handleSubscriptionIntent(intent)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            TetoNovaRoot(
                windowSizeClass = windowSizeClass,
                // Smoke-test hook only — never honour an externally-supplied URL in release, or any
                // app could launch playback of arbitrary web content in the JS-enabled player WebView.
                debugPlayerUrl = if (BuildConfig.DEBUG) intent?.getStringExtra("player_url") else null,
                deepLink = deepLink.value,
                openSubscriptionRequest = subscriptionRequest.intValue,
                openPaidPlans = showPaidPlans.value,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.toDetailUri()
        handleSubscriptionIntent(intent)
    }

    private fun Intent?.toDetailUri(): Uri? {
        val uri = this?.data
        return if (uri?.scheme == APP_LINK_SCHEME && uri.host == DETAIL_LINK_HOST) {
            uri
        } else {
            this?.getStringExtra("open_detail")?.let { detailDeepLinkUri(url = it) }
        }
    }

    private fun handleSubscriptionIntent(intent: Intent?) {
        val target = intent.subscriptionTarget() ?: return
        showPaidPlans.value = target == SubscriptionTarget.PREMIUM
        subscriptionRequest.intValue++
    }

    private fun Intent?.subscriptionTarget(): SubscriptionTarget? {
        val uri = this?.data
        if (this?.getBooleanExtra("open_subscription", false) == true) return SubscriptionTarget.PREMIUM
        if (uri?.scheme != APP_LINK_SCHEME) return null
        return when (uri.host) {
            TRIAL_LINK_HOST -> SubscriptionTarget.TRIAL
            PREMIUM_LINK_HOST -> SubscriptionTarget.PREMIUM
            else -> null
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private companion object {
        const val APP_LINK_SCHEME = "tetonova"
        const val DETAIL_LINK_HOST = "detail"
        const val TRIAL_LINK_HOST = "trial"
        const val PREMIUM_LINK_HOST = "premium"
    }

    private enum class SubscriptionTarget { TRIAL, PREMIUM }
}
