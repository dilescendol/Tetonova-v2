package com.tetonova.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentBreakdownTest {
    @Test
    fun monthlyTwentyFiveThousandUsesCurrentQrisBasicTariff() {
        assertEquals(
            PaymentBreakdown(25_000L, 1_175L, 0L, 26_175L),
            paymentBreakdown(25_000L),
        )
    }

    @Test
    fun quarterlyPriceShowsGatewayFeeAndFinalTotal() {
        assertEquals(
            PaymentBreakdown(
                subtotalIdr = 60_000L,
                adminFeeIdr = 1_420L,
                taxIdr = 0L,
                totalIdr = 61_420L,
            ),
            paymentBreakdown(60_000L),
        )
    }

    @Test
    fun feeRoundsUpToAWholeRupiah() {
        assertEquals(1_001L, paymentBreakdown(1L).adminFeeIdr)
    }

    @Test
    fun nonPositivePriceCannotProduceACharge() {
        assertEquals(PaymentBreakdown(0L, 0L, 0L, 0L), paymentBreakdown(-1L))
    }
}
