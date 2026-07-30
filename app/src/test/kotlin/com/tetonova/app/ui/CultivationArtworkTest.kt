package com.tetonova.app.ui

import com.tetonova.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CultivationArtworkTest {
    @Test
    fun pathArtworkUsesOriginBelowAndSharedSealAboveAscension() {
        assertEquals(R.drawable.cultivation_path_dou_qi_v1, cultivationPathArtworkRes("dou-qi", "u118"))
        assertEquals(R.drawable.cultivation_path_martial_v1, cultivationPathArtworkRes("martial", "u145"))
        assertEquals(R.drawable.cultivation_path_great_thousand_v1, cultivationPathArtworkRes("martial", "g326"))
    }

    @Test
    fun allLockedSpecMilestonesHaveBundledArtwork() {
        val ids = listOf(
            "dq_spark", "dq_king_wings", "dq_space_splitter", "dq_saint_path", "dq_dou_emperor",
            "mu_yuan_core", "mu_manifestation", "mu_nine_tribulations", "mu_samsara", "mu_martial_ancestor",
            "gt_beyond_lower_world", "gt_true_sovereign", "gt_heaven_earth", "gt_sky_record", "gt_great_ruler",
        )
        ids.forEach { assertNotNull("Missing artwork for $it", achievementArtworkRes(it)) }
        assertNull(achievementArtworkRes("legacy-or-unknown"))
    }
}
