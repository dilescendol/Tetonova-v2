package com.tetonova.app.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
import java.util.Locale

/**
 * Tiny synchronous persistence for user settings (theme, accent, playback/notif toggles…).
 * Backed by SharedPreferences so values survive app restarts. Initialised once on app start.
 */
object SettingsStore {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("tetonova_settings", Context.MODE_PRIVATE)
    }

    fun getBool(key: String, default: Boolean): Boolean = prefs?.getBoolean(key, default) ?: default
    fun setBool(key: String, value: Boolean) { prefs?.edit()?.putBoolean(key, value)?.apply() }
    fun getStr(key: String, default: String): String = prefs?.getString(key, default) ?: default
    fun setStr(key: String, value: String) { prefs?.edit()?.putString(key, value)?.apply() }
    fun getLong(key: String, default: Long): Long = prefs?.getLong(key, default) ?: default
    fun setLong(key: String, value: Long) { prefs?.edit()?.putLong(key, value)?.apply() }
    fun remove(vararg keys: String) {
        prefs?.edit()?.apply { keys.forEach(::remove) }?.apply()
    }

    /**
     * Stable anonymous per-install id, generated once and persisted. Sent as the
     * `X-TN-Install-Id` header with the in-app problem report so an admin can spot
     * repeat reporters without any account/login.
     */
    fun installId(): String {
        val existing = getStr("install_id", "")
        if (existing.isNotBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        setStr("install_id", fresh)
        return fresh
    }

    /** Stable local fallback handle used until the account profile is fetched from the panel. */
    fun profileUsernameFallback(): String {
        val existing = getStr("profile_username_fallback", "")
        if (Regex("^[a-z0-9_]{3,20}$").matches(existing)) return existing
        val suffix = UUID.randomUUID().toString()
            .replace("-", "")
            .take(8)
            .lowercase(Locale.ROOT)
        return "nova$suffix".take(20).also { setStr("profile_username_fallback", it) }
    }
}
