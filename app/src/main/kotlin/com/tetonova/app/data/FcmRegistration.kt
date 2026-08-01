package com.tetonova.app.data

import com.tetonova.app.BuildConfig
import com.tetonova.app.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Handles FCM token registration with the panel server.
 */
object FcmRegistration {

    private val client = TnHttp.client
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Register FCM token with panel server for push notifications.
     *
     * No-op when "Push lokal saja" (push_local) is on — the default — because that mode
     * deliberately keeps notifications on the local WorkManager poller with no external
     * push stack. Registration only happens once the user opts into external push.
     */
    suspend fun registerToken(token: String, userId: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (!AuthManager.signedIn) return@withContext Result.success(Unit)
        if (SettingsStore.getBool("push_local", false)) {
            return@withContext Result.success(Unit)
        }
        try {
            val body = RegisterTokenRequest(
                token = token,
                platform = "android",
                app_version = BuildConfig.VERSION_NAME,
                user_id = userId,
            )

            val requestBody = json.encodeToString(RegisterTokenRequest.serializer(), body)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${Secrets.controlPanelUrl}/api/v1/notif/register-token")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("X-Install-Id", SettingsStore.installId())
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unregister FCM token when user signs out or switches to local-only push.
     */
    suspend fun unregisterToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = UnregisterTokenRequest(token = token)

            val requestBody = json.encodeToString(UnregisterTokenRequest.serializer(), body)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${Secrets.controlPanelUrl}/api/v1/notif/unregister-token")
                .delete(requestBody)
                .header("Content-Type", "application/json")
                .header("X-Install-Id", SettingsStore.installId())
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Server error: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

@Serializable
private data class RegisterTokenRequest(
    val token: String,
    val platform: String,
    val app_version: String,
    val user_id: String? = null,
)

@Serializable
private data class UnregisterTokenRequest(
    val token: String,
)
