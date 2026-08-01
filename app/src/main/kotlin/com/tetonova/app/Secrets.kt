package com.tetonova.app

import android.util.Base64

/**
 * Runtime decode of the build-time XOR+base64 obfuscated secrets (see the
 * `obfuscateSecret` helper in app/build.gradle.kts). Keeps the control-panel domain
 * and API keys out of the release APK as plaintext string constants so a decompile
 * (`strings`/jadx) can't lift them directly.
 *
 * This is obfuscation, not real crypto — the key ships in the APK too — but it forces
 * anyone extracting the domain/keys to actually reverse the decode instead of grepping.
 * Keep [XOR_KEY] byte-for-byte in sync with `secretXorKey` in build.gradle.kts.
 *
 * Always read secrets through this object, never `BuildConfig.*_ENC` directly.
 */
object Secrets {
    private val XOR_KEY = "Tn0v4_biz_2026".toByteArray(Charsets.UTF_8)

    /** Control-panel base URL for the /api/v1 feeds (sources + Home sections). */
    val controlPanelUrl: String by lazy { reveal(BuildConfig.TETONOVA_CONTROL_PANEL_URL_ENC) }

    /** OMDb (IMDB) API key for Movie/Drama detail lookups. */
    val omdbKey: String by lazy { reveal(BuildConfig.TETONOVA_OMDB_KEY_ENC) }

    private fun reveal(enc: String): String {
        val bytes = Base64.decode(enc, Base64.DEFAULT)
        val out = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}
