package com.tetonova.app.feature.settings

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyRow
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
import com.tetonova.app.data.TrialPhase
import com.tetonova.app.data.TrialStore
import com.tetonova.app.ui.AppState
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.TnGhostButton
import com.tetonova.app.ui.bleedEnd
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.RoseGradientColors
import com.tetonova.core.designsystem.theme.TnAccents
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.tnGradient
import kotlinx.coroutines.delay

private data class Plan(val id: String, val name: String, val price: Int, val per: String, val sub: String, val save: String?, val best: Boolean = false)

private val PLANS = listOf(
    Plan("month", "Bulanan", 15000, "/bln", "Coba dulu", null),
    Plan("quarter", "3 Bulan", 40000, "/3 bln", "≈ Rp 13.300/bln", "Hemat 11%"),
    Plan("year", "Tahunan", 120000, "/thn", "≈ Rp 10.000/bln", "Hemat 33%", best = true),
)
private val PERKS = listOf("layers" to "40+ source", "zap" to "Tanpa iklan", "refresh" to "Auto-update", "shield" to "Server prioritas")
private val ANCHORS = listOf(
    "user" to "Akun", "sparkle" to "Langganan", "palette" to "Tampilan", "play" to "Pemutaran",
    "eye" to "Konten", "bell" to "Notifikasi", "cloud" to "Cadangan", "info" to "Tentang",
)
private fun rp(n: Int) = "Rp " + "%,d".format(n).replace(',', '.')

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
        Text("Atur tampilan, pemutaran, konten, dan sinkron antar perangkat.", color = c.muted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        LazyRow(
            modifier = Modifier.bleedEnd(20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ANCHORS.size) { i ->
                val (icon, label) = ANCHORS[i]
                Row(
                    Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    TnIcon(icon, size = 14.dp, tint = c.ink2)
                    Text(label, color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        SectionCard("Akun & Sinkron", "Kelola akun dan koneksi cloud", "user") { AccountHero(state) }
        SectionCard("Langganan & API", "Akses 40+ source lewat API provider", "sparkle") { SubscriptionPanel() }
        SectionCard("Tampilan", "Tema, warna aksen, dan kepadatan UI", "palette") { AppearanceSection(state) }
        SectionCard("Pemutaran & Data", "Kualitas stream dan perilaku player", "play") { PlaybackSection(state) }
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
                Text(desc, color = c.muted, fontSize = 12.sp)
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
                Text(desc, color = c.muted, fontSize = 12.sp)
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
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (state.signedIn) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).tnGradient(com.tetonova.core.designsystem.theme.gradColors(0)), contentAlignment = Alignment.Center) {
                    Text("R", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Rafa Nova", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("rafa@nova.id", color = c.muted, fontSize = 12.sp)
                    Text("Sinkron aktif · 3 perangkat · Terakhir 2 menit lalu", color = c.muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TnGhostButton(text = "Ubah password", icon = "key")
                TnGhostButton(text = "Keluar", icon = "logout", onClick = { state.signedIn = false })
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Masuk untuk sinkron lintas perangkat", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                Text("Library, history, dan badge kamu akan otomatis tersinkron. Tetap gratis, tanpa iklan.", color = c.muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.tetonova.app.ui.TnPrimaryButton(text = "Masuk dengan Email", icon = "mail", onClick = { state.signedIn = true })
                    TnGhostButton(text = "Lanjut dengan Google", icon = "globe")
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
    SettingRow("zap", "Skip opening", "Lewati intro & ending otomatis kalau terdeteksi.") { TnToggle(state.skipOp) { state.skipOp = it } }
}

@Composable
private fun ContentSection(state: AppState) {
    SettingRow("eyeOff", "Konten Dewasa (18+)", "Tampilkan atau sembunyikan judul bertanda 18+.") { TnToggle(state.mature) { state.mature = it } }
    Divider()
    SettingRow("flag", "Lapor konten bermasalah", "Cara cepat lapor judul / source yang error.") { TnGhostButton(text = "Lapor sekarang", onClick = { state.openReport() }) }
}

@Composable
private fun NotifSection(state: AppState) {
    SettingRow("bookmark", "Follow notifications", "Saat judul yang kamu ikuti rilis episode baru.") { TnToggle(state.notifFollow) { state.notifFollow = it } }
    Divider()
    SettingRow("comment", "Balasan forum", "Saat seseorang membalas thread atau komentar kamu.") { TnToggle(state.notifForum) { state.notifForum = it } }
    Divider()
    SettingRow("shield", "Push lokal saja", "Gunakan worker lokal, tanpa push stack eksternal.") { TnToggle(state.pushLocal) { state.pushLocal = it } }
}

@Composable
private fun BackupSection(state: AppState) {
    val context = LocalContext.current
    SettingRow("cloud", "Cadangan otomatis", if (state.signedIn) "Backup tiap 24 jam ke cloud akun kamu." else "Masuk dulu untuk aktifkan backup cloud.") {
        TnToggle(state.autoBackup && state.signedIn) { v -> if (state.signedIn) state.autoBackup = v else state.signedIn = true }
    }
    Divider()
    SettingRow("upload", "Ekspor data", "Unduh library, history, dan settings sebagai file.") { TnGhostButton(text = "Ekspor", icon = "download", onClick = { shareText(context, exportJson(state)) }) }
    Divider()
    SettingRow("file", "Impor dari file", "Pulihkan dari file backup .tnova sebelumnya.") { TnGhostButton(text = "Pilih file", icon = "upload", onClick = { Toast.makeText(context, "Pilih file .tnova — segera hadir", Toast.LENGTH_SHORT).show() }) }
}

@Composable
private fun AboutSection(state: AppState) {
    SettingRow("info", "Versi aplikasi", "TetoNova 0.1.0 · build 240608") { TnGhostButton(text = "Catatan rilis", onClick = { state.openReleaseNotes() }) }
    Divider()
    SettingRow("help", "Pusat bantuan", "FAQ, panduan source, dan kontak.") { TnGhostButton(text = "Buka", icon = "chevR", onClick = { state.openHelp() }) }
}

private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Ekspor data")) }
}

private fun exportJson(state: AppState): String =
    """{"app":"TetoNova","theme":"${if (state.darkTheme) "dark" else "light"}","accent":"${state.accentId}","quality":"${state.quality}","dataSaver":${state.dataSaver},"autoNext":${state.autoNext},"skipOpening":${state.skipOp},"mature":${state.mature}}"""

/** Format sisa waktu trial sebagai mm:ss. */
private fun fmtTrial(ms: Long): String { val t = ms / 1000; return "%02d:%02d".format(t / 60, t % 60) }

@Composable
private fun SubscriptionPanel() {
    val c = TnTheme.colors
    var status by remember { mutableStateOf("active") }
    var plan by remember { mutableStateOf("year") }
    val active = status == "active"
    val sel = PLANS.first { it.id == plan }
    // Trial Premium 1 jam (sekali pakai). Real state lewat TrialStore; tick tiap detik saat aktif.
    var phase by remember { mutableStateOf(TrialStore.phase()) }
    var remaining by remember { mutableStateOf(TrialStore.remainingMs()) }
    LaunchedEffect(phase) {
        while (phase == TrialPhase.ACTIVE) {
            remaining = TrialStore.remainingMs()
            if (remaining <= 0L) { phase = TrialPhase.EXPIRED; break }
            delay(1000)
        }
    }
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // status preview (demo toggle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PRATINJAU STATUS", color = c.muted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
            SegSelect(status, listOf("active" to "Premium aktif", "free" to "Gratis"), onSelect = { status = it })
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
                            Text("Aktif", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TnIcon("clock", size = 14.dp, tint = Color.White.copy(0.9f))
                        Text("Aktif sampai 12 Juli 2026", color = Color.White.copy(0.9f), fontSize = 12.sp)
                        Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("36 hari lagi", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.25f))) {
                        Box(Modifier.fillMaxWidth(0.7f).height(7.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White))
                    }
                } else {
                    Text("Sambungkan ke API provider untuk membuka 40+ source streaming — tanpa iklan, update otomatis, dan server prioritas.", color = c.ink2, fontSize = 12.sp)
                }
                // API provider sub-card — premium (demo) tersambung; free → pintu trial 1 jam.
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
                            Text("40 source aktif · sinkron 2 menit lalu", color = Color.White.copy(0.85f), fontSize = 11.sp)
                        }
                        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White.copy(0.14f)), contentAlignment = Alignment.Center) { TnIcon("refresh", size = 14.dp, tint = Color.White) }
                    }
                } else {
                    val (tIcon, tTitle, tSub) = when (phase) {
                        TrialPhase.AVAILABLE -> Triple("sparkle", "Coba Premium 1 jam gratis", "Sekali pakai · tanpa kartu")
                        TrialPhase.ACTIVE    -> Triple("clock", "Trial Premium aktif", "Sisa ${fmtTrial(remaining)}")
                        TrialPhase.EXPIRED   -> Triple("lock", "Trial sudah dipakai", "Berlangganan untuk lanjut akses")
                    }
                    val tTint = if (phase == TrialPhase.EXPIRED) c.muted else c.rose
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
                            .then(if (phase == TrialPhase.AVAILABLE) Modifier.clickable {
                                if (TrialStore.claim()) { phase = TrialPhase.ACTIVE; remaining = TrialStore.remainingMs() }
                            } else Modifier)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(34.dp).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface3), contentAlignment = Alignment.Center) {
                            TnIcon(tIcon, size = 16.dp, tint = tTint, filled = phase == TrialPhase.AVAILABLE)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(tTitle, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(tSub, color = c.muted, fontSize = 11.sp)
                        }
                        when (phase) {
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
                    // Two buttons share one full-width row (weight 1f each, centered, no wrap) —
                    // design handoff phone rule `.sub-actions .btn{flex:1 1 0;min-width:0;justify-content:center}`.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White).clickable {}.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                TnIcon("sparkle", size = 15.dp, tint = c.rose, filled = true)
                                Text("Perpanjang", color = c.rose, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                        }
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.15f)).clickable {}.padding(horizontal = 10.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text("Kelola langganan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        // perks
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PERKS.forEach { (icon, label) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface2).padding(vertical = 12.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    TnIcon(icon, size = 16.dp, tint = c.rose)
                    Text(label, color = c.ink2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        // plan picker — 3 cards on tablet, stacked rows on phone
        BoxWithConstraints {
            if (maxWidth >= 560.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PLANS.forEach { p -> PlanCardVertical(p, plan == p.id, Modifier.weight(1f)) { plan = p.id } }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PLANS.forEach { p -> PlanCard(p, plan == p.id) { plan = p.id } }
                }
            }
        }
        // checkout — stacked on phone (price block above a full-width CTA) so the
        // price never gets crushed into a mid-number wrap; design handoff
        // `@media phone .pay-card{flex-direction:column} .pay-cta{width:100%}`.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.roseTint).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(if (active) "Perpanjang" else "Mulai langganan", color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(rp(sel.price), color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1, softWrap = false)
                    Text(sel.per, color = c.muted, fontSize = 12.sp, maxLines = 1, softWrap = false)
                }
            }
            com.tetonova.app.ui.TnPrimaryButton(text = "Bayar ${rp(sel.price)}", icon = "shield", modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Vertical plan card (tablet 3-up). */
@Composable
private fun PlanCardVertical(p: Plan, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = TnTheme.colors
    Box(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(if (selected) c.roseTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.rose else c.line, RoundedCornerShape(TnRadii.md))
            .clickable { onClick() }.padding(16.dp),
    ) {
        if (p.best) Box(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(TnRadii.pill)).tnGradient(RoseGradientColors).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text("Terpopuler", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name.uppercase(), color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(rp(p.price), color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                Text(p.per, color = c.muted, fontSize = 12.sp)
            }
            Text(p.sub, color = c.muted, fontSize = 11.sp)
            p.save?.let {
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
private fun PlanCard(p: Plan, selected: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(if (selected) c.roseTint else c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.rose else c.line, RoundedCornerShape(TnRadii.md))
            .clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                if (p.best) Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).tnGradient(RoseGradientColors).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("Terpopuler", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(rp(p.price), color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text(p.per, color = c.muted, fontSize = 12.sp)
            }
            Text(p.sub + (p.save?.let { "  ·  $it" } ?: ""), color = c.muted, fontSize = 11.sp)
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
