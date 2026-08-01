package com.tetonova.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CultivationLevelUpEventTest {
    @Test
    fun createsOneEventForARegularLevelIncrease() {
        val event = mergeCultivationLevelUpEvent(
            pending = null,
            previousLevel = 8,
            newLevel = 9,
            realmName = "Tempered Body",
            realmChanged = false,
        )

        assertEquals(8, event?.fromLevel)
        assertEquals(9, event?.level)
        assertEquals(1, event?.levelsGained)
        assertFalse(event!!.realmChanged)
    }

    @Test
    fun mergesSeveralInPlayerLevelIncreasesIntoOneNotice() {
        val first = mergeCultivationLevelUpEvent(null, 8, 9, "Tempered Body", false)
        val merged = mergeCultivationLevelUpEvent(first, 9, 12, "Qi Gathering", true)

        assertEquals(8, merged?.fromLevel)
        assertEquals(12, merged?.level)
        assertEquals(4, merged?.levelsGained)
        assertEquals("Qi Gathering", merged?.realmName)
        assertTrue(merged!!.realmChanged)
    }

    @Test
    fun ignoresRefreshesThatDoNotIncreaseTheLevel() {
        assertNull(mergeCultivationLevelUpEvent(null, 9, 9, "Tempered Body", false))

        val pending = CultivationLevelUpEvent(8, 9, "Tempered Body", false)
        assertEquals(pending, mergeCultivationLevelUpEvent(pending, 9, 9, "Tempered Body", false))
    }
}
