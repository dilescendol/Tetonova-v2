package com.tetonova.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementSnoozeTest {
    @Test
    fun matchingAnnouncementIsHiddenForSixHours() {
        val dismissedAt = 1_000L

        assertTrue(
            isAnnouncementSnoozed(
                announcementId = "announcement-v1",
                dismissedAnnouncementId = "announcement-v1",
                snoozedUntilMs = dismissedAt + ANNOUNCEMENT_SNOOZE_MS,
                nowMs = dismissedAt + ANNOUNCEMENT_SNOOZE_MS - 1L,
            ),
        )
        assertFalse(
            isAnnouncementSnoozed(
                announcementId = "announcement-v1",
                dismissedAnnouncementId = "announcement-v1",
                snoozedUntilMs = dismissedAt + ANNOUNCEMENT_SNOOZE_MS,
                nowMs = dismissedAt + ANNOUNCEMENT_SNOOZE_MS,
            ),
        )
    }

    @Test
    fun newAnnouncementBypassesPreviousSnooze() {
        assertFalse(
            isAnnouncementSnoozed(
                announcementId = "announcement-v2",
                dismissedAnnouncementId = "announcement-v1",
                snoozedUntilMs = Long.MAX_VALUE,
                nowMs = 1_000L,
            ),
        )
    }

    @Test
    fun legacyDismissWithoutExpiryDoesNotHideAnnouncement() {
        assertFalse(
            isAnnouncementSnoozed(
                announcementId = "announcement-v1",
                dismissedAnnouncementId = "announcement-v1",
                snoozedUntilMs = 0L,
                nowMs = 1_000L,
            ),
        )
    }
}
