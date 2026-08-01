package com.tetonova.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One trending content row from the panel — enough to render a poster directly. */
@Serializable
data class TrendingItem(
    val title: String = "",
    val cover: String? = null,
    val url: String? = null,
    val badge: String? = null,
    val sourceId: String? = null,
)

@Serializable
private data class TrendingSearchesResponse(val terms: List<TrendingTerm> = emptyList())

@Serializable
private data class TrendingTerm(val term: String = "", val count: Int = 0)

@Serializable
private data class TrendingContentResponse(val items: List<TrendingItem> = emptyList())

/**
 * Client for the panel's cross-user trending feeds + telemetry of searches/opens. All calls are
 * best-effort and offline-first: a blank base, an unreachable host, or a non-2xx response yields
 * null / a no-op so the Search screen quietly falls back to its bundled data.
 */
class TrendingApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSearches(): List<String>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/trending-searches").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<TrendingSearchesResponse>(resp.body?.string().orEmpty())
                    .terms.map { it.term }.filter { it.isNotBlank() }
            }
        }.onFailure { Log.w("TnTrending", "fetchSearches failed: ${it.message}") }.getOrNull()
    }

    suspend fun fetchContent(): List<TrendingItem>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/trending-content").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<TrendingContentResponse>(resp.body?.string().orEmpty())
                    .items.filter { it.title.isNotBlank() }
            }
        }.onFailure { Log.w("TnTrending", "fetchContent failed: ${it.message}") }.getOrNull()
    }

    suspend fun reportSearch(term: String, token: String, installId: String): Unit = withContext(Dispatchers.IO) {
        if (base.isEmpty() || token.isBlank()) return@withContext
        runCatching {
            val payload = JSONObject()
                .put("terms", JSONArray().put(term))
                .put("installId", installId)
                .toString()
            post("$base/api/v1/telemetry/search", payload, token, installId)
        }
    }

    suspend fun reportOpen(
        title: String,
        url: String,
        cover: String?,
        badge: String?,
        sourceId: String?,
        token: String,
        installId: String,
    ): Unit = withContext(Dispatchers.IO) {
        if (base.isEmpty() || token.isBlank()) return@withContext
        runCatching {
            val payload = JSONObject()
                .put("title", title)
                .put("url", url)
                .put("cover", cover ?: "")
                .put("badge", badge ?: "")
                .put("sourceId", sourceId ?: "")
                .put("installId", installId)
                .toString()
            post("$base/api/v1/telemetry/open", payload, token, installId)
        }
    }

    private fun post(url: String, payload: String, token: String, installId: String) {
        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("X-TN-Telemetry-Token", token)
            .header("X-TN-Install-Id", installId)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { /* fire-and-forget */ }
    }
}
