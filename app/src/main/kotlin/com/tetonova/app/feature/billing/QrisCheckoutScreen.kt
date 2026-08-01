package com.tetonova.app.feature.billing

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tetonova.app.data.BillingApi
import com.tetonova.app.data.TnData
import com.tetonova.app.data.rupiah
import com.tetonova.app.ui.QrisArg
import com.tetonova.app.ui.TnGhostButton
import com.tetonova.app.ui.TnPrimaryButton
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private enum class Phase { PENDING, SUCCESS, EXPIRED, CANCELLED }

/** Poll cadence for the order's status while the QR is pending. */
private const val POLL_MS = 3_500L

/**
 * Native QRIS checkout. Loads VioletMediaPay's QR image in-app with our own countdown to
 * [QrisArg.expiresAt] and polls `GET /api/v1/me/payments/{order_id}` until the order goes terminal. On
 * `completed` it refreshes the entitlement and bounces back to the Langganan screen; on expiry it
 * offers a "Buat ulang".
 */
@Composable
fun QrisCheckoutScreen(initial: QrisArg, onClose: () -> Unit) {
    val c = TnTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var arg by remember { mutableStateOf(initial) }
    var phase by remember { mutableStateOf(Phase.PENDING) }
    var remaining by remember { mutableStateOf(0L) }
    var reissuing by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var qrFailed by remember(arg.qrImageUrl) { mutableStateOf(false) }

    val expiryMs = remember(arg.expiresAt) {
        runCatching { Instant.parse(arg.expiresAt).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis() + 30 * 60_000L)
    }

    fun openCheckoutPage() {
        val raw = arg.checkoutUrl.trim()
        if (raw.isBlank()) return
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(context, "Tidak bisa membuka halaman pembayaran", Toast.LENGTH_SHORT).show() }
    }

    suspend fun applyStatus(status: String?) {
        when (status) {
            "completed" -> { TnData.refreshSubscription(); phase = Phase.SUCCESS }
            "expired" -> phase = Phase.EXPIRED
            "cancelled" -> phase = Phase.CANCELLED
            else -> {}
        }
    }

    suspend fun refreshStatusNow() {
        applyStatus(TnData.paymentStatus(arg.orderId))
    }

    // Countdown to expiry.
    LaunchedEffect(arg.orderId, phase) {
        if (phase != Phase.PENDING) return@LaunchedEffect
        while (true) {
            remaining = (expiryMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining <= 0L) { phase = Phase.EXPIRED; break }
            delay(1000)
        }
    }

    // Poll order status until terminal.
    LaunchedEffect(arg.orderId, phase) {
        if (phase != Phase.PENDING) return@LaunchedEffect
        while (true) {
            refreshStatusNow()
            if (phase != Phase.PENDING) break
            delay(POLL_MS)
        }
    }

    // After a successful payment, hold the confirmation briefly then return to Langganan.
    LaunchedEffect(phase) {
        if (phase == Phase.SUCCESS) { delay(1800); onClose() }
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // back
        Row(
            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
                    .clickable { onClose() }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Tutup", color = c.ink2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Bayar QRIS", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        Text("TetoNova Premium - ${arg.planName}", color = c.muted, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        Text(rupiah(arg.totalIdr), color = c.rose, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
        Spacer(Modifier.height(12.dp))
        QrisPriceBreakdown(arg)
        Spacer(Modifier.height(20.dp))

        when (phase) {
            Phase.PENDING -> {
                // QR card
                Box(
                    Modifier.clip(RoundedCornerShape(TnRadii.lg)).background(Color.White)
                        .border(1.dp, c.line, RoundedCornerShape(TnRadii.lg)).padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (arg.qrImageUrl.isNotBlank() && !qrFailed) {
                        AsyncImage(
                            model = arg.qrImageUrl,
                            contentDescription = "QRIS",
                            modifier = Modifier.size(240.dp),
                            contentScale = ContentScale.Fit,
                            onSuccess = { qrFailed = false },
                            onError = { qrFailed = true },
                        )
                    } else {
                        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (arg.checkoutUrl.isNotBlank()) "QR belum termuat" else "QR tidak tersedia",
                                color = Color(0xFF777777),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                // countdown pill
                Row(
                    Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.roseSoft)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TnIcon("clock", size = 14.dp, tint = c.roseDeep)
                    Text("Bayar dalam ${fmt(remaining)}", color = c.roseDeep, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
                if (arg.checkoutUrl.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    TnGhostButton(
                        text = "Buka halaman pembayaran",
                        icon = "globe",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openCheckoutPage() },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TnGhostButton(
                        text = if (checking) "Mengecek..." else "Refresh status",
                        icon = "refresh",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (checking) return@TnGhostButton
                            checking = true
                            scope.launch {
                                try {
                                    refreshStatusNow()
                                } finally {
                                    checking = false
                                }
                            }
                        },
                    )
                    TnGhostButton(
                        text = if (cancelling) "Membatalkan..." else "Batalkan",
                        icon = "trash",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (cancelling) return@TnGhostButton
                            cancelling = true
                            scope.launch {
                                try {
                                    applyStatus(TnData.cancelPayment(arg.orderId))
                                    if (phase == Phase.PENDING) {
                                        Toast.makeText(context, "Gagal membatalkan pembayaran", Toast.LENGTH_SHORT).show()
                                    }
                                } finally {
                                    cancelling = false
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Scan QR ini dengan aplikasi e-wallet / m-banking apa pun (GoPay, OVO, DANA, dst). " +
                        "Halaman ini otomatis terbarui setelah pembayaran berhasil.",
                    color = c.muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Phase.SUCCESS -> StatusBlock(
                icon = "check", tint = Color(0xFF21A463),
                title = "Pembayaran berhasil", sub = "Premium kamu sudah aktif. Mengarahkan kembali...",
            )
            Phase.CANCELLED -> {
                StatusBlock(
                    icon = "trash", tint = c.muted,
                    title = "Pembayaran dibatalkan",
                    sub = "Invoice ini sudah ditutup di TetoNova. Buat QRIS baru kalau mau lanjut.",
                )
                Spacer(Modifier.height(18.dp))
                TnGhostButton(
                    text = "Tutup",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onClose,
                )
            }
            Phase.EXPIRED -> {
                StatusBlock(
                    icon = "clock", tint = c.muted,
                    title = "QRIS kedaluwarsa", sub = "Waktu pembayaran habis. Buat QRIS baru untuk melanjutkan.",
                )
                Spacer(Modifier.height(18.dp))
                TnPrimaryButton(
                    text = if (reissuing) "Membuat..." else "Buat ulang",
                    icon = "refresh",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (reissuing) return@TnPrimaryButton
                    reissuing = true
                    scope.launch {
                        when (val out = TnData.startCheckout(arg.planCode)) {
                            is BillingApi.CheckoutOutcome.Success -> {
                                arg = arg.copy(
                                    orderId = out.orderId, qrImageUrl = out.qrImageUrl,
                                    checkoutUrl = out.checkoutUrl, amountIdr = out.amountIdr,
                                    adminFeeIdr = out.adminFeeIdr, taxIdr = out.taxIdr, totalIdr = out.totalIdr,
                                    expiresAt = out.expiresAt,
                                )
                                phase = Phase.PENDING
                            }
                            else -> {} // stay on expired; user can retry again
                        }
                        reissuing = false
                    }
                }
            }
        }
    }
}

@Composable
private fun QrisPriceBreakdown(arg: QrisArg) {
    val c = TnTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QrisPriceLine("Harga paket", rupiah(arg.amountIdr))
        QrisPriceLine("Biaya admin QRIS", rupiah(arg.adminFeeIdr))
        QrisPriceLine("Pajak tambahan", if (arg.taxIdr == 0L) "Tidak dikenakan" else rupiah(arg.taxIdr))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        QrisPriceLine("Total bayar", rupiah(arg.totalIdr), strong = true)
    }
}

@Composable
private fun QrisPriceLine(label: String, value: String, strong: Boolean = false) {
    val c = TnTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (strong) c.ink else c.muted, fontSize = 12.sp, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.Normal)
        Text(value, color = c.ink, fontSize = 12.sp, fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.SemiBold)
    }
}

@Composable
private fun StatusBlock(icon: String, tint: Color, title: String, sub: String) {
    val c = TnTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) { TnIcon(icon, size = 34.dp, tint = tint, filled = true) }
        Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Text(sub, color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

/** mm:ss for the countdown. */
private fun fmt(ms: Long): String { val t = ms / 1000; return "%02d:%02d".format(t / 60, t % 60) }
