package com.tetonova.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for the panel's per-account library sync (`/api/v1/me/library`). Auth is a Firebase ID token
 * passed as `Authorization: Bearer <idToken>` (from [AuthManager]). Offline-first like [UserApi]:
 * a blank base, unreachable host, non-2xx (incl. `401 auth_unavailable`), or parse error yields null
 * so [LibrarySync] simply keeps the local stores untouched.
 */
class LibrarySyncApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Pull the account's authoritative library set. */
    suspend fun pull(idToken: String): LibraryPayload? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("$base/api/v1/me/library")
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<LibraryResponse>(resp.body?.string().orEmpty()).library
            }
        }.onFailure { Log.w("TnLibSync", "pull failed: ${it.message}") }.getOrNull()
    }

    /** Push the local set; the server merges (last-write-wins) and returns the merged set. */
    suspend fun sync(idToken: String, payload: LibraryPayload): LibraryPayload? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext null
        runCatching {
            val body = json.encodeToString(LibraryPayload.serializer(), payload)
            val req = Request.Builder()
                .url("$base/api/v1/me/library/sync")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $idToken")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<LibraryResponse>(resp.body?.string().orEmpty()).library
            }
        }.onFailure { Log.w("TnLibSync", "sync failed: ${it.message}") }.getOrNull()
    }
}
