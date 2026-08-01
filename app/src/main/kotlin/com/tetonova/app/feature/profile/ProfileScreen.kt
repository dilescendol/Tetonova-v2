package com.tetonova.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.tetonova.app.data.Achievement
import com.tetonova.app.data.AuthManager
import com.tetonova.app.data.MatureContentAccess
import com.tetonova.app.data.ProfileApi
import com.tetonova.app.data.RealmTier
import com.tetonova.app.data.TnData
import com.tetonova.app.data.rememberGoogleSignIn
import com.tetonova.app.feature.library.libraryGroup
import com.tetonova.app.ui.AchievementBadgeArtwork
import com.tetonova.app.ui.AppState
import com.tetonova.app.ui.CultivationPathArtwork
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.LoyaltyBadgeArtwork
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.PosterGrid
import com.tetonova.app.ui.isTelevision
import com.tetonova.app.ui.TnGhostButton
import com.tetonova.app.ui.TnPrimaryButton
import com.tetonova.app.ui.achievementArtworkRes
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
import com.tetonova.core.model.RewardItem
import com.tetonova.core.model.RewardState
import com.tetonova.core.model.StatItem
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(state: AppState) {
    // Pull fresh XP/level/streak/realm + achievements when Profile opens (best-effort).
    LaunchedEffect(Unit) {
        runCatching { TnData.refreshUserXp() }
        runCatching { TnData.refreshAchievements() }
        runCatching { TnData.refreshUserProfile() }
        runCatching { TnData.refreshSubscription() }
    }
    Box(Modifier.fillMaxSize()) {
        PageScroll(topInset = true) {
            ProfileHero(signedIn = state.signedIn, onOpenSubscription = { state.openSubscription() })
            SectionHead(title = "Statistik nonton", sub = "30 hari terakhir")
            StatGrid()
            SectionHead(title = "Jalan Kultivasi", sub = "Naik level untuk membuka realm")
            RewardTrack()
            val ach = TnData.achievements
            val achSub = if (ach.isEmpty()) "Kumpulkan pencapaianmu" else "${ach.count { it.unlocked }} dari ${ach.size} terbuka"
            SectionHead(title = "Pencapaian", sub = achSub)
            BadgeWall()
            SectionHead(
                title = "Library Lane",
                sub = "Riwayat, ikutan & unduhan",
                action = { TnGhostButton(text = "Buka Full Library", icon = "chevR", onClick = { state.openLibrary() }) },
            )
            Library(
                showMature = state.mature && MatureContentAccess.isEnabled(),
                onOpenDetail = state::openDetail,
            )
            SectionHead(title = "Pengaturan")
            SettingsShortcut(onClick = { state.openSettings() })
            Spacer(Modifier.height(24.dp))
        }
        // First-run gate (§7.5): once XP loads and no path is chosen, overlay the path picker.
        val xp = TnData.userXp
        if (xp != null && xp.cultivationPath == null) {
            PathSelectionScreen(
                onSelect = { TnData.selectCultivationPath(it) },
                error = TnData.pathSwitchError,
            )
        }
    }
}

@Composable
private fun SettingsShortcut(onClick: () -> Unit) {
    val c = TnTheme.colors
    TnCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface3),
                contentAlignment = Alignment.Center,
            ) {
                TnIcon("gear", size = 21.dp, tint = c.rose)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Pengaturan aplikasi", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
            TnGhostButton(text = "Buka", icon = "chevR", onClick = onClick)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileHero(signedIn: Boolean, onOpenSubscription: () -> Unit) {
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
    val frameUrl = xp?.equippedFrameUrl
        ?: frameRealmId?.let { id -> TnData.realms.firstOrNull { it.realmId == id }?.frameUrl }
    // Editable identity (synced to the account; falls back to local cache / Google / default).
    val name = TnData.profileName
    val username = TnData.profileUsername
    val photoUrl = TnData.profilePhotoUrl
    val loyalty = TnData.subscription?.loyalty
    var showEdit by remember { mutableStateOf(false) }
    val launchSignIn = rememberGoogleSignIn()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(TnRadii.lg))
            .background(banner.brush)
            .padding(22.dp),
    ) {
        // Pencil → edit profile (signed in) or prompt Google sign-in first so the edit can sync.
        Box(
            Modifier.align(Alignment.TopEnd).size(38.dp).clip(CircleShape).background(Color.White.copy(0.14f))
                .clickable { if (AuthManager.signedIn) showEdit = true else launchSignIn() },
            contentAlignment = Alignment.Center,
        ) {
            TnIcon("edit", size = 19.dp, tint = Color.White)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LevelRing(
                    letter = name.take(1).uppercase().ifBlank { "?" },
                    level = level,
                    frameUrl = frameUrl,
                    photoUrl = photoUrl,
                    loyaltyTierId = loyalty?.tierId,
                    loyaltyTier = loyalty?.tier ?: 0,
                    loyaltyActive = loyalty?.active == true,
                )
                Column {
                    Text("Selamat datang kembali · @$username", color = Color.White.copy(0.82f), fontSize = 12.sp)
                    Text(name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                }
            }
            // compact inline pills (wrap to next line as needed)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroPill("cloud", if (signedIn) "Sinkron aktif" else "Masuk untuk sinkron")
                // Live status word only (no source count); tap → Settings → Langganan to upgrade/manage.
                val premiumEntitled = TnData.subscription?.entitled == true
                HeroPill("sparkle", if (premiumEntitled) "Premium" else "Free", filled = premiumEntitled, onClick = onOpenSubscription)
                if (loyalty != null && loyalty.tier > 0) {
                    val stateLabel = if (loyalty.active) loyalty.displayName else "${loyalty.displayName} - redup"
                    HeroPill(
                        "trophy",
                        stateLabel,
                        filled = loyalty.active,
                        onClick = onOpenSubscription,
                        loyaltyTierId = loyalty.tierId,
                        loyaltyTier = loyalty.tier,
                        loyaltyActive = loyalty.active,
                    )
                }
                HeroPill("flame2", if (streak > 0) "$streak hari streak" else "Mulai streak")
                val freezes = TnData.subscription?.loyalty?.pills?.inventory ?: xp?.streakFreezes ?: 0
                if (freezes > 0) HeroPill("shield", "$freezes Pil Penjaga Qi", filled = true)
                val contributorLabel = xp?.contributor?.label?.takeIf { it.isNotBlank() } ?: "Kontributor baru"
                HeroPill("star", contributorLabel)
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
    if (showEdit) {
        EditProfileDialog(
            initialName = name,
            initialUsername = username,
            initialPhotoUrl = photoUrl,
            onDismiss = { showEdit = false },
        )
    }
}

@Composable
private fun LevelRing(
    letter: String,
    level: Int = 1,
    frameUrl: String? = null,
    photoUrl: String? = null,
    loyaltyTierId: String? = null,
    loyaltyTier: Int = 0,
    loyaltyActive: Boolean = false,
) {
    val frame = TnFrames.first { it.id == "rose" }
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(frame.ring), contentAlignment = Alignment.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).tnGradient(gradColors(0)), contentAlignment = Alignment.Center) {
                if (photoUrl != null) {
                    AsyncImage(
                        model = photoUrl,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Text(letter, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp)
                }
            }
        }
        // The current realm's frame cosmetic (when uploaded) wraps the avatar; else the rose ring above.
        if (frameUrl != null) {
            AsyncImage(model = frameUrl, contentDescription = null, modifier = Modifier.size(92.dp))
        }
        if (loyaltyTierId != null && loyaltyTier > 0) {
            LoyaltyBadgeArtwork(
                tierId = loyaltyTierId,
                tier = loyaltyTier,
                active = loyaltyActive,
                modifier = Modifier.align(Alignment.BottomEnd).size(38.dp),
                contentDescription = "Badge loyalty",
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).graphicsLayer(translationY = 10f)
                .clip(RoundedCornerShape(TnRadii.pill)).background(TnTheme.colors.rose).padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text("Lv.$level", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold) }
    }
}

/** Edit display name + handle + avatar photo; all changes sync to the account (cross-device). */
@Composable
private fun EditProfileDialog(
    initialName: String,
    initialUsername: String,
    initialPhotoUrl: String?,
    onDismiss: () -> Unit,
) {
    val c = TnTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var username by remember { mutableStateOf(initialUsername) }
    var photoUrl by remember { mutableStateOf(initialPhotoUrl) }
    var usernameError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
                if (bytes != null && bytes.isNotEmpty() && TnData.updateAvatar(bytes, mime)) {
                    photoUrl = TnData.profilePhotoUrl
                } else {
                    android.widget.Toast.makeText(ctx, "Gagal unggah foto, coba lagi", android.widget.Toast.LENGTH_SHORT).show()
                }
                uploading = false
            }
        }
    }

    val cleanUsername = username.removePrefix("@").lowercase()
    val usernameValid = Regex("^[a-z0-9_]{3,20}$").matches(cleanUsername)
    val nameValid = name.trim().isNotEmpty() && name.trim().length <= 40
    val canSave = nameValid && usernameValid && !saving && !uploading

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = c.surface,
        title = { Text("Edit profil", color = c.ink, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).tnGradient(gradColors(0)), contentAlignment = Alignment.Center) {
                        if (photoUrl != null) {
                            AsyncImage(model = photoUrl, contentDescription = null, modifier = Modifier.size(64.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        } else {
                            Text(name.take(1).uppercase().ifBlank { "?" }, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                        }
                    }
                    TnGhostButton(
                        text = if (uploading) "Mengunggah…" else "Ganti foto",
                        onClick = { if (!uploading) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Nama") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !nameValid,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tnFieldColors(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { input ->
                        username = input.removePrefix("@").lowercase()
                            .filter { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '_' }
                            .take(20)
                        usernameError = null
                    },
                    label = { Text("Username") },
                    prefix = { Text("@", color = c.muted) },
                    singleLine = true,
                    isError = usernameError != null || (username.isNotEmpty() && !usernameValid),
                    supportingText = {
                        val msg = usernameError
                            ?: if (username.isNotEmpty() && !usernameValid) "3–20 huruf kecil, angka, atau _" else null
                        if (msg != null) Text(msg, color = c.rose, fontSize = 11.sp)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = tnFieldColors(),
                )
            }
        },
        confirmButton = {
            TnPrimaryButton(
                text = if (saving) "Menyimpan…" else "Simpan",
                modifier = Modifier.alpha(if (canSave) 1f else 0.5f),
                onClick = {
                    if (!canSave) return@TnPrimaryButton
                    saving = true
                    usernameError = null
                    scope.launch {
                        when (TnData.updateProfile(name.trim(), cleanUsername)) {
                            is ProfileApi.SaveOutcome.Success -> { saving = false; onDismiss() }
                            ProfileApi.SaveOutcome.UsernameTaken -> { saving = false; usernameError = "Username sudah dipakai" }
                            ProfileApi.SaveOutcome.Invalid -> { saving = false; usernameError = "Nama/username tidak valid" }
                            ProfileApi.SaveOutcome.Failed -> {
                                saving = false
                                android.widget.Toast.makeText(ctx, "Gagal menyimpan, coba lagi", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
            )
        },
        dismissButton = { TnGhostButton(text = "Batal", onClick = { if (!saving) onDismiss() }) },
    )
}

@Composable
private fun tnFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TnTheme.colors.ink,
    unfocusedTextColor = TnTheme.colors.ink,
    cursorColor = TnTheme.colors.rose,
    focusedBorderColor = TnTheme.colors.rose,
    unfocusedBorderColor = TnTheme.colors.line,
    focusedContainerColor = TnTheme.colors.surface,
    unfocusedContainerColor = TnTheme.colors.surface,
)

@Composable
private fun HeroPill(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: (() -> Unit)? = null,
    loyaltyTierId: String? = null,
    loyaltyTier: Int = 0,
    loyaltyActive: Boolean = false,
) {
    Row(
        modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.15f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (loyaltyTierId != null && loyaltyTier > 0) {
            LoyaltyBadgeArtwork(
                tierId = loyaltyTierId,
                tier = loyaltyTier,
                active = loyaltyActive,
                modifier = Modifier.size(22.dp),
            )
        } else {
            TnIcon(icon, size = 14.dp, tint = Color.White, filled = filled)
        }
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
    ) else listOf(
        // New user / stats not loaded — honest zeros, never fabricated numbers.
        StatItem("Jam nonton", "0j", null, "play", 0),
        StatItem("Episode", "0", null, "eye", 0),
        StatItem("Hari streak", "0", null, "flame2", 0),
        StatItem("Episode selesai", "0", null, "check", 0),
    )
    val context = LocalContext.current
    val isWide = LocalConfiguration.current.screenWidthDp >= 600 || isTelevision(context)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { s2 -> StatTile(s2, Modifier.weight(1f), isWide = isWide) }
    }
}

/** Compact hour label for the stat tile: whole hours once past 10h, one decimal below. */
private fun fmtHours(h: Double): String = when {
    h <= 0.0 -> "0"
    h >= 10.0 -> "${h.toInt()}h"
    else -> String.format(java.util.Locale.US, "%.1fh", h)
}

@Composable
private fun StatTile(s: StatItem, modifier: Modifier, isWide: Boolean) {
    val c = TnTheme.colors
    Column(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(horizontal = 9.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        GradientTile(s.icon, s.grad, Modifier.size(36.dp), iconSize = 20.dp)
        if (isWide) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(s.value, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, softWrap = false)
                Text(s.label, color = c.muted, fontSize = 10.sp, maxLines = 1, softWrap = false)
                s.delta?.let { Text(it, color = Color(0xFF1FA463), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
            }
        } else {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(s.value, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, softWrap = false)
                s.delta?.let { Text(it, color = Color(0xFF1FA463), fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
            }
            Text(s.label, color = c.muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun RewardTrack() {
    val ladder = TnData.realms
    if (ladder.isEmpty()) {
        // Realm ladder not loaded yet (offline / first run) — render nothing rather than a fake track.
        return
    }
    val xp = TnData.userXp
    val level = xp?.level ?: 1
    val pathId = xp?.cultivationPath
    val equippedId = xp?.equippedFrame?.ifBlank { null } ?: xp?.realm?.realmId
    val listState = rememberLazyListState()
    val currentIdx = ladder.indexOfFirst { level in it.minLevel..it.maxLevel }.coerceAtLeast(0)
    // Auto-scroll the path to the cultivator's current realm.
    LaunchedEffect(currentIdx, ladder.size) { runCatching { listState.animateScrollToItem(currentIdx) } }
    LazyRow(
        state = listState,
        modifier = Modifier.bleedEnd(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ladder.size, key = { ladder[it].realmId }) { i ->
            val r = ladder[i]
            val state = when {
                level in r.minLevel..r.maxLevel -> RewardState.NEXT      // current realm
                level > r.maxLevel -> RewardState.CLAIMED                 // passed
                else -> RewardState.LOCKED                                // not yet reached
            }
            RealmNode(r, state, pathId = pathId, equipped = r.realmId == equippedId, onClick = {
                if (state != RewardState.LOCKED) TnData.equipFrame(r.realmId) // wear a reached realm's frame
            })
        }
    }
}

@Composable
private fun RealmNode(r: RealmTier, state: RewardState, pathId: String?, equipped: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    val locked = state == RewardState.LOCKED
    var focused by remember(r.realmId) { mutableStateOf(false) }
    val restingBorderWidth = if (equipped) 2.dp else 1.dp
    val restingBorderColor = if (equipped) c.rose else c.line
    Column(
        Modifier.width(120.dp)
            .graphicsLayer {
                scaleX = if (focused) 1.04f else 1f
                scaleY = if (focused) 1.04f else 1f
            }
            .clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
            .border(if (focused) 4.dp else restingBorderWidth, if (focused) Color.White else restingBorderColor, RoundedCornerShape(TnRadii.md))
            .border(if (focused) 2.dp else 0.dp, if (focused) c.rose else Color.Transparent, RoundedCornerShape(TnRadii.md))
            .onFocusChanged { focused = it.isFocused }
            .then(if (!locked) Modifier.clickable { onClick() } else Modifier.focusable())
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(50.dp).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface3),
            contentAlignment = Alignment.Center,
        ) {
            // Uploaded cosmetics win; bundled path seals keep the ladder intentional offline.
            if (r.badgeUrl != null && !locked) {
                SubcomposeAsyncImage(
                    model = r.badgeUrl,
                    contentDescription = r.displayId,
                    modifier = Modifier.size(43.dp),
                    loading = {
                        CultivationPathArtwork(
                            pathId = pathId,
                            realmId = r.realmId,
                            modifier = Modifier.size(43.dp),
                        )
                    },
                    error = {
                        CultivationPathArtwork(
                            pathId = pathId,
                            realmId = r.realmId,
                            modifier = Modifier.size(43.dp),
                        )
                    },
                    success = { SubcomposeAsyncImageContent() },
                )
            } else {
                CultivationPathArtwork(
                    pathId = pathId,
                    realmId = r.realmId,
                    locked = locked,
                    modifier = Modifier.size(43.dp),
                    contentDescription = r.displayId,
                )
                if (locked) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(c.surface),
                        contentAlignment = Alignment.Center,
                    ) { TnIcon("lock", size = 11.dp, tint = c.muted) }
                }
            }
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
    // No achievements loaded yet → empty (no fabricated badges).
    LazyRow(
        modifier = Modifier.bleedEnd(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(ach.size, key = { ach[it].id }) { i ->
            AchievementTile(ach[i], Modifier.width(144.dp).height(196.dp))
        }
    }
}

@Composable
private fun AchievementTile(a: Achievement, modifier: Modifier) {
    val c = TnTheme.colors
    val unlocked = a.unlocked
    val hidden = a.hidden && !unlocked
    val name = if (hidden) "???" else a.name
    val desc = if (hidden) "Rahasia" else a.desc
    val customArtwork = achievementArtworkRes(a.id) != null
    var focused by remember(a.id) { mutableStateOf(false) }
    Column(
        modifier
            .graphicsLayer {
                scaleX = if (focused) 1.04f else 1f
                scaleY = if (focused) 1.04f else 1f
            }
            .clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
            .border(if (focused) 4.dp else 1.dp, if (focused) Color.White else c.line, RoundedCornerShape(TnRadii.md))
            .border(if (focused) 2.dp else 0.dp, if (focused) c.rose else Color.Transparent, RoundedCornerShape(TnRadii.md))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(c.surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (customArtwork) {
                AchievementBadgeArtwork(
                    achievementId = a.id,
                    unlocked = unlocked,
                    modifier = Modifier.size(64.dp),
                    contentDescription = if (hidden) null else a.name,
                )
            } else {
                Box(
                    Modifier.size(54.dp).clip(CircleShape)
                        .then(if (unlocked) Modifier.tnGradient(gradColors(a.grad)) else Modifier.background(c.surface3)),
                    contentAlignment = Alignment.Center,
                ) { TnIcon(if (unlocked) a.icon else "lock", size = 26.dp, tint = if (unlocked) Color.White else c.faint) }
            }
            if (!unlocked && customArtwork) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(21.dp).clip(CircleShape).background(c.surface),
                    contentAlignment = Alignment.Center,
                ) { TnIcon("lock", size = 12.dp, tint = c.muted) }
            }
        }
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
            Text(
                name,
                color = c.ink,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                desc,
                color = c.muted,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.Center) {
            if (a.legacy) {
                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface3).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("Warisan", color = c.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            } else if (a.maxTier > 1) {
                Text("Tahap ${a.tier}/${a.maxTier}", color = if (unlocked) c.rose else c.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Library(showMature: Boolean, onOpenDetail: (DetailArg) -> Unit) {
    val c = TnTheme.colors
    var tab by remember { mutableStateOf("history") }
    val tabs = listOf(
        Triple("history", "History", "clock"),
        Triple("followed", "Followed", "bookmark"),
        Triple("downloads", "Downloads", "download"),
    )
    // LIVE library data — real history / followed / downloads (no static catalog slice). Phones show
    // up to 4 per tab; tablets / Android TV show up to 10 (more width to fill).
    val cfg = LocalConfiguration.current
    val ctx = LocalContext.current
    val limit = if (cfg.screenWidthDp >= 600 || isTelevision(ctx)) 10 else 4
    val data = libraryGroup(tab, showMature).take(limit)
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
        if (data.isEmpty()) {
            Text(libraryEmptyHint(tab), color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            PosterGrid(items = data, onOpenDetail = onOpenDetail, showProgress = tab == "history")
        }
    }
}

/** Empty-state copy per Library tab (fresh install → History/Followed are empty). */
internal fun libraryEmptyHint(tab: String): String = when (tab) {
    "followed" -> "Belum ada judul yang diikuti. Tap + di halaman detail untuk mengikuti."
    "downloads" -> "Belum ada unduhan. Tap ikon unduh di episode untuk simpan offline."
    else -> "Belum ada riwayat. Judul yang kamu buka akan muncul di sini."
}
