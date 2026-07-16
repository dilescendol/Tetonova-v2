package com.tetonova.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The user's editable profile (display name + public handle + avatar URL) synced to the account. */
@Serializable
data class UserProfile(
    val displayName: String = "",
    val username: String = "",
    val photoUrl: String = "",
)

@Serializable
private data class ProfileResponse(val profile: UserProfile = UserProfile())

/**
 * Client for the panel's per-account editable profile (`/api/v1/me/profile` + `/api/v1/me/avatar`).
 * Auth is a Firebase ID token as `Authorization: Bearer <idToken>` (from [AuthManager]) — same shape
 * as [LibrarySyncApi]. Offline-first: a blank base, unreachable host, or non-2xx yields null / a
 * [SaveOutcome.Failed] so the Profile screen quietly keeps its cached/sample identity.
 */
class ProfileApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Outcome of a save so the dialog can show the right message (collision vs invalid vs offline). */
    sealed interface SaveOutcome {
        data class Success(val profile: UserProfile) : SaveOutcome
        data object UsernameTaken : SaveOutcome
        data object Invalid : SaveOutcome
        data object Failed : SaveOutcome
    }

    /** Read the account's effective profile (user edits over Google claims). */
    suspend fun getProfile(idToken: String): UserProfile? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("$base/api/v1/me/profile")
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<ProfileResponse>(resp.body?.string().orEmpty()).profile
            }
        }.onFailure { Log.w("TnProfile", "getProfile failed: ${it.message}") }.getOrNull()
    }

    /** Persist display name + handle. Distinguishes 409 (handle taken) and 422 (invalid). */
    suspend fun updateProfile(idToken: String, displayName: String, username: String): SaveOutcome =
        withContext(Dispatchers.IO) {
            if (base.isEmpty() || idToken.isBlank()) return@withContext SaveOutcome.Failed
            runCatching {
                val payload = JSONObject()
                    .put("displayName", displayName)
                    .put("username", username)
                    .toString()
                val req = Request.Builder()
                    .url("$base/api/v1/me/profile")
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $idToken")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 409 -> SaveOutcome.UsernameTaken
                        resp.code == 422 -> SaveOutcome.Invalid
                        resp.isSuccessful -> SaveOutcome.Success(
                            json.decodeFromString<ProfileResponse>(resp.body?.string().orEmpty()).profile
                        )
                        else -> SaveOutcome.Failed
                    }
                }
            }.onFailure { Log.w("TnProfile", "updateProfile failed: ${it.message}") }
                .getOrDefault(SaveOutcome.Failed)
        }

    /** Upload an avatar photo (multipart). Returns the refreshed profile with the new photo URL. */
    suspend fun uploadAvatar(idToken: String, bytes: ByteArray, mime: String): UserProfile? =
        withContext(Dispatchers.IO) {
            if (base.isEmpty() || idToken.isBlank() || bytes.isEmpty()) return@withContext null
            runCatching {
                val ext = when (mime) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "avatar.$ext", bytes.toRequestBody(mime.toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url("$base/api/v1/me/avatar")
                    .header("Authorization", "Bearer $idToken")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    json.decodeFromString<ProfileResponse>(resp.body?.string().orEmpty()).profile
                }
            }.onFailure { Log.w("TnProfile", "uploadAvatar failed: ${it.message}") }.getOrNull()
        }
}
