package com.tetonova.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Thin client for the control-panel public sources feed (`GET <base>/api/v1/sources`).
 * Offline-first: a blank base URL, an unreachable host, or a malformed response all yield an
 * empty list instead of throwing — the app then just runs on the bundled registry.
 */
class SourceApi(private val baseUrl: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): SourcesResponse? = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/sources").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else json.decodeFromString<SourcesResponse>(body)
            }
        }.getOrNull()
    }
}
