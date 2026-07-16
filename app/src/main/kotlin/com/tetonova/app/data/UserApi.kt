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

/** The user's cultivation progress (panel `GET /api/v1/users/{id}/xp` → `user`). */
@Serializable
data class UserXp(
    val level: Int = 1,
    val xpIntoLevel: Int = 0,
    val xpForNextLevel: Int = 0,
    val xpToday: Int = 0,
    val dailyCapRemaining: Int = 0,
    val streakDays: Int = 0,
    val streakFreezes: Int = 0,
    val equippedFrame: String = "",
    val flags: List<String> = emptyList(),
    val realm: Realm? = null,
    val stats30d: Stats30d? = null,
    val contributor: ContributorRank? = null,
)

/** Current cultivation realm for the user's level. */
@Serializable
data class Realm(
    val realmId: String = "",
    val realmDisplayId: String = "",
    val subStageLabel: String = "",
    val displayName: String = "",
)

/** Last-30-days watch tiles for the "Statistik nonton" grid. */
@Serializable
data class Stats30d(
    val watchHours: Double = 0.0,
    val episodes: Int = 0,
    val episodesCompleted: Int = 0,
)

/** Live contribution rank derived by the panel from cross-user XP totals. */
@Serializable
data class ContributorRank(
    val rank: Int = 0,
    val total: Int = 0,
    val topPercent: Int = 0,
    val label: String = "",
)

/** One realm tier on the "Jalan Kultivasi" ladder (panel `GET /api/v1/realms`). */
@Serializable
data class RealmTier(
    val realmId: String = "",
    val displayId: String = "",
    val minLevel: Int = 0,
    val maxLevel: Int = 0,
    val frameUrl: String? = null,
    val badgeUrl: String? = null,
)

/** One playback heartbeat — mirrors the server watch-session validator's fields. */
data class Heartbeat(
    val capturedAt: Long,
    val playheadMs: Long,
    val realtimeElapsedMs: Long,
    val playbackRate: Double,
    val isForeground: Boolean,
    val lastInteractionMs: Long,
    val episodeDurationMs: Long,
)

/** One cultivation achievement (panel `GET /api/v1/users/{id}/achievements`). */
@Serializable
data class Achievement(
    val id: String = "",
    val family: String = "",
    val name: String = "",
    val desc: String = "",
    val icon: String = "",
    val grad: Int = 0,
    val hidden: Boolean = false,
    val tier: Int = 0,
    val maxTier: Int = 1,
    val unlocked: Boolean = false,
)

@Serializable
private data class UserXpResponse(val installId: String = "", val user: UserXp? = null)

@Serializable
private data class RealmsResponse(val realms: List<RealmTier> = emptyList())

@Serializable
private data class AchievementsResponse(val achievements: List<Achievement> = emptyList())

/**
 * Client for the panel's per-install XP/realm reads + watch-session telemetry. Offline-first like
 * [TrendingApi]: a blank base, an unreachable host, or a non-2xx response yields null / a no-op so
 * the Profile screen quietly falls back to its bundled sample data.
 */
class UserApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * When signed in, [bearer] is the Firebase ID token — the server then owns the data by ACCOUNT
     * (`acct:<uid>`) and ignores the install id, so level/XP follow the account across devices.
     */
    private fun Request.Builder.auth(token: String, installId: String, bearer: String?): Request.Builder {
        if (bearer != null) header("Authorization", "Bearer $bearer")
        return header("X-TN-Telemetry-Token", token).header("X-TN-Install-Id", installId)
    }

    /** Read the user's XP/level/streak/realm + 30-day stats. Owned by account when [bearer] is set. */
    suspend fun fetchXp(installId: String, token: String, bearer: String? = null): UserXp? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || (token.isBlank() && bearer == null)) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/users/$installId/xp").auth(token, installId, bearer).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<UserXpResponse>(resp.body?.string().orEmpty()).user
            }
        }.onFailure { Log.w("TnUserXp", "fetchXp failed: ${it.message}") }.getOrNull()
    }

    /** Read the user's cultivation achievements (server evaluates + persists unlocks). */
    suspend fun fetchAchievements(installId: String, token: String, bearer: String? = null): List<Achievement>? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || (token.isBlank() && bearer == null)) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/users/$installId/achievements").auth(token, installId, bearer).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<AchievementsResponse>(resp.body?.string().orEmpty()).achievements
            }
        }.onFailure { Log.w("TnUserXp", "fetchAchievements failed: ${it.message}") }.getOrNull()
    }

    /** Equip a reached realm's frame cosmetic on the avatar. Returns true on success. */
    suspend fun equipFrame(installId: String, realmId: String, token: String, bearer: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (base.isEmpty() || (token.isBlank() && bearer == null)) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$base/api/v1/users/$installId/equip-frame")
                .header("Content-Type", "application/json")
                .auth(token, installId, bearer)
                .post(JSONObject().put("realmId", realmId).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.onFailure { Log.w("TnUserXp", "equipFrame failed: ${it.message}") }.getOrDefault(false)
    }

    /** Absorb this device's anonymous progress into the signed-in account (once per device). */
    suspend fun mergeCultivation(installId: String, bearer: String): Boolean = withContext(Dispatchers.IO) {
        if (base.isEmpty() || installId.isBlank() || bearer.isBlank()) return@withContext false
        runCatching {
            val req = Request.Builder()
                .url("$base/api/v1/me/cultivation/merge")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $bearer")
                .post(JSONObject().put("installId", installId).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.onFailure { Log.w("TnUserXp", "mergeCultivation failed: ${it.message}") }.getOrDefault(false)
    }

    /** The cultivation realm ladder (public; no token). */
    suspend fun fetchRealms(): List<RealmTier>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/realms").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<RealmsResponse>(resp.body?.string().orEmpty())
                    .realms.filter { it.realmId.isNotBlank() }
            }
        }.onFailure { Log.w("TnUserXp", "fetchRealms failed: ${it.message}") }.getOrNull()
    }

    /** Upload a batch of playback heartbeats for one watch session (fire-and-forget). */
    suspend fun postWatchSession(
        installId: String,
        sessionId: String,
        episodeId: String,
        sourceId: String,
        heartbeats: List<Heartbeat>,
        token: String,
        bearer: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        if (base.isEmpty() || (token.isBlank() && bearer == null) || heartbeats.size < 2) return@withContext
        runCatching {
            val hb = JSONArray()
            heartbeats.forEach { h ->
                hb.put(
                    JSONObject()
                        .put("capturedAt", h.capturedAt)
                        .put("playheadMs", h.playheadMs)
                        .put("realtimeElapsedMs", h.realtimeElapsedMs)
                        .put("playbackRate", h.playbackRate)
                        .put("isForeground", h.isForeground)
                        .put("lastInteractionMs", h.lastInteractionMs)
                        .put("episodeDurationMs", h.episodeDurationMs)
                )
            }
            val payload = JSONObject()
                .put("installId", installId)
                .put("sessionId", sessionId)
                .put("episodeId", episodeId)
                .put("sourceId", sourceId)
                .put("heartbeats", hb)
                .toString()
            val req = Request.Builder()
                .url("$base/api/v1/telemetry/watch-session")
                .header("Content-Type", "application/json")
                .auth(token, installId, bearer)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { /* fire-and-forget */ }
        }.onFailure { Log.w("TnUserXp", "postWatchSession failed: ${it.message}") }
    }
}
