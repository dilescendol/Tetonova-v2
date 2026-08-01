package com.tetonova.app.data

/** Fase trial Premium yang dilihat UI. */
enum class TrialPhase { AVAILABLE, ACTIVE, EXPIRED }

/**
 * Satu-satunya sumber kebenaran soal trial Premium sekali pakai.
 *
 * Semua baca/tulis trial lewat sini — UI tidak menyentuh [SettingsStore] langsung — supaya nanti
 * tinggal mengganti isi method ini dengan jalur server (POST /api/v1/me/trial/claim, GET
 * /api/v1/me/subscription) tanpa mengubah UI sama sekali.
 *
 * Fase hybrid (on-device): non-repeatable bersifat "lunak" — clear data / reinstall akan mereset
 * karena state hanya di SharedPreferences. Versi server membuatnya "keras" lewat
 * `app_users.trial_used_at` (per Firebase UID).
 */
object TrialStore {
    // Satu knob durasi — gampang diubah / nanti dipindah ke remote config (panel: trial.duration_seconds).
    const val DEFAULT_DURATION_MS = 60L * 60L * 1000L

    private const val KEY_USED_AT = "trial_used_at"
    private const val KEY_EXPIRES_AT = "trial_expires_at"

    fun isUsed(): Boolean = SettingsStore.getLong(KEY_USED_AT, 0L) > 0L
    fun expiresAt(): Long = SettingsStore.getLong(KEY_EXPIRES_AT, 0L)
    fun isActive(now: Long = System.currentTimeMillis()): Boolean = expiresAt() > now
    fun remainingMs(now: Long = System.currentTimeMillis()): Long = (expiresAt() - now).coerceAtLeast(0L)

    /** true kalau berhasil klaim; false kalau sudah pernah dipakai (non-repeatable). */
    fun claim(durationMs: Long = DEFAULT_DURATION_MS, now: Long = System.currentTimeMillis()): Boolean {
        if (isUsed()) return false
        if (durationMs <= 0L) return false
        SettingsStore.setLong(KEY_USED_AT, now)
        SettingsStore.setLong(KEY_EXPIRES_AT, now + durationMs)
        return true
    }

    fun phase(now: Long = System.currentTimeMillis()): TrialPhase = when {
        isActive(now) -> TrialPhase.ACTIVE
        isUsed()      -> TrialPhase.EXPIRED   // sudah dipakai & habis → tidak boleh ulang
        else          -> TrialPhase.AVAILABLE
    }
}
