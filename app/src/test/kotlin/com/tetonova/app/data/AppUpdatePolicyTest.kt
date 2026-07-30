package com.tetonova.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    @Test
    fun `build below minimum requires update`() {
        assertTrue(isMandatoryUpdateRequired(currentVersionCode = 2, minimumVersionCode = 3))
    }

    @Test
    fun `minimum and newer builds remain usable`() {
        assertFalse(isMandatoryUpdateRequired(currentVersionCode = 3, minimumVersionCode = 3))
        assertFalse(isMandatoryUpdateRequired(currentVersionCode = 4, minimumVersionCode = 3))
    }

    @Test
    fun `zero minimum disables mandatory update`() {
        assertFalse(isMandatoryUpdateRequired(currentVersionCode = 1, minimumVersionCode = 0))
    }
}
