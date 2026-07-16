package com.tetonova.app.feature.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tetonova.app.data.AuthManager
import com.tetonova.app.data.AutoBackup
import com.tetonova.app.data.BackupCodec
import com.tetonova.app.data.BillingApi
import com.tetonova.app.data.FcmRegistration
import com.tetonova.app.data.FcmTokenHolder
import com.tetonova.app.data.LibrarySync
import com.tetonova.app.data.PaymentRow
import com.tetonova.app.data.PlanView
import com.tetonova.app.data.TnData
import com.tetonova.app.data.TrialPhase
import com.tetonova.app.data.TrialStore
import com.tetonova.app.data.planViews
import com.tetonova.app.data.rememberGoogleSignIn
import com.tetonova.app.data.rupiah
import com.tetonova.app.ui.AppState
import com.tetonova.app.ui.QrisArg
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.TnGhostButton
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.RoseGradientColors
import com.tetonova.core.designsystem.theme.TnAccents
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.tnGradient
import kotlinx.coroutines.delay

private val PERKS = listOf("zap" to "Tanpa iklan", "refresh" to "Auto-update", "shield" to "Server prioritas")
private val ID_LOCALE = java.util.Locale("in", "ID")
private fun parseIsoMs(iso: String?): Long? =
    iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
private fun fmtDate(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", ID_LOCALE))
/** "N+ source" when known, else a generic word so the count is never a fabricated number. */
private fun sourceCountLabel(n: Int): String = if (n > 0) "$n+ source" else "banyak source"

@Composable
fun SettingsScreen(state: AppState, onBack: () -> Unit) {
    val c = TnTheme.colors
    PageScroll(topInset = true) {
        // header
        Row(
            Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
                .clickable { onBack() }.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TnIcon("chevR", size = 16.dp, tint = c.ink2, modifier = Modifier.rot180())
            Text("Kembali", color = c.ink2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("TetoNova · v0.1.0", color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text("Pengaturan", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)

        SectionCard("Akun & Sinkron", "Kelola akun dan koneksi cloud", "user") { AccountHero(state) }
        SectionCard("Langganan & API", "Akses ${sourceCountLabel(TnData.premiumSourceCount())} lewat API provider", "sparkle") { SubscriptionPanel(state) }
        SectionCard("Tampilan", "Tema, warna aksen, dan kepadatan UI", "palette") { AppearanceSection(state) }
        SectionCard("Pemutaran & Data", "Kualitas stream dan perilaku player", "play") { PlaybackSection(state) }
        SectionCard("Penyimpanan & Unduhan", "Kualitas unduhan & file offline", "download") { DownloadsSettingsSection(state) }
        SectionCard("Konten", "Filter & preferensi bahasa", "eye") { ContentSection(state) }
        SectionCard("Notifikasi", "Apa yang ingin kamu dapat", "bell") { NotifSection(state) }
        SectionCard("Cadangan & Sinkron", "Backup library lokal kamu", "cloud") { BackupSection(state) }
        SectionCard("Tentang", "Versi & bantuan", "info") { AboutSection(state) }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionCard(title: String, desc: String, icon: String, content: @Composable () -> Unit) {
    val c = TnTheme.colors
    Column(Modifier.padding(top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(TnRadii.sm)).background(c.roseTint), contentAlignment = Alignment.Center) {
                TnIcon(icon, size = 17.dp, tint = c.rose)
            }
            Column {
                Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        TnCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(8.dp)) { content() } }
    }
}

@Composable
private fun SettingRow(icon: String, title: String, desc: String, controlBelow: Boolean = false, control: @Composable () -> Unit) {
    val c = TnTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface2), contentAlignment = Alignment.Center) {
                TnIcon(icon, size = 18.dp, tint = c.ink2)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (!controlBelow) control()
        }
        // Wide controls (e.g. the 3-segment quality selector) wrap to their own
        // full-width line below the label instead of starving it — matches design handoff
        // `.set-row:has(.seg-ctrl) .sr-ctrl{flex:1 1 100%;margin-left:48px}`.
        if (controlBelow) Box(Modifier.padding(start = 46.dp, top = 11.dp)) { control() }
    }
}

@Composable
private fun TnToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = TnTheme.colors
    val knob by animateDpAsState(if (on) 22.dp else 2.dp, label = "knob")
    Box(
        Modifier.width(46.dp).height(26.dp).clip(RoundedCornerShape(TnRadii.pill)).background(if (on) c.rose else c.line2).clickable { onChange(!on) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.padding(start = knob).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun <T> SegSelect(value: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    val c = TnTheme.colors
    Row(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(TnRadii.pill)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (id, label) ->
            val on = id == value
            Box(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(if (on) c.rose else Color.Transparent).clickable { onSelect(id) }.padding(horizontal = 13.dp, vertical = 7.dp),
            ) { Text(label, color = if (on) Color.White else c.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun AccountHero(state: AppState) {
    val c = TnTheme.colors
    val user = AuthManager.user
    val signIn = rememberGoogleSignIn()
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (state.signedIn) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val photo = user?.photoUrl?.toString()
                if (photo != null) {
                    AsyncImage(model = photo, contentDescription = null, modifier = Modifier.size(48.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(48.dp).clip(CircleShape).tnGradient(com.tetonova.core.designsystem.theme.gradColors(0)), contentAlignment = Alignment.Center) {
                        Text((user?.displayName ?: user?.email ?: "?").take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(user?.displayName ?: "Akun Google", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                    Text(user?.email ?: "", color = c.muted, fontSize = 12.sp, maxLines = 1)
                    Text("Sinkron aktif", color = Color(0xFF1FA463), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnGhostButton(text = "Keluar", icon = "logout", onClick = { state.signOut() })
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Masuk untuk sinkron lintas perangkat", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text("Library, history, dan lanjut tonton kamu akan otomatis tersinkron. Tetap gratis, tanpa iklan.", color = c.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.tetonova.app.ui.TnPrimaryButton(text = "Masuk dengan Google", icon = "login", onClick = { signIn() })
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(state: AppState) {
    val c = TnTheme.colors
    SettingRow("moon", "Tema", "Pilih mode terang, gelap, atau ikuti sistem.") {
        SegSelect(
            value = if (state.darkTheme) "dark" else "light",
            options = listOf("light" to "Terang", "dark" to "Gelap"),
            onSelect = { state.darkTheme = it == "dark" },
        )
    }
    Divider()
    SettingRow("palette", "Warna aksen", "Sesuaikan warna utama aplikasi.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TnAccents.forEach { a ->
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(a.color)
                        .border(if (state.accentId == a.id) 3.dp else 0.dp, c.ink, CircleShape)
                        .clickable { state.accentId = a.id },
                )
            }
        }
    }
    Divider()
    SettingRow("sparkle", "Lite Mode", "Kurangi animasi & ornamen, UI lebih ringan.") { TnToggle(state.lite) { state.lite = it } }
}

@Composable
private fun PlaybackSection(state: AppState) {
    SettingRow("tv", "Kualitas default", "Resolusi awal saat memulai episode.", controlBelow = true) {
        SegSelect(state.quality, listOf("auto" to "Auto", "1080" to "1080p", "720" to "720p"), onSelect = { state.quality = it })
    }
    Divider()
    SettingRow("layers", "Data Saver", "Stream lebih ringan & refresh lebih jarang.") { TnToggle(state.dataSaver) { state.dataSaver = it } }
    Divider()
    SettingRow("play", "Auto next episode", "Lanjut otomatis ke episode berikutnya.") { TnToggle(state.autoNext) { state.autoNext = it } }
    Divider()
    SettingRow("zap", "Skip opening", "Lewati intro & ending otomatis untuk anime (donghua tetap manual).") { TnToggle(state.skipOp) { state.skipOp = it } }
}

@Composable
private fun ContentSection(state: AppState) {
    // Persist the flag, then bump ext state so the Extensions catalog + Home/Search re-filter mature
    // sources instantly (matureVisible() reads the same persisted key).
    SettingRow("eyeOff", "Konten Dewasa (18+)", "Tampilkan atau sembunyikan judul bertanda 18+.") { TnToggle(state.mature) { state.mature = it; com.tetonova.app.data.TnData.onMatureChanged() } }
    Divider()
    SettingRow("flag", "Lapor konten bermasalah", "Cara cepat lapor judul / source yang error.") { TnGhostButton(text = "Lapor sekarang", onClick = { state.openReport() }) }
}

@Composable
private fun NotifSection(state: AppState) {
    val scope = rememberCoroutineScope()
    SettingRow("bookmark", "Follow notifications", "Saat judul yang kamu ikuti rilis episode baru.") { TnToggle(state.notifFollow) { state.notifFollow = it } }
    Divider()
    SettingRow("comment", "Balasan forum", "Saat seseorang membalas thread atau komentar kamu.") { TnToggle(state.notifForum) { state.notifForum = it } }
    Divider()
    SettingRow("shield", "Push lokal saja", "Gunakan worker lokal, tanpa push stack eksternal.") {
        TnToggle(state.pushLocal) { v ->
            state.pushLocal = v
            // Flipping this promptly (un)registers the current FCM token instead of waiting for the
            // next token refresh. No-op without a token (e.g. Firebase not configured) — local-first.
            FcmTokenHolder.token?.let { token ->
                scope.launch { if (v) FcmRegistration.unregisterToken(token) else FcmRegistration.registerToken(token) }
            }
        }
    }
}

@Composable
private fun DownloadsSettingsSection(state: AppState) {
    val context = LocalContext.current
    var used by remember { mutableStateOf(com.tetonova.app.data.download.DownloadCenter.storageBytes()) }
    SettingRow("layers", "Kualitas unduhan", "Batas resolusi yang dipakai untuk semua unduhan.", controlBelow = true) {
        SegSelect(state.downloadQuality, listOf("auto" to "Auto", "1080" to "1080p", "720" to "720p", "480" to "480p")) { state.downloadQuality = it }
    }
    Divider()
    SettingRow("globe", "Hanya via Wi-Fi", "Tunda unduhan saat memakai data seluler.") {
        TnToggle(state.downloadWifiOnly) { v -> state.downloadWifiOnly = v; com.tetonova.app.data.download.DownloadCenter.applyWifiOnly(v) }
    }
    Divider()
    SettingRow("trash", "Penyimpanan terpakai", "${fmtSize(used)} tersimpan offline di perangkat.") {
        TnGhostButton(text = "Hapus semua", icon = "trash", onClick = {
            com.tetonova.app.data.download.DownloadCenter.removeAll()
            used = 0L
            Toast.makeText(context, "Semua unduhan dihapus", Toast.LENGTH_SHORT).show()
        })
    }
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}

@Composable
private fun BackupSection(state: AppState) {
    val context = LocalContext.current
    val signIn = rememberGoogleSignIn()
    var folderName by remember { mutableStateOf(AutoBackup.folderName(context)) }

    // SAF: write the .tnova backup into a user-chosen location in their file manager.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(BackupCodec.export().toByteArray()) }
        }.isSuccess
        Toast.makeText(context, if (ok) "Backup tersimpan" else "Gagal menyimpan backup", Toast.LENGTH_SHORT).show()
    }
    // SAF: read a previously exported .tnova and merge it back in.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val result = text?.let { BackupCodec.import(it, state) }
        val msg = result?.fold({ "Berhasil impor $it item" }, { "File backup tidak valid" }) ?: "Gagal membaca file"
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
    // SAF: pick the folder the periodic local auto-backup writes into.
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        AutoBackup.setFolder(context, uri)
        folderName = AutoBackup.folderName(context)
        AutoBackup.maybeRun(context)
    }

    SettingRow("cloud", "Cadangan otomatis", if (state.signedIn) "Backup tiap 24 jam ke cloud akun & file lokal." else "Masuk dulu untuk aktifkan backup cloud.") {
        TnToggle(state.autoBackup && state.signedIn) { v ->
            if (!state.signedIn) { signIn(); return@TnToggle }
            state.autoBackup = v
            if (v) { LibrarySync.onSignedIn(); AutoBackup.maybeRun(context) }
        }
    }
    if (state.autoBackup && state.signedIn) {
        Divider()
        SettingRow("file", "Folder cadangan lokal", folderName?.let { "Tersimpan ke: $it" } ?: "Pilih folder untuk backup file otomatis.") {
            TnGhostButton(text = if (folderName != null) "Ubah" else "Pilih folder", icon = "file", onClick = { folderLauncher.launch(null) })
        }
    }
    Divider()
    SettingRow("upload", "Ekspor data", "Simpan library, history, dan settings sebagai file .tnova.") {
        TnGhostButton(text = "Ekspor", icon = "download", onClick = { exportLauncher.launch("tetonova-backup-${backupDate()}.tnova") })
    }
    Divider()
    SettingRow("file", "Impor dari file", "Pulihkan dari file backup .tnova sebelumnya.") {
        TnGhostButton(text = "Pilih file", icon = "upload", onClick = { importLauncher.launch(arrayOf("*/*")) })
    }
}

private fun backupDate(): String = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())

@Composable
private fun AboutSection(state: AppState) {
    SettingRow("info", "Versi aplikasi", "TetoNova 0.1.0 · build 240608") { TnGhostButton(text = "Catatan rilis", onClick = { state.openReleaseNotes() }) }
    Divider()
    SettingRow("help", "Pusat bantuan", "FAQ, panduan source, dan kontak.") { TnGhostButton(text = "Buka", icon = "chevR", onClick = { state.openHelp() }) }
}

/** Format sisa waktu trial sebagai mm:ss / hh:mm:ss. */
private fun fmtTrial(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun fmtTrialDuration(ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(1L)
    val days = minutes / 1440L
    val hours = minutes / 60L
    return when {
        minutes % 1440L == 0L -> "$days hari"
        minutes >= 60L && minutes % 60L == 0L -> "$hours jam"
        minutes >= 60L -> "$hours jam ${minutes % 60L} menit"
        else -> "$minutes menit"
    }
}

@Composable
private fun SubscriptionPanel(state: AppState) {
    val c = TnTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val signIn = rememberGoogleSignIn()
    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // Live state. `panelVersion` read so the source count recomposes after a panel fetch.
    @Suppress("UNUSED_VARIABLE") val pv = TnData.panelVersion
    val sub = TnData.subscription
    val views = planViews(TnData.plans)
    val premiumCount = TnData.premiumSourceCount()
    val signedIn = AuthManager.signedIn
    val entitled = sub?.entitled == true
    val trialDurationMs = TnData.trialDurationMs()

    // "Pratinjau status" stays a manual design preview, defaulting to the real status (re-keyed on it).
    var preview by remember(sub?.status) { mutableStateOf(if (entitled) "active" else "free") }
    val active = preview == "active"

    // Selected plan: pre-select the user's current plan when premium (so "Perpanjang" buys the same),
    // else the best-value plan; never null while views exist.
    var planCode by remember(sub?.planCode, TnData.plans) { mutableStateOf(sub?.planCode) }
    val sel = views.firstOrNull { it.plan.code == planCode } ?: views.firstOrNull { it.best } ?: views.firstOrNull()

    // Trial phase: server-enforced when signed in (status=="trial" / 409 → used), else on-device TrialStore.
    val serverTrial = sub?.status == "trial"
    val serverTrialUsed = sub?.trialUsedAt != null
    var localTrialUsed by remember { mutableStateOf(false) }
    var phase by remember(sub?.status, sub?.trialUsedAt, signedIn, localTrialUsed) {
        mutableStateOf(
            when {
                serverTrial -> TrialPhase.ACTIVE
                signedIn -> if (serverTrialUsed || localTrialUsed) TrialPhase.EXPIRED else TrialPhase.AVAILABLE
                else -> TrialStore.phase()
            }
        )
    }
    var remaining by remember { mutableStateOf(0L) }
    LaunchedEffect(phase, sub?.currentExpiry) {
        val serverExpiry = parseIsoMs(sub?.currentExpiry)
        while (phase == TrialPhase.ACTIVE) {
            remaining = if (serverTrial && serverExpiry != null)
                (serverExpiry - System.currentTimeMillis()).coerceAtLeast(0L)
            else TrialStore.remainingMs()
            if (remaining <= 0L) { phase = TrialPhase.EXPIRED; break }
            delay(1000)
        }
    }

    var showManage by remember { mutableStateOf(false) }

    // Pull fresh entitlement + plans when the Langganan panel opens (best-effort, no-op signed-out).
    LaunchedEffect(Unit) { runCatching { TnData.refreshSubscription() } }

    fun launchCheckout(view: PlanView?) {
        if (view == null) return
        if (!signedIn) { signIn(); return }
        scope.launch {
            when (val out = TnData.startCheckout(view.plan.code)) {
                is BillingApi.CheckoutOutcome.Success -> state.openQris(
                    QrisArg(
                        orderId = out.orderId, qrImageUrl = out.qrImageUrl, checkoutUrl = out.checkoutUrl,
                        amountIdr = out.amountIdr, expiresAt = out.expiresAt,
                        planCode = view.plan.code, planName = view.plan.displayName,
                    )
                )
                BillingApi.CheckoutOutcome.PlanNotPurchasable -> toast("Paket belum bisa diproses")
                BillingApi.CheckoutOutcome.GatewayUnavailable -> toast("Gerbang pembayaran sedang gangguan, coba lagi")
                BillingApi.CheckoutOutcome.BillingUnavailable -> toast("Pembayaran belum tersedia")
                BillingApi.CheckoutOutcome.Failed -> toast("Gagal memulai pembayaran")
            }
        }
    }

    fun startTrial() {
        if (trialDurationMs <= 0L) {
            toast("Trial sedang nonaktif")
            return
        }
        if (!signedIn) {
            // Offline/signed-out soft fallback: on-device TrialStore. Server enforcement needs an account.
            if (TrialStore.claim(trialDurationMs)) { phase = TrialPhase.ACTIVE; remaining = TrialStore.remainingMs() } else signIn()
            return
        }
        scope.launch {
            when (TnData.claimTrialServer()) {
                is BillingApi.TrialOutcome.Success -> {} // sub.status flips to "trial" → phase recomputes
                is BillingApi.TrialOutcome.AlreadyUsed -> { localTrialUsed = true; toast("Trial sudah dipakai") }
                BillingApi.TrialOutcome.DeviceLimited -> { localTrialUsed = true; toast("Trial sudah dipakai di perangkat ini") }
                BillingApi.TrialOutcome.Contention -> toast("Sedang sibuk, coba lagi sebentar")
                BillingApi.TrialOutcome.Failed -> toast("Gagal memulai trial")
            }
        }
    }

    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // status preview (manual design override; defaults to the real entitlement)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PRATINJAU STATUS", color = c.muted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            SegSelect(preview, listOf("active" to "Premium aktif", "free" to "Gratis"), onSelect = { preview = it })
        }
        // status hero
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.lg))
                .then(if (active) Modifier.tnGradient(listOf(Color(0xFFD7003B), Color(0xFF7A0024), Color(0xFF2A0008))) else Modifier.background(c.surface3))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(if (active) Color.White.copy(0.2f) else c.roseSoft).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TnIcon("sparkle", size = 13.dp, tint = if (active) Color.White else c.roseDeep, filled = true)
                        Text(if (active) "PREMIUM" else if (phase == TrialPhase.ACTIVE) "TRIAL" else "GRATIS", color = if (active) Color.White else c.roseDeep, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (active) {
                        Row(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color(0x3321A463)).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF53E08A)))
                            Text(if (serverTrial) "Trial" else "Aktif", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            TnIcon("lock", size = 12.dp, tint = c.muted)
                            Text("Belum berlangganan", color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(if (active) "TetoNova Premium" else "Upgrade ke Premium", color = if (active) Color.White else c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                if (active) {
                    val expiryMs = parseIsoMs(sub?.currentExpiry)
                    val daysLeft = expiryMs?.let { ((it - System.currentTimeMillis()) / 86_400_000L).toInt().coerceAtLeast(0) }
                    val totalDays = TnData.plans.firstOrNull { it.code == sub?.planCode }?.durationSeconds?.let { (it / 86_400L).toInt() }
                    val frac = if (totalDays != null && totalDays > 0 && daysLeft != null) (daysLeft.toFloat() / totalDays).coerceIn(0f, 1f) else 0.6f
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TnIcon("clock", size = 14.dp, tint = Color.White.copy(0.9f))
                        Text(expiryMs?.let { "Aktif sampai ${fmtDate(it)}" } ?: "Langganan aktif", color = Color.White.copy(0.9f), fontSize = 12.sp)
                        if (daysLeft != null) Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("$daysLeft hari lagi", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.25f))) {
                        Box(Modifier.fillMaxWidth(frac).height(7.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White))
                    }
                } else {
                    Text("Sambungkan ke API provider untuk membuka ${sourceCountLabel(premiumCount)} streaming — tanpa iklan, update otomatis, dan server prioritas.", color = c.ink2, fontSize = 12.sp)
                }
                // API provider sub-card: premium tersambung; free -> panel-configured trial.
                if (active) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(Color.White.copy(0.12f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(TnRadii.sm)).background(Color.White.copy(0.18f)), contentAlignment = Alignment.Center) {
                            TnIcon("check", size = 16.dp, tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("API provider tersambung", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(if (premiumCount > 0) "$premiumCount source premium aktif" else "Source premium aktif", color = Color.White.copy(0.85f), fontSize = 11.sp)
                        }
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(0.14f))
                                .clickable { scope.launch { TnData.refreshSubscription() } },
                            contentAlignment = Alignment.Center,
                        ) { TnIcon("refresh", size = 14.dp, tint = Color.White) }
                    }
                } else {
                    val trialEnabled = trialDurationMs > 0L
                    val effectivePhase = if (!trialEnabled && phase == TrialPhase.AVAILABLE) TrialPhase.EXPIRED else phase
                    val (tIcon, tTitle, _) = when (effectivePhase) {
                        TrialPhase.AVAILABLE -> Triple("sparkle", "Coba Premium ${fmtTrialDuration(trialDurationMs)} gratis", "Sekali pakai · tanpa kartu")
                        TrialPhase.ACTIVE    -> Triple("clock", "Trial Premium aktif", "Sisa ${fmtTrial(remaining)}")
                        TrialPhase.EXPIRED   -> if (trialEnabled)
                            Triple("lock", "Trial sudah dipakai", "Berlangganan untuk lanjut akses")
                        else
                            Triple("lock", "Trial tidak tersedia", "Berlangganan untuk membuka Premium")
                    }
                    val tTint = if (effectivePhase == TrialPhase.EXPIRED) c.muted else c.rose
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
                            .then(if (effectivePhase == TrialPhase.AVAILABLE) Modifier.clickable { startTrial() } else Modifier)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface3), contentAlignment = Alignment.Center) {
                            TnIcon(tIcon, size = 16.dp, tint = tTint, filled = effectivePhase == TrialPhase.AVAILABLE)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(tTitle, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        when (effectivePhase) {
                            TrialPhase.AVAILABLE -> Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.rose).padding(horizontal = 12.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text("Mulai", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            TrialPhase.ACTIVE -> Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.roseSoft).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                Text(fmtTrial(remaining), color = c.roseDeep, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            TrialPhase.EXPIRED -> {}
                        }
                    }
                }
                if (active) {
                    // Perpanjang = shortcut to the same checkout (stacking); Kelola = payment history sheet.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White).clickable { launchCheckout(sel) }.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                TnIcon("sparkle", size = 15.dp, tint = c.rose, filled = true)
                                Text("Perpanjang", color = c.rose, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.15f)).clickable { showManage = true }.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text("Kelola langganan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        // perks — first chip is the live premium-source count (when known)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerkChip("layers", if (premiumCount > 0) "$premiumCount+ source" else "Multi-source", Modifier.weight(1f))
            PERKS.forEach { (icon, label) -> PerkChip(icon, label, Modifier.weight(1f)) }
        }
        // plan picker — 3 cards on tablet, stacked rows on phone
        BoxWithConstraints {
            if (maxWidth >= 560.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    views.forEach { v -> PlanCardVertical(v, sel?.plan?.code == v.plan.code, Modifier.weight(1f)) { planCode = v.plan.code } }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    views.forEach { v -> PlanCard(v, sel?.plan?.code == v.plan.code) { planCode = v.plan.code } }
                }
            }
        }
        // checkout — stacked on phone (price block above a full-width CTA).
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.roseTint).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(if (active) "Perpanjang" else "Mulai langganan", color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(sel?.priceLabel ?: "—", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1, softWrap = false)
                    Text(sel?.perLabel ?: "", color = c.muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
                if (active) Text("Waktu ditambah ke sisa langganan — gak hangus.", color = c.muted, fontSize = 11.sp)
            }
            com.tetonova.app.ui.TnPrimaryButton(
                text = "Bayar ${sel?.priceLabel ?: ""}", icon = "shield", modifier = Modifier.fillMaxWidth(),
            ) { launchCheckout(sel) }
        }
    }

    if (showManage) ManageSubscriptionDialog(onDismiss = { showManage = false })
}

/** One perk chip (icon + short label). */
@Composable
private fun PerkChip(icon: String, label: String, modifier: Modifier) {
    val c = TnTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(TnRadii.sm)).background(c.surface2).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TnIcon(icon, size = 16.dp, tint = c.rose)
        Text(label, color = c.ink2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** "Kelola langganan" — payment-history only (the hero already shows status/expiry/sync). */
@Composable
private fun ManageSubscriptionDialog(onDismiss: () -> Unit) {
    val c = TnTheme.colors
    var rows by remember { mutableStateOf<List<PaymentRow>?>(null) }
    LaunchedEffect(Unit) { rows = TnData.paymentHistory() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(TnRadii.lg)).background(c.surface).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Riwayat pembayaran", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            val list = rows
            when {
                list == null -> Text("Memuat…", color = c.muted, fontSize = 12.sp)
                list.isEmpty() -> Text("Belum ada pembayaran.", color = c.muted, fontSize = 12.sp)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.take(20).forEach { p ->
                        val planLabel = p.planDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: p.planCode
                        val dateLabel = parseIsoMs(p.createdAt)?.let { fmtDate(it) } ?: p.createdAt
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.sm)).background(c.surface2).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(planLabel, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${rupiah(p.amountIdr)} - $dateLabel", color = c.muted, fontSize = 11.sp)
                            }
                            Text(paymentStatusLabel(p.status), color = paymentStatusColor(p.status, c.muted), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            TnGhostButton("Tutup", modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

private fun paymentStatusLabel(s: String): String = when (s) {
    "completed" -> "Lunas"
    "pending" -> "Menunggu"
    "expired" -> "Kedaluwarsa"
    "cancelled" -> "Dibatalkan"
    else -> s
}

private fun paymentStatusColor(s: String, muted: Color): Color = when (s) {
    "completed" -> Color(0xFF1FA463)
    "pending" -> Color(0xFFE08A1F)
    else -> muted
}

/** Vertical plan card (tablet 3-up). */
@Composable
private fun PlanCardVertical(v: PlanView, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = TnTheme.colors
    Box(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(if (selected) c.roseTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.rose else c.line, RoundedCornerShape(TnRadii.md))
            .clickable { onClick() }.padding(16.dp),
    ) {
        if (v.best) Box(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(TnRadii.pill)).tnGradient(RoseGradientColors).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text("Terpopuler", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(v.plan.displayName.uppercase(), color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(v.priceLabel, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text(v.perLabel, color = c.muted, fontSize = 12.sp)
            }
            Text(v.perMonthLabel ?: "Coba dulu", color = c.muted, fontSize = 11.sp)
            v.savingsLabel?.let {
                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color(0x1A1FA463)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(it, color = Color(0xFF1FA463), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (selected) c.rose else Color.Transparent).border(if (selected) 0.dp else 2.dp, c.line2, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) TnIcon("check", size = 13.dp, tint = Color.White) }
        }
    }
}

@Composable
private fun PlanCard(v: PlanView, selected: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(if (selected) c.roseTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.rose else c.line, RoundedCornerShape(TnRadii.md))
            .clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(v.plan.displayName, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (v.best) Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).tnGradient(RoseGradientColors).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Terpopuler", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(v.priceLabel, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(v.perLabel, color = c.muted, fontSize = 12.sp)
            }
            Text((v.perMonthLabel ?: "Coba dulu") + (v.savingsLabel?.let { "  ·  $it" } ?: ""), color = c.muted, fontSize = 11.sp)
        }
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (selected) c.rose else Color.Transparent).border(if (selected) 0.dp else 2.dp, c.line2, CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (selected) TnIcon("check", size = 13.dp, tint = Color.White) }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(TnTheme.colors.line))
}

private fun Modifier.rot180(): Modifier = this.then(Modifier.graphicsLayer(rotationZ = 180f))
