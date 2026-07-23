package com.tetonova.app.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Small in-memory batcher for the panel's existing per-source telemetry endpoint. */
object SourceTelemetry {
    private data class Count(var requests: Int = 0, var errors: Int = 0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val pending = LinkedHashMap<String, Count>()
    private var flushJob: Job? = null

    fun record(sourceId: String, failed: Boolean) {
        if (sourceId.isBlank() || !AuthManager.signedIn) return
        synchronized(lock) {
            val count = pending.getOrPut(sourceId) { Count() }
            count.requests++
            if (failed) count.errors++
            if (flushJob?.isActive != true) flushJob = scope.launch { delay(10_000); flush() }
        }
    }

    suspend fun flush() {
        val batch = synchronized(lock) {
            if (pending.isEmpty()) return
            pending.toMap().also { pending.clear() }
        }
        val base = TnData.telemetryPanelBase()
        val token = TnData.telemetryToken
        val status = if (base.isBlank() || token.isBlank()) 0 else send(base, token, batch)
        if (status !in 200..299) {
            if (status == 401) TnData.warmPanel(base, force = true)
            synchronized(lock) {
                batch.forEach { (id, old) ->
                    pending.getOrPut(id) { Count() }.also {
                        it.requests += old.requests
                        it.errors += old.errors
                    }
                }
                flushJob = scope.launch { delay(30_000); flush() }
            }
        }
    }

    private fun send(base: String, token: String, batch: Map<String, Count>): Int = runCatching {
        val events = JSONArray()
        batch.forEach { (id, count) ->
            events.put(JSONObject().put("source_id", id).put("requests", count.requests).put("errors", count.errors))
        }
        val payload = JSONObject().put("install_id", SettingsStore.installId()).put("events", events).toString()
        val request = Request.Builder()
            .url("${base.trimEnd('/')}/api/v1/telemetry")
            .header("X-TN-Telemetry-Token", token)
            .header("X-TN-Install-Id", SettingsStore.installId())
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        TnHttp.client.newCall(request).execute().use { it.code }
    }.onFailure { Log.w("TnTelemetry", "flush failed: ${it.message}") }.getOrDefault(0)
}
