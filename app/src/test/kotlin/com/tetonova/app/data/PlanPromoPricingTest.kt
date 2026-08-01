package com.tetonova.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlanPromoPricingTest {
    @Test
    fun activePromoShowsOriginalPriceAndAutomaticDiscountLabel() {
        val view = planViews(
            listOf(
                BillingPlan(
                    code = "monthly",
                    displayName = "Monthly",
                    durationSeconds = 2_592_000L,
                    priceIdr = 15_000L,
                    originalPriceIdr = 25_000L,
                    promoActive = true,
                    promoDiscountPercent = 40,
                )
            )
        ).single()

        assertEquals("Rp 15.000", view.priceLabel)
        assertEquals("Rp 25.000", view.originalPriceLabel)
        assertEquals("Hemat 40%", view.promoBadgeLabel)
    }

    @Test
    fun ordinaryPlanHasNoPromoDecoration() {
        val view = planViews(
            listOf(BillingPlan("monthly", "Monthly", 2_592_000L, 25_000L))
        ).single()

        assertNull(view.originalPriceLabel)
        assertNull(view.promoBadgeLabel)
    }
}
