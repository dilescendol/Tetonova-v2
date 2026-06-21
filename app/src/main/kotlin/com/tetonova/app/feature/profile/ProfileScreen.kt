package com.tetonova.app.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.tetonova.app.data.RealmTier
import com.tetonova.app.data.TnData
import com.tetonova.app.ui.AppState
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.PosterGrid
import com.tetonova.app.ui.TnGhostButton
import com.tetonova.app.ui.TnPrimaryButton
import com.tetonova.app.ui.bleedEnd
import com.tetonova.core.designsystem.GradientTile
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.TnProgress
import com.tetonova.core.designsystem.theme.TnBanners
import com.tetonova.core.designsystem.theme.TnFrames
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.theme.gradColors
import com.tetonova.core.designsystem.tnGradient
import com.tetonova.core.model.BadgeItem
import com.tetonova.core.model.RewardItem
import com.tetonova.core.model.RewardState
import com.tetonova.core.model.SampleData
import com.tetonova.core.model.StatItem
import kotlinx.coroutines.delay

@Composable
fun ProfileScreen(state: AppState) {
    val ctx = LocalContext.current
    // Pull fresh XP/level/streak/realm + achievements when Profile opens (best-effort).
    LaunchedEffect(Unit) {
        runCatching { TnData.refreshUserXp() }
        runCatching { TnData.refreshAchievements() }
    }
    // Breakthrough: a realm crossing triggers the full "Tribulasi" overlay; a plain level-up just toasts.
    var tribulation by remember { mutableStateOf<TnData.BreakthroughEvent?>(null) }
    val breakthrough = TnData.breakthrough
    LaunchedEffect(breakthrough) {
        breakthrough?.let {
            if (it.realmChanged) tribulation = it
            else android.widget.Toast.makeText(ctx, "⚡ Naik level — ${it.realmName}", android.widget.Toast.LENGTH_LONG).show()
            TnData.breakthrough = null
        }
    }
    Box(Modifier.fillMaxSize()) {
        PageScroll(topInset = true) {
            ProfileHero(signedIn = state.signedIn)
            SectionHead(title = "Statistik nonton", sub = "30 hari terakhir")
            StatGrid()
            CollapseSection(title = "Jalan Kultivasi", sub = "Naik level untuk membuka realm") { RewardTrack() }
            val ach = TnData.achievements
            val achSub = if (ach.isEmpty()) "Kumpulkan pencapaianmu" else "${ach.count { it.unlocked }} dari ${ach.size} terbuka"
            CollapseSection(title = "Pencapaian", sub = achSub) { BadgeWall() }
            SectionHead(
                title = "Library Lane",
                sub = "Riwayat, ikutan & unduhan",
                action = { TnGhostButton(text = "Buka Full Library", icon = "chevR") },
            )
            Library(onOpenDetail = state::openDetail)
            SectionHead(title = "Akun & Aplikasi", sub = "Kelola akun, dukungan, dan pengaturan")
            AccountFooter(state)
            Spacer(Modifier.height(24.dp))
        }
        tribulation?.let { TribulationOverlay(it) { tribulation = null } }
    }
}

/** Full-screen lightning "Tribulasi" celebration shown when the user crosses into a new realm. */
@Composable
private fun TribulationOverlay(event: TnData.BreakthroughEvent, onDone: () -> Unit) {
    val c = TnTheme.colors
    val appear = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(450)) }
    LaunchedEffect(Unit) { delay(3000); onDone() }
    val flash = rememberInfiniteTransition(label = "tribulasi")
    val bolt by flash.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(230), RepeatMode.Reverse), label = "bolt",
    )
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(0.85f)).clickable { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value },
        ) {
            Text("⚡", fontSize = 72.sp, modifier = Modifier.graphicsLayer { alpha = bolt })
            Text("TEROBOSAN!", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp)
            Box(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).tnGradient(gradColors(0)).padding(horizontal = 16.dp, vertical = 7.dp),
            ) { Text(event.realmName, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
            Text("Tribulasi dilalui · Lv.${event.level}", color = Color.White.copy(0.85f), fontSize = 13.sp)
            Text("⚡  ⚡  ⚡", color = c.rose, fontSize = 26.sp, modifier = Modifier.graphicsLayer { alpha = bolt })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileHero(signedIn: Boolean) {
    val banner = TnBanners.first { it.id == "grape" }
    // Live cultivation identity (falls back to a fresh Manusia Fana / Lv.1 cultivator when offline).
    val xp = TnData.userXp
    val realm = xp?.realm
    val level = xp?.level ?: 1
    val realmName = realm?.displayName ?: "Manusia Fana"
    val streak = xp?.streakDays ?: 0
    val into = xp?.xpIntoLevel ?: 0
    val need = xp?.xpForNextLevel ?: 0
    val frac = if (need > 0) (into.toFloat() / need).coerceIn(0f, 1f) else 1f
    // Equipped frame (chosen from any reached realm) overrides the current realm's frame.
    val frameRealmId = xp?.equippedFrame?.ifBlank { null } ?: realm?.realmId
    val frameUrl = frameRealmId?.let { id -> TnData.realms.firstOrNull { it.realmId == id }?.frameUrl }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TnRadii.lg))
            .background(banner.brush)
            .padding(22.dp),
    ) {
        Box(Modifier.align(Alignment.TopEnd).size(38.dp).clip(CircleShape).background(Color.White.copy(0.14f)), contentAlignment = Alignment.Center) {
            TnIcon("edit", size = 19.dp, tint = Color.White)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LevelRing(letter = "R", level = level, frameUrl = frameUrl)
                Column {
                    Text("Selamat datang kembali · @rafzhx", color = Color.White.copy(0.82f), fontSize = 12.sp)
                    Text("Rafa Nova", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                }
            }
            // compact inline pills (wrap to next line as needed)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill("cloud", if (signedIn) "Sinkron aktif" else "Masuk untuk sinkron")
                HeroPill("sparkle", "Premium · 40 source", filled = true)
                HeroPill("flame2", if (streak > 0) "$streak hari streak" else "Mulai streak")
                val freezes = xp?.streakFreezes ?: 0
                if (freezes > 0) HeroPill("shield", "$freezes Pil Penjaga Qi", filled = true)
                HeroPill("star", "Top 5% kontributor")
            }
            // Cultivation realm + breakthrough progress (was a hardcoded Lv./XP line).
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$realmName · Lv.$level", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(if (need > 0) "$into / $need XP menuju terobosan" else "Realm puncak tercapai", color = Color.White.copy(0.85f), fontSize = 11.sp)
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.22f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(TnRadii.pill)).background(Color.White))
                }
            }
        }
    }
}

@Composable
private fun LevelRing(letter: String, level: Int = 1, frameUrl: String? = null) {
    val frame = TnFrames.first { it.id == "rose" }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(frame.ring), contentAlignment = Alignment.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).tnGradient(gradColors(0)), contentAlignment = Alignment.Center) {
                Text(letter, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
            }
        }
        // The current realm's frame cosmetic (when uploaded) wraps the avatar; else the rose ring above.
        if (frameUrl != null) {
            AsyncImage(model = frameUrl, contentDescription = null, modifier = Modifier.size(92.dp))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).graphicsLayer(translationY = 10f)
                .clip(RoundedCornerShape(TnRadii.pill)).background(TnTheme.colors.rose).padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text("Lv.$level", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

@Composable
private fun HeroPill(icon: String, label: String, modifier: Modifier = Modifier, filled: Boolean = false) {
    Row(
        modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.15f)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        TnIcon(icon, size = 14.dp, tint = Color.White, filled = filled)
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun StatGrid() {
    // 4 tiles in one row stays tight on a phone, so sizes follow the design's phone
    // scale (value 18sp, delta 9.5sp, 9dp side padding) — otherwise a value + 3-char
    // delta like "1.3k +24" overflows and clips. See device.css `.stat` phone rules.
    val xp = TnData.userXp
    val s = xp?.stats30d
    val stats = if (s != null) listOf(
        StatItem("Jam nonton", fmtHours(s.watchHours), null, "play", 0),
        StatItem("Episode", s.episodes.toString(), null, "eye", 6),
        StatItem("Hari streak", xp.streakDays.toString(), null, "flame2", 4),
        StatItem("Episode selesai", s.episodesCompleted.toString(), null, "check", 2),
    ) else SampleData.stats
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { s2 -> StatTile(s2, Modifier.weight(1f)) }
    }
}

/** Compact hour label for the stat tile: whole hours once past 10h, one decimal below. */
private fun fmtHours(h: Double): String = when {
    h <= 0.0 -> "0"
    h >= 10.0 -> "${h.toInt()}h"
    else -> String.format(java.util.Locale.US, "%.1fh", h)
}

@Composable
private fun StatTile(s: StatItem, modifier: Modifier) {
    val c = TnTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(horizontal = 9.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GradientTile(s.icon, s.grad, Modifier.size(36.dp), iconSize = 20.dp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(s.value, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, softWrap = false)
            s.delta?.let { Text(it, color = Color(0xFF1FA463), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
        }
        Text(s.label, color = c.muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun CollapseSection(title: String, sub: String, content: @Composable () -> Unit) {
    val c = TnTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 18.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(sub, color = c.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TnIcon("chevD", size = 19.dp, tint = c.ink2, modifier = if (open) Modifier.graphicsLayer(rotationZ = 180f) else Modifier)
        }
        AnimatedVisibility(open) {
            Box(Modifier.padding(top = 12.dp)) { content() }
        }
    }
}

@Composable
private fun RewardTrack() {
    val ladder = TnData.realms
    if (ladder.isEmpty()) {
        // Realm ladder not loaded yet (offline / first run) → keep the bundled sample track.
        LazyRow(modifier = Modifier.bleedEnd(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(SampleData.rewards.size) { i -> RewardNode(SampleData.rewards[i]) }
        }
        return
    }
    val xp = TnData.userXp
    val level = xp?.level ?: 1
    val equippedId = xp?.equippedFrame?.ifBlank { null } ?: xp?.realm?.realmId
    val listState = rememberLazyListState()
    val currentIdx = ladder.indexOfFirst { level in it.minLevel..it.maxLevel }.coerceAtLeast(0)
    // Auto-scroll the path to the cultivator's current realm.
    LaunchedEffect(currentIdx, ladder.size) { runCatching { listState.animateScrollToItem(currentIdx) } }
    LazyRow(state = listState, modifier = Modifier.bleedEnd(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(ladder.size) { i ->
            val r = ladder[i]
            val state = when {
                level in r.minLevel..r.maxLevel -> RewardState.NEXT      // current realm
                level > r.maxLevel -> RewardState.CLAIMED                 // passed
                else -> RewardState.LOCKED                                // not yet reached
            }
            RealmNode(r, state, grad = i % 8, equipped = r.realmId == equippedId, onClick = {
                if (state != RewardState.LOCKED) TnData.equipFrame(r.realmId) // wear a reached realm's frame
            })
        }
    }
}

@Composable
private fun RealmNode(r: RealmTier, state: RewardState, grad: Int, equipped: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    val locked = state == RewardState.LOCKED
    Column(
        Modifier.width(120.dp).clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
            .border(if (equipped) 2.dp else 1.dp, if (equipped) c.rose else c.line, RoundedCornerShape(TnRadii.md))
            .then(if (!locked) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(TnRadii.sm)).then(if (locked) Modifier.background(c.surface3) else Modifier.tnGradient(gradColors(grad))),
            contentAlignment = Alignment.Center,
        ) {
            // The realm's badge emblem (cosmetic) when uploaded; else a gradient tile + sparkle/lock icon.
            if (r.badgeUrl != null && !locked) AsyncImage(model = r.badgeUrl, contentDescription = null, modifier = Modifier.size(34.dp))
            else TnIcon(if (locked) "lock" else "sparkle", size = 23.dp, tint = if (locked) c.faint else Color.White)
        }
        Text("Lv.${r.minLevel}", color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(r.displayId, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        val (bg, fg, label) = when {
            equipped -> Triple(c.rose, Color.White, "Dipakai")
            state == RewardState.CLAIMED -> Triple(c.roseSoft, c.roseDeep, "Tercapai")
            state == RewardState.NEXT -> Triple(c.rose, Color.White, "Sekarang")
            else -> Triple(c.surface3, c.muted, "Terkunci")
        }
        Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RewardNode(r: RewardItem) {
    val c = TnTheme.colors
    val locked = r.state == RewardState.LOCKED
    Column(
        Modifier.width(120.dp).clip(RoundedCornerShape(TnRadii.md)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(TnRadii.sm)).then(if (locked) Modifier.background(c.surface3) else Modifier.tnGradient(gradColors(r.grad))),
            contentAlignment = Alignment.Center,
        ) { TnIcon(r.icon, size = 23.dp, tint = if (locked) c.faint else Color.White) }
        Text(r.level, color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Text(r.name, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        val (bg, fg, label) = when (r.state) {
            RewardState.CLAIMED -> Triple(c.roseSoft, c.roseDeep, "Diklaim")
            RewardState.NEXT -> Triple(c.rose, Color.White, "Berikutnya")
            RewardState.LOCKED -> Triple(c.surface3, c.muted, "Terkunci")
        }
        Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BadgeWall() {
    val ach = TnData.achievements
    // Hidden + still-locked achievements show as "???"; everything else binds live.
    val items = if (ach.isEmpty()) SampleData.badges else ach.map { a ->
        BadgeItem(
            name = if (a.hidden && !a.unlocked) "???" else a.name,
            desc = if (a.hidden && !a.unlocked) "Rahasia" else a.desc,
            icon = a.icon, grad = a.grad, locked = !a.unlocked,
        )
    }
    LazyRow(
        modifier = Modifier.bleedEnd(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.size) { i -> BadgeTile(items[i], Modifier.width(120.dp)) }
    }
}

@Composable
private fun BadgeTile(b: BadgeItem, modifier: Modifier) {
    val c = TnTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).then(if (b.locked) Modifier.background(c.surface3) else Modifier.tnGradient(gradColors(b.grad))),
            contentAlignment = Alignment.Center,
        ) { TnIcon(if (b.locked) "lock" else b.icon, size = 26.dp, tint = if (b.locked) c.faint else Color.White) }
        Text(b.name, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(b.desc, color = c.muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun Library(onOpenDetail: (DetailArg) -> Unit) {
    val c = TnTheme.colors
    var tab by remember { mutableStateOf("history") }
    val tabs = listOf(
        Triple("history", "History", "clock"),
        Triple("followed", "Followed", "bookmark"),
        Triple("downloads", "Downloads", "download"),
    )
    // Live catalog posters (real covers) — 4 per tab, a distinct slice each.
    val live = remember(TnData.panelVersion) { TnData.posters }
    val data = when (tab) {
        "followed" -> live.drop(4).take(4)
        "downloads" -> live.drop(8).take(4)
        else -> live.take(4)
    }.ifEmpty { live.take(4) }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { (id, label, icon) ->
                val on = id == tab
                Row(
                    Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(if (on) c.rose else c.surface)
                        .border(1.dp, if (on) c.rose else c.line, RoundedCornerShape(TnRadii.pill))
                        .clickable { tab = id }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    TnIcon(icon, size = 15.dp, tint = if (on) Color.White else c.muted)
                    Text(label, color = if (on) Color.White else c.ink2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PosterGrid(items = data, onOpenDetail = onOpenDetail, showProgress = false)
    }
}

@Composable
private fun AccountFooter(state: AppState) {
    val c = TnTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Account card
        TnCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.signedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).tnGradient(gradColors(0)), contentAlignment = Alignment.Center) {
                            Text("R", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Rafa Nova", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("rafa@nova.id", color = c.muted, fontSize = 12.sp)
                        }
                        Row(
                            Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color(0x1A1FA463)).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF1FA463)))
                            Text("Sinkron aktif", color = Color(0xFF1FA463), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text("Terakhir sinkron 2 menit lalu · 3 perangkat", color = c.muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TnGhostButton(text = "Ganti akun", icon = "key")
                        TnGhostButton(text = "Keluar", icon = "logout", onClick = { state.signedIn = false })
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(c.surface3), contentAlignment = Alignment.Center) {
                            TnIcon("user", size = 22.dp, tint = c.muted)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Belum masuk", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Data tersimpan di perangkat ini saja", color = c.muted, fontSize = 12.sp)
                        }
                    }
                    Text("Masuk untuk sinkron library, history & badge antar perangkat.", color = c.muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TnPrimaryButton(text = "Masuk / Daftar", icon = "login", onClick = { state.signedIn = true })
                        TnGhostButton(text = "Mode tamu")
                    }
                }
            }
        }
        // Support card — title/description/button + donate link all from the panel's supportMe.
        val support = remember(TnData.panelVersion) { TnData.supportMe }
        if (support?.enabled != false) {
            val ctx = LocalContext.current
            val desc = support?.description?.ifBlank { null }
                ?: "Server, scraper sources, dan workers butuh kopi. Donasi sekali atau bulanan — terserah kamu."
            val btnLabel = support?.buttonLabel?.ifBlank { null } ?: "Dukung sekarang"
            val url = support?.url?.ifBlank { null } ?: "https://tetonova.dilcendol.web.id"
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.lg)).tnGradient(com.tetonova.core.designsystem.theme.RoseGradientColors).padding(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        TnIcon("heart", size = 12.dp, tint = Color.White, filled = true)
                        Text(support?.label?.ifBlank { null } ?: "Dukung TetoNova", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Text("Bantu TetoNova tetap gratis & berkembang", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    Text(desc, color = Color.White.copy(0.9f), fontSize = 12.sp)
                    Box(
                        Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White)
                            .clickable { openUrl(ctx, url) }.padding(horizontal = 18.dp, vertical = 11.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            TnIcon("heart", size = 16.dp, tint = c.rose, filled = true)
                            Text(btnLabel, color = c.rose, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        // Quick tiles — 2-column grid on wide screens
        val tiles = listOf(
            Tile("gear", 0, "Pengaturan", "Tampilan, konten, notifikasi") { state.openSettings() },
            Tile("cloud", 1, "Cadangan & Sinkron", "Backup library lokal") { state.openSettings() },
            Tile("help", 4, "Bantuan", "FAQ & laporan masalah") { state.openSettings() },
            Tile("sparkle", 5, "Langganan & API", "Premium aktif · 36 hari lagi") { state.openSettings() },
            Tile("info", 2, "Tentang", "TetoNova 0.1.0 · Catatan rilis") { state.openSettings() },
        )
        BoxWithConstraints {
            val cols = if (maxWidth >= 560.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tiles.chunked(cols).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { t -> QuickTile(t.icon, t.grad, t.title, t.sub, Modifier.weight(1f), t.onClick) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private class Tile(
    val icon: String,
    val grad: Int,
    val title: String,
    val sub: String,
    val onClick: () -> Unit,
)

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
}

@Composable
private fun QuickTile(icon: String, grad: Int, title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = TnTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md))
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GradientTile(icon, grad, Modifier.size(40.dp), iconSize = 18.dp, filled = icon == "sparkle")
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(sub, color = c.muted, fontSize = 12.sp)
        }
        TnIcon("chevR", size = 18.dp, tint = c.faint)
    }
}
