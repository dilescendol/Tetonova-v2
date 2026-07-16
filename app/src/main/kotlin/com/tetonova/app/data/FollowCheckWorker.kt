package com.tetonova.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tetonova.app.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically checks for new episodes from followed titles.
 * Calls panel API to get followed titles and their latest episodes, then shows local notifications.
 */
class FollowCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val client = TnHttp.client
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val ctx = applicationContext

            val prefs = ctx.getSharedPreferences("tetonova_settings", Context.MODE_PRIVATE)
            val panelUrl = Secrets.controlPanelUrl
            // Stable per-install id: the panel keys followed titles / forum identity by it, so the
            // request has to carry it or the server can't tell whose notifications to return.
            val installId = SettingsStore.installId()

            // "Follow notifications" — new episodes from followed titles.
            if (prefs.getBoolean("notif_follow", true)) {
                for (episode in fetchNewEpisodes(panelUrl, installId)) {
                    NotificationHelper.showFollowNotification(
                        context = ctx,
                        title = episode.title,
                        episode = episode.episode,
                        detailUrl = episode.url,
                    )
                }
            }

            // "Balasan forum" — replies to the user's own threads/posts.
            if (prefs.getBoolean("notif_forum", true)) {
                for (reply in fetchForumReplies(panelUrl, installId)) {
                    NotificationHelper.showForumNotification(
                        context = ctx,
                        threadTitle = reply.threadTitle,
                        replyText = reply.replyText,
                        forumUrl = reply.url,
                    )
                }
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("FollowCheck", "Error: ${e.message}")
            Result.retry()
        }
    }

    private fun fetchNewEpisodes(panelUrl: String, installId: String): List<NewEpisodeResponse> {
        return try {
            val request = Request.Builder()
                .url("$panelUrl/api/v1/notif/new-episodes")
                .header("X-Install-Id", installId)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyList()
                    json.decodeFromString<NewEpisodesResponse>(body).episodes
                } else {
                    android.util.Log.e("FollowCheck", "API error: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FollowCheck", "Fetch error: ${e.message}")
            emptyList()
        }
    }

    private fun fetchForumReplies(panelUrl: String, installId: String): List<ForumReplyResponse> {
        return try {
            val request = Request.Builder()
                .url("$panelUrl/api/v1/notif/forum-replies?installId=$installId")
                .header("X-Install-Id", installId)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return emptyList()
                    json.decodeFromString<ForumRepliesResponse>(body).replies
                } else {
                    android.util.Log.e("FollowCheck", "Forum API error: ${response.code}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FollowCheck", "Forum fetch error: ${e.message}")
            emptyList()
        }
    }

    companion object {
        const val WORK_NAME = "follow_check_worker"

        /**
         * Schedule periodic follow check every 6 hours.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<FollowCheckWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Cancel scheduled follow check.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

@Serializable
private data class NewEpisodesResponse(
    val episodes: List<NewEpisodeResponse>
)

@Serializable
private data class NewEpisodeResponse(
    val title: String,
    val episode: String,
    val url: String,
    val cover: String? = null,
)

@Serializable
private data class ForumRepliesResponse(
    val replies: List<ForumReplyResponse> = emptyList()
)

@Serializable
private data class ForumReplyResponse(
    val threadId: Int = 0,
    val threadTitle: String = "",
    val replyText: String = "",
    val author: String = "",
    val url: String = "",
)
