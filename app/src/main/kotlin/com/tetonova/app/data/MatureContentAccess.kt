package com.tetonova.app.data

import com.tetonova.app.BuildConfig
import com.tetonova.app.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MatureContentAccess {
    const val POLICY_VERSION = "2026-07-28-v1"

    private const val ENABLED_KEY = "mature"
    private const val UID_KEY = "mature_consent_uid"
    private const val POLICY_KEY = "mature_consent_policy"
    private const val ACCEPTED_AT_KEY = "mature_consent_accepted_at"

    private val client = TnHttp.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun isEnabled(uid: String? = AuthManager.user?.uid): Boolean {
        if (uid.isNullOrBlank()) return false
        return SettingsStore.getBool(ENABLED_KEY, false) &&
            SettingsStore.getStr(UID_KEY, "") == uid &&
            SettingsStore.getStr(POLICY_KEY, "") == POLICY_VERSION
    }

    fun onAuthChanged(uid: String?) {
        val storedUid = SettingsStore.getStr(UID_KEY, "")
        val storedPolicy = SettingsStore.getStr(POLICY_KEY, "")
        if (uid.isNullOrBlank() || storedUid != uid || storedPolicy != POLICY_VERSION) {
            clearLocalConsent()
        }
    }

    suspend fun accept(): Result<Unit> {
        val uid = AuthManager.user?.uid
            ?: return Result.failure(IllegalStateException("Masuk dengan Google terlebih dahulu"))
        val token = AuthManager.idToken(forceRefresh = true)
            ?: return Result.failure(IllegalStateException("Sesi akun tidak tersedia"))
        val result = record(token, action = "accepted", ageConfirmed = true)
        if (result.isSuccess && AuthManager.user?.uid == uid) {
            SettingsStore.setStr(UID_KEY, uid)
            SettingsStore.setStr(POLICY_KEY, POLICY_VERSION)
            SettingsStore.setLong(ACCEPTED_AT_KEY, System.currentTimeMillis())
            SettingsStore.setBool(ENABLED_KEY, true)
        }
        return result
    }

    suspend fun revoke(): Result<Unit> {
        clearLocalConsent()
        val token = AuthManager.idToken() ?: return Result.success(Unit)
        return record(token, action = "revoked", ageConfirmed = false)
    }

    private fun clearLocalConsent() {
        SettingsStore.setBool(ENABLED_KEY, false)
        SettingsStore.remove(UID_KEY, POLICY_KEY, ACCEPTED_AT_KEY)
    }

    private suspend fun record(token: String, action: String, ageConfirmed: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val base = Secrets.controlPanelUrl.trim().trimEnd('/')
            if (base.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Panel belum dikonfigurasi"))
            }
            runCatching {
                val payload = JSONObject()
                    .put("action", action)
                    .put("policyVersion", POLICY_VERSION)
                    .put("ageConfirmed", ageConfirmed)
                    .put("installId", SettingsStore.installId())
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .toString()
                val request = Request.Builder()
                    .url("$base/api/v1/me/mature-consent")
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                }
            }
        }
}
