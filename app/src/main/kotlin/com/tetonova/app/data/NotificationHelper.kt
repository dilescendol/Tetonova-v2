package com.tetonova.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tetonova.app.R
import com.tetonova.app.MainActivity

/**
 * NotificationHelper manages notification channels and creates notifications for:
 * - Follow episode updates (new episodes from followed titles)
 * - Forum replies (replies to user's comments/threads)
 */
object NotificationHelper {

    const val CHANNEL_FOLLOW = "follow_updates"
    const val CHANNEL_FORUM = "forum_replies"
    const val CHANNEL_ANNOUNCEMENT = "announcements_v2"
    const val CHANNEL_SUBSCRIPTION = "subscription_expiry"

    private const val CHANNEL_FOLLOW_ID = 1001
    private const val CHANNEL_FORUM_ID = 1002
    private const val CHANNEL_ANNOUNCEMENT_ID = 1003
    private const val CHANNEL_SUBSCRIPTION_ID = 1004

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val announcementSound = announcementSoundUri(context)
            val announcementAudio = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Follow updates channel
            val followChannel = NotificationChannel(
                CHANNEL_FOLLOW,
                "Update Episode",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifikasi saat episode baru rilis dari judul yang kamu ikuti"
            }

            // Forum replies channel
            val forumChannel = NotificationChannel(
                CHANNEL_FORUM,
                "Balasan Forum",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi saat seseorang membalas thread atau komentar kamu"
            }

            val announcementChannel = NotificationChannel(
                CHANNEL_ANNOUNCEMENT,
                "Pengumuman",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Info penting dari TetoNova"
                setSound(announcementSound, announcementAudio)
            }

            val subscriptionChannel = NotificationChannel(
                CHANNEL_SUBSCRIPTION,
                "Masa Aktif Premium",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Pengingat saat langganan Premium mendekati tanggal berakhir"
            }

            nm.createNotificationChannels(listOf(followChannel, forumChannel, announcementChannel, subscriptionChannel))
        }
    }

    /**
     * Show notification for new episode from followed title.
     */
    fun showFollowNotification(
        context: Context,
        title: String,
        episode: String,
        detailUrl: String,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_detail", detailUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            detailUrl.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_FOLLOW)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText("Episode $episode sudah tersedia!")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        post(context, detailUrl.hashCode(), notification)
    }

    /**
     * Show notification for forum reply.
     */
    fun showForumNotification(
        context: Context,
        threadTitle: String,
        replyText: String,
        forumUrl: String,
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_forum", forumUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            forumUrl.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_FORUM)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle("Balasan di: $threadTitle")
            .setContentText(replyText.take(100))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        post(context, forumUrl.hashCode() + 1, notification)
    }

    fun showAnnouncementNotification(
        context: Context,
        title: String,
        body: String,
        url: String?,
    ) {
        val targetUrl = url?.trim().orEmpty()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (targetUrl.startsWith("tetonova://")) {
                data = Uri.parse(targetUrl)
            } else if (targetUrl.isNotBlank()) {
                putExtra("open_detail", targetUrl)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (title + body + targetUrl).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(announcementSoundUri(context))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        post(context, (title + body + targetUrl).hashCode(), notification)
    }

    /** Daily H-5..H reminder for paid Premium subscriptions. */
    fun showSubscriptionExpiryNotification(context: Context, daysLeft: Long): Boolean {
        val title = when (daysLeft) {
            0L -> "Premium berakhir hari ini"
            1L -> "Premium berakhir besok"
            else -> "Premium berakhir dalam $daysLeft hari"
        }
        val body = "Perpanjang sekarang agar akses source Premium dan drama pendek tidak terputus."
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_subscription", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            CHANNEL_SUBSCRIPTION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SUBSCRIPTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        return post(context, CHANNEL_SUBSCRIPTION_ID, notification)
    }

    /**
     * Cancel a notification by ID (e.g., when user unfollows a title).
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun post(context: Context, notificationId: Int, notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        NotificationManagerCompat.from(context).notify(notificationId, notification)
        return true
    }

    private fun announcementSoundUri(context: Context): Uri =
        Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.notif_tetonova}")
}
