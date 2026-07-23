package com.tetonova.app.data

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubscriptionExpiryReminderTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val now = Instant.parse("2026-07-23T03:00:00Z") // 10:00 WIB

    @Test
    fun `active subscription starts reminding at H minus 5`() {
        val sub = SubscriptionState(status = "active", currentExpiry = "2026-07-28T13:00:00Z")
        assertEquals(5L, subscriptionReminderDaysLeft(sub, now, zone))
    }

    @Test
    fun `active subscription reminds on expiry day while still valid`() {
        val sub = SubscriptionState(status = "active", currentExpiry = "2026-07-23T13:00:00Z")
        assertEquals(0L, subscriptionReminderDaysLeft(sub, now, zone))
    }

    @Test
    fun `subscription outside H minus 5 window is ignored`() {
        val sub = SubscriptionState(status = "active", currentExpiry = "2026-07-29T13:00:00Z")
        assertNull(subscriptionReminderDaysLeft(sub, now, zone))
    }

    @Test
    fun `trial never receives paid expiry reminder`() {
        val sub = SubscriptionState(status = "trial", currentExpiry = "2026-07-23T13:00:00Z")
        assertNull(subscriptionReminderDaysLeft(sub, now, zone))
    }

    @Test
    fun `already expired subscription is ignored`() {
        val sub = SubscriptionState(status = "active", currentExpiry = "2026-07-23T02:59:59Z")
        assertNull(subscriptionReminderDaysLeft(sub, now, zone))
    }
}
