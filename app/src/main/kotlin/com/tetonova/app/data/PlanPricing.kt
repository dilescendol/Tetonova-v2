package com.tetonova.app.data

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 30 days in seconds — the unit the panel uses for a "monthly" plan (`app_plans.monthly`). */
private const val SECONDS_PER_MONTH = 2_592_000.0

/** Current Violet Pay QRIS Basic payer fee: Rp1,000 + 0.7% (70 basis points). */
private const val QRIS_ADMIN_FEE_FIXED_IDR = 1_000L
private const val QRIS_ADMIN_FEE_BPS = 70L
private const val BASIS_POINTS = 10_000L

/** Full payer-facing price shown before an invoice is created. No separate tax is currently charged. */
data class PaymentBreakdown(
    val subtotalIdr: Long,
    val adminFeeIdr: Long,
    val taxIdr: Long,
    val totalIdr: Long,
)

fun paymentBreakdown(subtotalIdr: Long): PaymentBreakdown {
    val safeSubtotal = subtotalIdr.coerceAtLeast(0L)
    // Round up so the disclosed total can never be lower than the gateway's whole-rupiah fee.
    val adminFee = if (safeSubtotal == 0L) 0L else QRIS_ADMIN_FEE_FIXED_IDR +
        (safeSubtotal * QRIS_ADMIN_FEE_BPS + BASIS_POINTS - 1L) / BASIS_POINTS
    val tax = 0L
    return PaymentBreakdown(safeSubtotal, adminFee, tax, safeSubtotal + adminFee + tax)
}

/** Offline/not-signed-in fallback so the Langganan screen always has plans to show. Mirrors the
 *  panel seed (monthly/quarterly/yearly); real prices come live from `GET /api/v1/me/plans`. */
val FALLBACK_PLANS: List<BillingPlan> = listOf(
    BillingPlan("monthly", "Bulanan", 2_592_000L, 15_000L),
    BillingPlan("quarterly", "3 Bulan", 7_776_000L, 40_000L),
    BillingPlan("yearly", "Tahunan", 31_536_000L, 120_000L),
)

/**
 * A plan ready to render: the raw [plan] plus the figures **derived client-side** from its
 * `priceIdr` + `durationSeconds`, so the panel only ever sets a price and the per-month + "Hemat %"
 * follow automatically (no manual discount math, no app rebuild).
 *
 * - [perMonthLabel] — "≈ Rp X/bln", only present when the plan spans more than one month.
 * - [savingsPercent] — vs the 1-month baseline; 0 when there's no saving (or no baseline).
 * - [best] — the plan with the highest saving (auto "Terpopuler"/best-value badge).
 */
data class PlanView(
    val plan: BillingPlan,
    val perMonthLabel: String?,
    val savingsPercent: Int,
    val best: Boolean,
) {
    val priceLabel: String get() = rupiah(plan.priceIdr)
    val originalPriceLabel: String? get() = plan.originalPriceIdr
        ?.takeIf { plan.promoActive && it > plan.priceIdr }
        ?.let(::rupiah)
    val promoDiscountPercent: Int get() {
        val original = plan.originalPriceIdr ?: return 0
        if (!plan.promoActive || original <= plan.priceIdr || original <= 0L) return 0
        val derived = ((original - plan.priceIdr).toDouble() / original * 100).roundToInt()
        return plan.promoDiscountPercent.takeIf { it > 0 } ?: derived
    }
    val promoBadgeLabel: String? get() = promoDiscountPercent.takeIf { it > 0 }?.let { "Hemat $it%" }
    /** "/bln", "/3 bln", "/thn" — derived from the duration in whole months. */
    val perLabel: String get() = when (val m = months(plan)) {
        1 -> "/bln"
        12 -> "/thn"
        else -> "/$m bln"
    }
    val savingsLabel: String? get() = if (savingsPercent > 0) "Hemat $savingsPercent%" else null
}

/** Format whole rupiah as "Rp 13.300" (dot thousands separator, id-style). */
fun rupiah(n: Long): String = "Rp " + "%,d".format(n).replace(',', '.')

private fun months(p: BillingPlan): Int =
    (p.durationSeconds / SECONDS_PER_MONTH).roundToInt().coerceAtLeast(1)

private fun perMonth(p: BillingPlan): Double {
    val m = p.durationSeconds / SECONDS_PER_MONTH
    return if (m <= 0.0) p.priceIdr.toDouble() else p.priceIdr / m
}

private fun comparisonPerMonth(p: BillingPlan): Double {
    val m = p.durationSeconds / SECONDS_PER_MONTH
    val price = p.originalPriceIdr?.takeIf { p.promoActive && it > p.priceIdr } ?: p.priceIdr
    return if (m <= 0.0) price.toDouble() else price / m
}

/**
 * Project a price list into renderable [PlanView]s. Baseline for the discount is the 1-month plan
 * (`durationSeconds == 2_592_000`); if none is present we fall back to the highest per-month price so
 * a saving can still be expressed relative to the priciest tier.
 */
fun planViews(plans: List<BillingPlan>): List<PlanView> {
    if (plans.isEmpty()) return emptyList()
    val baselinePerMonth = plans.firstOrNull { months(it) == 1 }?.let { comparisonPerMonth(it) }
        ?: plans.maxOf { comparisonPerMonth(it) }

    val withSavings = plans.map { plan ->
        val effectivePm = perMonth(plan)
        val comparisonPm = comparisonPerMonth(plan)
        val savings = if (baselinePerMonth > 0.0 && comparisonPm < baselinePerMonth)
            ((1.0 - comparisonPm / baselinePerMonth) * 100).roundToInt() else 0
        val perMonthLabel = if (months(plan) > 1) "≈ ${rupiah((effectivePm / 100).roundToLong() * 100)}/bln" else null
        Triple(plan, savings, perMonthLabel)
    }
    val bestSavings = withSavings.maxOf { it.second }
    return withSavings.map { (plan, savings, perMonthLabel) ->
        PlanView(
            plan = plan,
            perMonthLabel = perMonthLabel,
            savingsPercent = savings,
            best = savings > 0 && savings == bestSavings,
        )
    }
}
