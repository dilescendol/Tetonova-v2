package com.tetonova.app

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Coarse form-factor bucket used to drive orientation policy (see MainActivity + PlayerScreen).
 * Phone = portrait-first, Tablet = follows rotation, TV = landscape-only.
 */
enum class DeviceTier { PHONE, TABLET, TV }

fun deviceTier(context: Context): DeviceTier {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTv = ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    if (isTv) return DeviceTier.TV
    // sw600dp is the standard phone/tablet cutoff.
    return if (context.resources.configuration.smallestScreenWidthDp >= 600) DeviceTier.TABLET
    else DeviceTier.PHONE
}
