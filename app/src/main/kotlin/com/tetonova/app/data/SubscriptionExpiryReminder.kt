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
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Checks paid Premium expiry in the background. The worker may run more than once per day, while
 * [maybeNotify] keeps delivery at a strict maximum of one reminder per local calendar day.
 */
object SubscriptionExpiryReminder {
    private const val WORK_NAME = "subscription_expiry_reminder"
    private const val LAST_NOTIFICATION_DAY_KEY = "subscription_expiry_last_notification_day"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SubscriptionExpiryWorker>(
            12, TimeUnit.HOURS,
            1, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    suspend fun checkNow(context: Context) {
        if (!AuthManager.signedIn) return
        val token = AuthManager.idToken() ?: return
        val subscription = BillingApi(Secrets.controlPanelUrl).getSubscription(token) ?: return
        maybeNotify(context, subscription)
    }

    @Synchronized
    fun maybeNotify(context: Context, subscription: SubscriptionState?) {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val daysLeft = subscriptionReminderDaysLeft(subscription, now, zone) ?: return

        val dayKey = today.toString()
        if (SettingsStore.getStr(LAST_NOTIFICATION_DAY_KEY, "") == dayKey) return
        if (NotificationHelper.showSubscriptionExpiryNotification(context, daysLeft)) {
            SettingsStore.setStr(LAST_NOTIFICATION_DAY_KEY, dayKey)
        }
    }
}

/** Pure H-5..H boundary calculation kept separate so timezone and status rules stay testable. */
internal fun subscriptionReminderDaysLeft(
    subscription: SubscriptionState?,
    now: Instant,
    zone: ZoneId,
): Long? {
    // Trial is intentionally excluded: its short duration is already shown as a live countdown.
    if (subscription?.status != "active") return null
    val expiry = subscription.currentExpiry
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: return null
    if (!expiry.isAfter(now)) return null
    val today = now.atZone(zone).toLocalDate()
    val expiryDay = expiry.atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(today, expiryDay).takeIf { it in 0L..5L }
}

class SubscriptionExpiryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        SubscriptionExpiryReminder.checkNow(applicationContext)
        Result.success()
    }.getOrElse {
        android.util.Log.w("SubscriptionExpiry", "Reminder check failed: ${it.message}")
        Result.retry()
    }
}
