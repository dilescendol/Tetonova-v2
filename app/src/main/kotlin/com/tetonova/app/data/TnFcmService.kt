package com.tetonova.app.data

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Firebase Cloud Messaging service for handling push notifications.
 *
 * Handles:
 * - New episode notifications from followed titles
 * - Forum reply notifications
 * - FCM token refresh for server registration
 */
class TnFcmService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val type = data["type"]

        when (type) {
            "episode" -> handleEpisodeNotification(data)
            "forum_reply" -> handleForumNotification(data)
            "announcement" -> handleAnnouncementNotification(data)
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        // Save token locally
        FcmTokenHolder.token = token

        // Register with server
        serviceScope.launch {
            FcmRegistration.registerToken(token)
        }
    }

    private fun handleEpisodeNotification(data: Map<String, String>) {
        val title = data["title"] ?: return
        val episode = data["episode"] ?: return
        val url = data["url"] ?: return

        NotificationHelper.showFollowNotification(
            context = applicationContext,
            title = title,
            episode = episode,
            detailUrl = url,
        )
    }

    private fun handleForumNotification(data: Map<String, String>) {
        val threadTitle = data["thread_title"] ?: return
        val replyText = data["reply_text"] ?: return
        val forumUrl = data["forum_url"] ?: return

        NotificationHelper.showForumNotification(
            context = applicationContext,
            threadTitle = threadTitle,
            replyText = replyText,
            forumUrl = forumUrl,
        )
    }

    private fun handleAnnouncementNotification(data: Map<String, String>) {
        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "TetoNova"
        val body = data["body"] ?: data["message"] ?: return

        NotificationHelper.showAnnouncementNotification(
            context = applicationContext,
            title = title,
            body = body,
            url = data["url"],
        )
    }
}

/**
 * Simple holder for FCM token - used until we have a proper store.
 */
object FcmTokenHolder {
    @Volatile
    var token: String? = null
}
