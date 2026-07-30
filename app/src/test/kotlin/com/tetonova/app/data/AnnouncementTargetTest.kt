package com.tetonova.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class AnnouncementTargetTest {
    @Test
    fun routesTetoNovaDeepLinksBackIntoTheApp() {
        assertEquals(
            AnnouncementTargetKind.APP_DEEP_LINK,
            classifyAnnouncementTarget("tetonova://trial"),
        )
    }

    @Test
    fun routesHttpLinksToTheBrowser() {
        assertEquals(
            AnnouncementTargetKind.WEB,
            classifyAnnouncementTarget("https://tetonova.biz.id/#download"),
        )
        assertEquals(
            AnnouncementTargetKind.WEB,
            classifyAnnouncementTarget(" HTTP://tetonova.biz.id/#download "),
        )
    }

    @Test
    fun opensTheAppForBlankOrUnsupportedTargets() {
        assertEquals(AnnouncementTargetKind.OPEN_APP, classifyAnnouncementTarget(null))
        assertEquals(AnnouncementTargetKind.OPEN_APP, classifyAnnouncementTarget(""))
        assertEquals(
            AnnouncementTargetKind.OPEN_APP,
            classifyAnnouncementTarget("javascript:alert(1)"),
        )
    }
}
