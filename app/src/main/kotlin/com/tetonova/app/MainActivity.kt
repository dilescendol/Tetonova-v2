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
        if (intent?.getBooleanExtra("open_subscription", false) == true) subscriptionRequest.intValue++
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            TetoNovaRoot(
                windowSizeClass = windowSizeClass,
                // Smoke-test hook only — never honour an externally-supplied URL in release, or any
                // app could launch playback of arbitrary web content in the JS-enabled player WebView.
                debugPlayerUrl = if (BuildConfig.DEBUG) intent?.getStringExtra("player_url") else null,
                deepLink = deepLink.value,
                openSubscriptionRequest = subscriptionRequest.intValue,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = intent.toDetailUri()
        if (intent.getBooleanExtra("open_subscription", false)) subscriptionRequest.intValue++
    }

    private fun Intent?.toDetailUri(): Uri? =
        this?.data ?: this?.getStringExtra("open_detail")?.let { detailDeepLinkUri(url = it) }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }
}
