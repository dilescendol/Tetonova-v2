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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One forum thread as served by the panel (`serializeForumThread`). */
@Serializable
data class FThread(
    val id: Int = 0,
    val title: String = "",
    val category: String = "umum",
    val authorName: String = "",
    val authorRealm: String = "",
    val replyCount: Int = 0,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    val content: String? = null,
    val tags: List<String> = emptyList(),
)

/** One forum post/reply (`serializeForumPost`). */
@Serializable
data class FPost(
    val id: Int = 0,
    val threadId: Int = 0,
    val parentPostId: Int? = null,
    val authorName: String = "",
    val authorRealm: String = "",
    val content: String = "",
    val isHidden: Boolean = false,
    val createdAt: String = "",
)

/** Result of a forum write — `success` = it took effect; `message` = optional toast (moderation
 *  warning or error reason). A blocked-by-spam post returns success=false with the strike message. */
data class ForumActionResult(val success: Boolean, val message: String? = null)

@Serializable
private data class FThreadsResp(val threads: List<FThread> = emptyList(), val total: Int = 0)

@Serializable
private data class FThreadResp(val thread: FThread? = null, val posts: List<FPost> = emptyList())

/**
 * Client for the panel's public forum API (self-declared identity: installId + authorName + level).
 * Offline-first like the other clients: a blank base / unreachable host yields null / a failed
 * [ForumActionResult] so the screen degrades instead of crashing.
 */
class ForumApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun listThreads(sort: String, category: String, installId: String): List<FThread>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val cat = if (category == "all" || category.isBlank()) "" else "&category=$category"
            val iid = if (installId.isBlank()) "" else "&installId=$installId"
            val req = Request.Builder().url("$base/api/v1/forum/threads?limit=50&sort=$sort$cat$iid").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<FThreadsResp>(resp.body?.string().orEmpty()).threads
            }
        }.onFailure { Log.w("TnForum", "listThreads failed: ${it.message}") }.getOrNull()
    }

    /** Thread + its posts (also records a read server-side → author "thread read" XP). */
    suspend fun getThread(threadId: Int, installId: String): Pair<FThread, List<FPost>>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val iid = if (installId.isBlank()) "" else "?installId=$installId"
            val req = Request.Builder().url("$base/api/v1/forum/threads/$threadId$iid").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val r = json.decodeFromString<FThreadResp>(resp.body?.string().orEmpty())
                val t = r.thread ?: error("no thread")
                t to r.posts
            }
        }.onFailure { Log.w("TnForum", "getThread failed: ${it.message}") }.getOrNull()
    }

    suspend fun createThread(installId: String, authorName: String, title: String, content: String, category: String, tags: List<String>, level: Int, xp: Int): ForumActionResult =
        withContext(Dispatchers.IO) {
            val tagArr = org.json.JSONArray().also { a -> tags.forEach { a.put(it) } }
            postAction("$base/api/v1/forum/threads", JSONObject()
                .put("installId", installId).put("authorName", authorName)
                .put("title", title).put("content", content).put("category", category)
                .put("tags", tagArr).put("level", level).put("xp", xp))
        }

    suspend fun deleteThread(threadId: Int, installId: String): ForumActionResult =
        withContext(Dispatchers.IO) {
            postAction("$base/api/v1/forum/threads/$threadId/delete", JSONObject().put("installId", installId))
        }

    suspend fun deletePost(postId: Int, installId: String): ForumActionResult =
        withContext(Dispatchers.IO) {
            postAction("$base/api/v1/forum/posts/$postId/delete", JSONObject().put("installId", installId))
        }

    suspend fun createPost(threadId: Int, installId: String, authorName: String, content: String, level: Int, xp: Int): ForumActionResult =
        withContext(Dispatchers.IO) {
            postAction("$base/api/v1/forum/threads/$threadId/posts", JSONObject()
                .put("installId", installId).put("authorName", authorName)
                .put("content", content).put("level", level).put("xp", xp))
        }

    suspend fun vote(threadId: Int, installId: String, authorName: String, direction: String, level: Int, xp: Int): ForumActionResult =
        withContext(Dispatchers.IO) {
            postAction("$base/api/v1/forum/threads/$threadId/vote", JSONObject()
                .put("installId", installId).put("authorName", authorName)
                .put("direction", direction).put("level", level).put("xp", xp))
        }

    private fun postAction(url: String, payload: JSONObject): ForumActionResult {
        if (base.isEmpty()) return ForumActionResult(false, null)
        return runCatching {
            val req = Request.Builder().url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val o = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrDefault(JSONObject())
                when {
                    o.has("error") -> ForumActionResult(false, o.optString("message").ifBlank { o.optString("error").ifBlank { "Gagal" } })
                    !o.optBoolean("ok", true) && o.optString("code") == "moderation_strike" ->
                        ForumActionResult(false, strikeMessage(o) ?: "Konten ditahan moderasi.")
                    !resp.isSuccessful -> ForumActionResult(false, "Gagal (HTTP ${resp.code})")
                    else -> ForumActionResult(true, if (o.optString("code") == "moderation_strike") strikeMessage(o) else null)
                }
            }
        }.onFailure { Log.w("TnForum", "postAction failed: ${it.message}") }
            .getOrDefault(ForumActionResult(false, null))
    }

    private fun strikeMessage(o: JSONObject): String? =
        o.optJSONObject("strike")?.optString("userMessage")?.ifBlank { null }
}
