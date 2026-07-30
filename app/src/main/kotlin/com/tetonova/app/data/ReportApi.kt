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
enum class ContentReportReason(val code: String, val label: String) {
    PLAYBACK_ERROR("playback_error", "Tidak bisa diputar"),
    SOURCE_ERROR("source_error", "Source bermasalah"),
    METADATA_ERROR("metadata_error", "Judul atau episode salah"),
    INAPPROPRIATE_CONTENT("inappropriate_content", "Konten tidak pantas"),
    SENSITIVE_LABEL_ERROR("sensitive_label_error", "Label konten sensitif salah"),
    OTHER("other", "Lainnya"),
}

object ReportApi {

    private val client = TnHttp.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun submit(reason: ContentReportReason, details: String, contact: String): Result<Unit> = withContext(Dispatchers.IO) {
        val base = Secrets.controlPanelUrl.trim().trimEnd('/')
        val token = TnData.telemetryToken
        if (base.isEmpty() || token.isBlank()) {
            return@withContext Result.failure(IllegalStateException("report endpoint not configured"))
        }
        runCatching {
            val idToken = AuthManager.idToken()
            val cleanDetails = details.trim()
            // Keep `message` useful for panel deployments that do not read the
            // structured report fields yet.
            val compatibilityMessage = buildString {
                append(reason.label)
                if (cleanDetails.isNotEmpty()) {
                    append("\n\n")
                    append(cleanDetails)
                }
            }
            val payload = JSONObject()
                .put("reason", reason.code)
                .put("details", cleanDetails)
                .put("message", compatibilityMessage)
                .put("contact", contact)
                .put("app_version", BuildConfig.VERSION_NAME)
                .toString()
            val request = Request.Builder()
                .url("$base/api/v1/content-report")
                .header("Content-Type", "application/json")
                .header("X-TN-Telemetry-Token", token)
                .header("X-TN-Install-Id", SettingsStore.installId())
                .post(payload.toRequestBody("application/json".toMediaType()))
            if (!idToken.isNullOrBlank()) {
                request.header("Authorization", "Bearer $idToken")
            }
            val req = request.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
            }
        }
    }
}
