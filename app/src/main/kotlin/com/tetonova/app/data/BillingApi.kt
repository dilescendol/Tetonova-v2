package com.tetonova.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One purchasable plan from `GET /api/v1/me/plans`. Price is the panel's single source of truth;
 *  the per-month figure + "Hemat %" badge are derived client-side (see [PlanPricing]). */
@Serializable
data class BillingPlan(
    val code: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("duration_seconds") val durationSeconds: Long = 0L,
    @SerialName("price_idr") val priceIdr: Long = 0L,
)

/** The caller's entitlement from `GET /api/v1/me/subscription`. `status` ∈ none/trial/active/expired;
 *  datetimes are ISO-8601 UTC (`…Z`). */
@Serializable
data class SubscriptionState(
    val status: String = "none",
    @SerialName("plan_code") val planCode: String? = null,
    val source: String? = null,
    @SerialName("current_expiry") val currentExpiry: String? = null,
    @SerialName("trial_used_at") val trialUsedAt: String? = null,
    val now: String? = null,
) {
    /** Entitled = paid-active or trial-active. The single gate Home/Search/premium read. */
    val entitled: Boolean get() = status == "active" || status == "trial"
}

/** One row in the account's payment history (`GET /api/v1/me/payments`). */
@Serializable
data class PaymentRow(
    @SerialName("order_id") val orderId: String = "",
    @SerialName("plan_code") val planCode: String = "",
    @SerialName("plan_display_name") val planDisplayName: String? = null,
    @SerialName("amount_idr") val amountIdr: Long = 0L,
    val status: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable private data class PlansResponse(val plans: List<BillingPlan> = emptyList())
@Serializable private data class PaymentsResponse(val payments: List<PaymentRow> = emptyList())

private data class CheckoutResponse(
    val qrImageUrl: String = "",
    val checkoutUrl: String = "",
    val orderId: String = "",
    val amountIdr: Long = 0L,
    val expiresAt: String = "",
)

@Serializable
private data class TrialClaimResponse(
    val status: String = "",
    @SerialName("current_expiry") val currentExpiry: String? = null,
    @SerialName("trial_used_at") val trialUsedAt: String? = null,
)

@Serializable private data class StatusResponse(val status: String = "")
@Serializable private data class ErrorResponse(val error: String = "")

/**
 * Client for the panel's Phase-2 billing endpoints (`/api/v1/me/{plans,subscription,billing/checkout,
 * trial/claim,payments,payments/{id}}`). Auth is a Firebase ID token as `Authorization: Bearer <idToken>`
 * (from [AuthManager]) — same shape as [ProfileApi]/[LibrarySyncApi]. Offline-first: a blank base,
 * unreachable host, or unexpected status collapses to a typed `Failed`/null so the UI keeps its fallback.
 */
class BillingApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Outcome of a checkout so the UI can show the right message + (on success) open the QRIS screen. */
    sealed interface CheckoutOutcome {
        data class Success(
            /** VioletMediaPay QR image URL (`target`) — rendered directly in the native QRIS screen. */
            val qrImageUrl: String,
            /** Hosted checkout page (`checkout_url`) — fallback if the image can't load. */
            val checkoutUrl: String,
            val orderId: String,
            val amountIdr: Long,
            val expiresAt: String,
        ) : CheckoutOutcome
        data object PlanNotPurchasable : CheckoutOutcome   // 400
        data object GatewayUnavailable : CheckoutOutcome   // 502
        data object BillingUnavailable : CheckoutOutcome   // 503
        data object Failed : CheckoutOutcome
    }

    /** Outcome of a server trial claim. */
    sealed interface TrialOutcome {
        data class Success(val status: String, val currentExpiry: String?) : TrialOutcome
        data class AlreadyUsed(val trialUsedAt: String?) : TrialOutcome   // 409 trial_already_used
        data object DeviceLimited : TrialOutcome                          // 409 trial_device_limited
        data object Contention : TrialOutcome                             // 503
        data object Failed : TrialOutcome
    }

    /** Public plan list for price display before/without sign-in. */
    suspend fun getPublicPlans(): List<BillingPlan>? = withContext(Dispatchers.IO) {
        if (base.isEmpty()) return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/api/v1/plans").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<PlansResponse>(resp.body?.string().orEmpty()).plans
            }
        }.onFailure { Log.w("TnBilling", "getPublicPlans failed: ${it.message}") }.getOrNull()
    }

    /** List purchasable plans (enabled + priced); null on any failure so the UI keeps its fallback. */
    suspend fun getPlans(idToken: String): List<BillingPlan>? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext null
        runCatching {
            val req = get("$base/api/v1/me/plans", idToken)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<PlansResponse>(resp.body?.string().orEmpty()).plans
            }
        }.onFailure { Log.w("TnBilling", "getPlans failed: ${it.message}") }.getOrNull()
    }

    /** Read the caller's current entitlement; null on any failure. */
    suspend fun getSubscription(idToken: String): SubscriptionState? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext null
        runCatching {
            val req = get("$base/api/v1/me/subscription", idToken)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<SubscriptionState>(resp.body?.string().orEmpty())
            }
        }.onFailure { Log.w("TnBilling", "getSubscription failed: ${it.message}") }.getOrNull()
    }

    /** Start a QRIS checkout for [planCode]; distinguishes common gateway errors for the UI. */
    suspend fun startCheckout(idToken: String, planCode: String): CheckoutOutcome =
        withContext(Dispatchers.IO) {
            if (base.isEmpty() || idToken.isBlank()) return@withContext CheckoutOutcome.Failed
            runCatching {
                val body = JSONObject().put("plan_code", planCode).toString()
                val req = Request.Builder()
                    .url("$base/api/v1/me/billing/checkout")
                    .header("Authorization", "Bearer $idToken")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val err = errorOf(text)
                    when {
                        resp.code == 400 || err == "plan_not_purchasable" -> CheckoutOutcome.PlanNotPurchasable
                        resp.code == 502 || err == "payment_gateway_unavailable" -> CheckoutOutcome.GatewayUnavailable
                        resp.code == 503 || err == "billing_unavailable" -> CheckoutOutcome.BillingUnavailable
                        resp.isSuccessful -> {
                            val r = parseCheckoutResponse(text) ?: return@use CheckoutOutcome.Failed
                            CheckoutOutcome.Success(
                                qrImageUrl = r.qrImageUrl,
                                checkoutUrl = r.checkoutUrl,
                                orderId = r.orderId,
                                amountIdr = r.amountIdr,
                                expiresAt = r.expiresAt,
                            )
                        }
                        else -> {
                            Log.w("TnBilling", "startCheckout HTTP ${resp.code}: ${err.ifBlank { text.take(160) }}")
                            CheckoutOutcome.Failed
                        }
                    }
                }
            }.onFailure { Log.w("TnBilling", "startCheckout failed: ${it.message}") }
                .getOrDefault(CheckoutOutcome.Failed)
        }

    /** Poll a single order's status (pending/completed/expired/cancelled); null on failure. */
    suspend fun getPaymentStatus(idToken: String, orderId: String): String? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank() || orderId.isBlank()) return@withContext null
        runCatching {
            val req = get("$base/api/v1/me/payments/${java.net.URLEncoder.encode(orderId, "UTF-8")}", idToken)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                normalizePaymentStatus(json.decodeFromString<StatusResponse>(resp.body?.string().orEmpty()).status)
            }
        }.onFailure { Log.w("TnBilling", "getPaymentStatus failed: ${it.message}") }.getOrNull()
    }

    /** Cancel a pending local QRIS order. Returns the resulting status; null on failure. */
    suspend fun cancelPayment(idToken: String, orderId: String): String? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank() || orderId.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url("$base/api/v1/me/payments/${java.net.URLEncoder.encode(orderId, "UTF-8")}/cancel")
                .header("Authorization", "Bearer $idToken")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                normalizePaymentStatus(json.decodeFromString<StatusResponse>(resp.body?.string().orEmpty()).status)
            }
        }.onFailure { Log.w("TnBilling", "cancelPayment failed: ${it.message}") }.getOrNull()
    }

    /** Claim the one-shot trial server-side. [installId] is the device id used for the anti-farming gate. */
    suspend fun claimTrial(idToken: String, installId: String): TrialOutcome = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext TrialOutcome.Failed
        runCatching {
            val body = JSONObject().put("install_id", installId).toString()
            val req = Request.Builder()
                .url("$base/api/v1/me/trial/claim")
                .header("Authorization", "Bearer $idToken")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> {
                        val r = json.decodeFromString<TrialClaimResponse>(text)
                        TrialOutcome.Success(r.status, r.currentExpiry)
                    }
                    resp.code == 409 -> {
                        val err = runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull()
                        if (err == "trial_device_limited") TrialOutcome.DeviceLimited
                        else TrialOutcome.AlreadyUsed(
                            runCatching { json.decodeFromString<TrialClaimResponse>(text).trialUsedAt }.getOrNull()
                        )
                    }
                    resp.code == 503 -> TrialOutcome.Contention
                    else -> TrialOutcome.Failed
                }
            }
        }.onFailure { Log.w("TnBilling", "claimTrial failed: ${it.message}") }
            .getOrDefault(TrialOutcome.Failed)
    }

    /** Account payment history for the "Kelola langganan" sheet; null on failure. */
    suspend fun getPayments(idToken: String): List<PaymentRow>? = withContext(Dispatchers.IO) {
        if (base.isEmpty() || idToken.isBlank()) return@withContext null
        runCatching {
            val req = get("$base/api/v1/me/payments", idToken)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString<PaymentsResponse>(resp.body?.string().orEmpty()).payments
            }
        }.onFailure { Log.w("TnBilling", "getPayments failed: ${it.message}") }.getOrNull()
    }

    private fun get(url: String, idToken: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $idToken").get().build()

    private fun errorOf(text: String): String =
        runCatching { json.decodeFromString<ErrorResponse>(text).error }.getOrNull().orEmpty()

    private fun parseCheckoutResponse(text: String): CheckoutResponse? = runCatching {
        val root = JSONObject(text)
        val data = root.optJSONObject("data")
        fun firstString(vararg keys: String): String {
            for (obj in listOf(root, data).filterNotNull()) {
                for (key in keys) {
                    val value = obj.optString(key, "").trim()
                    if (value.isNotBlank()) return value
                }
            }
            return ""
        }
        fun firstLong(vararg keys: String): Long {
            for (obj in listOf(root, data).filterNotNull()) {
                for (key in keys) {
                    if (!obj.has(key)) continue
                    val value = obj.opt(key)
                    val parsed = when (value) {
                        is Number -> value.toLong()
                        is String -> value.filter { it.isDigit() }.toLongOrNull()
                        else -> null
                    }
                    if (parsed != null && parsed > 0L) return parsed
                }
            }
            return 0L
        }

        val qr = firstString("qr_image_url", "qrImageUrl", "target", "qr_url", "qrUrl")
        val checkout = firstString("checkout_url", "checkoutUrl", "payment_url", "paymentUrl", "pay_url", "payUrl", "url")
        val order = firstString("order_id", "orderId", "ref_kode", "ref", "reference", "id_reference", "ref_id")
        val amount = firstLong("amount_idr", "amountIdr", "nominal", "amount", "total")
        val expires = firstString("expires_at", "expiresAt", "expired_time", "expiredTime", "expiry")
        if (order.isBlank() || amount <= 0L || (qr.isBlank() && checkout.isBlank())) null
        else CheckoutResponse(qr, checkout, order, amount, expires)
    }.getOrNull()

    private fun normalizePaymentStatus(raw: String): String = when (raw.trim().lowercase()) {
        "completed", "complete", "paid", "success", "settlement", "capture" -> "completed"
        "expired", "expire", "kadaluarsa", "kedaluwarsa" -> "expired"
        "cancelled", "canceled", "cancel", "dibatalkan" -> "cancelled"
        else -> raw.trim().lowercase().ifBlank { "pending" }
    }
}
