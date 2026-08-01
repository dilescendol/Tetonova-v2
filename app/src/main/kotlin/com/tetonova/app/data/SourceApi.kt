package com.tetonova.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    // Generous timeouts: the LDPlayer emulator's NAT'd network is slow to first-byte, and an 8s budget
    // was timing out → the app silently ran panel-less (no live Home, no live search).
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Fetch the sources feed, retrying a few times so a transient network hiccup on a cold start
     *  doesn't strand the app on the bundled registry for the whole session. */
    suspend fun fetch(): SourcesResponse? = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext null
        repeat(2) { attempt ->
            val r = runCatching {
                val req = Request.Builder().url("$base/api/v1/sources").get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) error("empty body") else json.decodeFromString<SourcesResponse>(body)
                }
            }
            r.getOrNull()?.let { return@withContext it }
            Log.w("TnPanel", "panel fetch attempt ${attempt + 1}/2 failed: ${r.exceptionOrNull()?.javaClass?.simpleName}: ${r.exceptionOrNull()?.message}")
            if (attempt < 1) delay(1200)
        }
        null
    }
}
