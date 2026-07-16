package com.tetonova.app.data

import com.tetonova.app.BuildConfig
import com.tetonova.app.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Submits the in-app "Lapor konten bermasalah" report to the control panel
 * (`POST <base>/api/v1/content-report`). Auth reuses the telemetry ingest token
 * the panel publishes in `/api/v1/sources` (kept in [TnData.telemetryToken]).
 *
 * Offline-first like [SourceApi]: a blank base/token, an unreachable host, or a
 * non-2xx response all return [Result.failure] instead of throwing, so the UI
 * can show a friendly toast rather than crashing.
 */
object ReportApi {

    private val client = TnHttp.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun submit(message: String, contact: String): Result<Unit> = withContext(Dispatchers.IO) {
        val base = Secrets.controlPanelUrl.trim().trimEnd('/')
        val token = TnData.telemetryToken
        if (base.isEmpty() || token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("report endpoint not configured"))
        }
        runCatching {
            val payload = JSONObject()
                .put("message", message)
                .put("contact", contact)
                .put("app_version", BuildConfig.VERSION_NAME)
                .toString()
            val req = Request.Builder()
                .url("$base/api/v1/content-report")
                .header("Content-Type", "application/json")
                .header("X-TN-Telemetry-Token", token)
                .header("X-TN-Install-Id", SettingsStore.installId())
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
            }
        }
    }
}
