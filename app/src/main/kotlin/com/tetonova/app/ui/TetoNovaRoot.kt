package com.tetonova.app.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as eventKey
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tetonova.app.feature.billing.QrisCheckoutScreen
import com.tetonova.app.feature.detail.DetailScreen
import com.tetonova.app.feature.extensions.ExtensionsScreen
import com.tetonova.app.feature.forum.ForumScreen
import com.tetonova.app.feature.home.HomeScreen
import com.tetonova.app.feature.landing.LandingScreen
import com.tetonova.app.feature.library.FullLibraryScreen
import com.tetonova.app.feature.manga.MangaDetailScreen
import com.tetonova.app.feature.manga.MangaTab
import com.tetonova.app.feature.manga.ReaderScreen
import com.tetonova.app.feature.player.PlayerScreen
import com.tetonova.app.feature.profile.ProfileScreen
import com.tetonova.app.feature.search.SearchScreen
import com.tetonova.app.feature.settings.HelpScreen
import com.tetonova.app.feature.settings.ReleaseNotesScreen
import com.tetonova.app.feature.settings.ReportScreen
import com.tetonova.app.feature.settings.SettingsScreen
import com.tetonova.app.feature.settings.SubscriptionScreen
import com.tetonova.app.BuildConfig
import com.tetonova.app.data.Announcement
import com.tetonova.app.data.CultivationLevelUpEvent
import com.tetonova.app.data.MatureContentAccess
import com.tetonova.app.data.SettingsStore
import com.tetonova.app.data.TnData
import com.tetonova.app.data.isMandatoryUpdateRequired
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TetoNovaTheme
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.model.NavDest
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun TetoNovaRoot(
    windowSizeClass: WindowSizeClass,
    debugPlayerUrl: String? = null,
    deepLink: Uri? = null,
    openSubscriptionRequest: Int = 0,
    openPaidPlans: Boolean = false,
) {
    val state = rememberAppState()
    val context = LocalContext.current
    val isTv = remember { isTelevision(context) }
    val useRail = isTv || windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val announcement = TnData.announcement
    val appUpdateConfig = TnData.appUpdateConfig
    val mandatoryUpdate = isMandatoryUpdateRequired(
        currentVersionCode = BuildConfig.VERSION_CODE,
        minimumVersionCode = appUpdateConfig.minimumVersionCode,
    )
    var dismissedAnnouncementId by remember {
        mutableStateOf(SettingsStore.getStr(DISMISSED_ANNOUNCEMENT_KEY, ""))
    }
    var announcementSnoozedUntil by remember {
        mutableLongStateOf(SettingsStore.getLong(ANNOUNCEMENT_SNOOZED_UNTIL_KEY, 0L))
    }

    // One-shot merge of control-panel source overrides. No-ops when no panel URL is configured
    // or the host is unreachable, so the app keeps running on the bundled registry (local-first).
    LaunchedEffect(Unit) {
        com.tetonova.app.data.TnData.warmPanel(com.tetonova.app.Secrets.controlPanelUrl)
    }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            com.tetonova.app.data.TnData.refreshSubscription()
            com.tetonova.app.data.SubscriptionExpiryReminder.checkNow(context)
            com.tetonova.app.data.TnData.warmPanel(com.tetonova.app.Secrets.controlPanelUrl, force = true)
        }
    }
    LaunchedEffect(debugPlayerUrl) {
        if (!debugPlayerUrl.isNullOrBlank()) {
            state.openPlayer(PlayerArg(title = "Smoke Test", url = debugPlayerUrl, episodeLabel = "URL test", badge = "Anime"))
        }
    }
    LaunchedEffect(deepLink?.toString()) {
        detailArgFromDeepLink(deepLink)?.let {
            state.openDetail(it)
        }
    }
    LaunchedEffect(openSubscriptionRequest, openPaidPlans) {
        if (openSubscriptionRequest > 0) state.openSubscription(showPaidPlans = openPaidPlans)
    }

    val effectiveDarkTheme = when (state.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    TetoNovaTheme(darkTheme = effectiveDarkTheme, accent = state.accentColor) {
        val c = TnTheme.colors
        var levelUpNotice by remember { mutableStateOf<CultivationLevelUpEvent?>(null) }
        val pendingLevelUp = TnData.breakthrough
        LaunchedEffect(pendingLevelUp, state.screen, levelUpNotice) {
            if (pendingLevelUp != null && state.screen !is Screen.Player && levelUpNotice == null) {
                levelUpNotice = pendingLevelUp
                TnData.consumeBreakthrough(pendingLevelUp)
            }
        }
        SystemBarsEffect(darkTheme = effectiveDarkTheme)
        // Retain the tab shell's UI state (Home scroll position, etc.) while Detail is on top, so
        // returning lands back on the exact rail/extension the user left — not scrolled to the top.
        val shellState = rememberSaveableStateHolder()
        val rootModifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .let { if (state.screen is Screen.Player) it else it.windowInsetsPadding(WindowInsets.statusBars) }
        // TV: swap the default ripple for a white + rose focus ring so it remains visible even when
        // the focused component already uses the active accent color.
        // every plain `.clickable {}` in the app picks this up. Phone/tablet keep the ripple untouched.
        // Rose (the app accent) reads on both the dark hero and the light episode list; white vanished there.
        val tvIndication = remember(c.rose) { TvFocusIndication(c.rose) }
        val focusIndication = if (isTv) tvIndication else LocalIndication.current
        CompositionLocalProvider(LocalIndication provides focusIndication) {
        Box(rootModifier) {
            if (mandatoryUpdate) {
                MandatoryUpdateScreen(
                    config = appUpdateConfig,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    isTv = isTv,
                    onUpdate = {
                        openRequiredUpdateUrl(
                            context,
                            appUpdateConfig.updatePageUrl.ifBlank { appUpdateConfig.updateUrl },
                        )
                    },
                )
            } else {
            if (!state.signedIn) {
                LandingScreen(wide = useRail)
            } else when (val scr = state.screen) {
                is Screen.Detail -> {
                    BackHandler { state.back() }
                    DetailScreen(arg = scr.arg, resumeEpisode = state.resumeEpisodeFor(scr.arg), onBack = { state.back() }, onOpenDetail = state::openDetail, onOpenPlayer = state::openPlayer)
                }
                is Screen.Player -> {
                    BackHandler { state.closePlayer() }
                    val a = scr.arg
                    // Next episode = the lowest episode number above the current one (order-independent:
                    // source lists may be ascending OR descending). Re-keying on the url restarts the
                    // player cleanly per episode (fresh ExoPlayer + source resolution).
                    val next = a.episodeNum?.let { cur -> a.playlist.filter { it.num > cur }.minByOrNull { it.num } }
                    // Prev episode = the highest episode number below the current one (same order-independent
                    // logic as `next`, mirrored). Powers the new prev-episode control in the player.
                    val prev = a.episodeNum?.let { cur -> a.playlist.filter { it.num < cur }.maxByOrNull { it.num } }
                    key(a.url) {
                        PlayerScreen(
                            arg = a,
                            hasNext = next != null,
                            onNext = { next?.let { state.openPlayer(a.copy(url = it.url, episodeLabel = it.label, episodeNum = it.num)) } },
                            hasPrev = prev != null,
                            onPrev = { prev?.let { state.openPlayer(a.copy(url = it.url, episodeLabel = it.label, episodeNum = it.num)) } },
                            onBack = { state.closePlayer() },
                        )
                    }
                }
                is Screen.Manga -> {
                    BackHandler { state.back() }
                    MangaDetailScreen(arg = scr.arg, onBack = { state.back() }, onOpenReader = state::openReader)
                }
                is Screen.Reader -> {
                    BackHandler { state.closeReader() }
                    key(scr.arg.chapterId) {
                        ReaderScreen(arg = scr.arg, onBack = { state.closeReader() }, onOpenReader = state::openReader)
                    }
                }
                is Screen.Report -> {
                    BackHandler { state.openSettings() }
                    ReportScreen(onBack = { state.openSettings() })
                }
                is Screen.ReleaseNotes -> {
                    BackHandler { state.openSettings() }
                    ReleaseNotesScreen(onBack = { state.openSettings() })
                }
                is Screen.Help -> {
                    BackHandler { state.openSettings() }
                    HelpScreen(onBack = { state.openSettings() })
                }
                is Screen.Qris -> {
                    BackHandler { state.openSubscription() }
                    QrisCheckoutScreen(initial = scr.arg, onClose = { state.openSubscription() })
                }
                is Screen.Library -> {
                    BackHandler { state.back() }
                    FullLibraryScreen(
                        showMature = state.mature && MatureContentAccess.isEnabled(),
                        onBack = { state.back() },
                        onOpenDetail = state::openDetail,
                    )
                }
                else -> shellState.SaveableStateProvider("tab-shell") {
                    MainShell(state = state, useRail = useRail, tv = isTv)
                }
            }

            val announcementId = announcement?.dismissalId().orEmpty()
            LaunchedEffect(announcementId, dismissedAnnouncementId, announcementSnoozedUntil) {
                if (announcementId == dismissedAnnouncementId) {
                    val remaining = announcementSnoozedUntil - System.currentTimeMillis()
                    if (remaining > 0L) delay(remaining)
                    if (announcementSnoozedUntil > 0L) {
                        announcementSnoozedUntil = 0L
                        SettingsStore.setLong(ANNOUNCEMENT_SNOOZED_UNTIL_KEY, 0L)
                    }
                }
            }
            val announcementSnoozed = isAnnouncementSnoozed(
                announcementId = announcementId,
                dismissedAnnouncementId = dismissedAnnouncementId,
                snoozedUntilMs = announcementSnoozedUntil,
                nowMs = System.currentTimeMillis(),
            )
            if (
                state.signedIn &&
                announcement?.enabled == true &&
                (announcement.title.isNotBlank() || announcement.message.isNotBlank()) &&
                !announcementSnoozed &&
                levelUpNotice == null &&
                state.screen !is Screen.Player &&
                state.screen !is Screen.Qris
            ) {
                EntryAnnouncementBanner(
                    announcement = announcement,
                    isTv = isTv,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            start = if (useRail) 104.dp else 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                        )
                        .widthIn(max = 780.dp)
                        .fillMaxWidth(),
                    onOpen = { openAnnouncementUrl(context, it, state) },
                    onDismiss = {
                        val snoozedUntil = System.currentTimeMillis() + ANNOUNCEMENT_SNOOZE_MS
                        dismissedAnnouncementId = announcementId
                        announcementSnoozedUntil = snoozedUntil
                        SettingsStore.setStr(DISMISSED_ANNOUNCEMENT_KEY, announcementId)
                        SettingsStore.setLong(ANNOUNCEMENT_SNOOZED_UNTIL_KEY, snoozedUntil)
                    },
                )
            }
            levelUpNotice?.let { event ->
                CultivationLevelUpNotice(event = event, onDismiss = { levelUpNotice = null })
            }
            }
        }
        if (state.premiumPromptVisible && !mandatoryUpdate) {
            PremiumPrompt(
                onSubscribe = { state.dismissPremiumPrompt(); state.openSubscription() },
                onDismiss = { state.dismissPremiumPrompt() },
            )
        }
        }
    }
}

private const val DEFAULT_UPDATE_URL = "https://tetonova.biz.id/#download"

private fun openRequiredUpdateUrl(context: Context, rawUrl: String) {
    val uri = runCatching { Uri.parse(rawUrl.ifBlank { DEFAULT_UPDATE_URL }) }.getOrNull() ?: return
    if (uri.scheme?.lowercase() !in setOf("http", "https")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private const val DISMISSED_ANNOUNCEMENT_KEY = "dismissed_announcement_id"
private const val ANNOUNCEMENT_SNOOZED_UNTIL_KEY = "announcement_snoozed_until"
internal const val ANNOUNCEMENT_SNOOZE_MS = 6L * 60L * 60L * 1000L

internal fun isAnnouncementSnoozed(
    announcementId: String,
    dismissedAnnouncementId: String,
    snoozedUntilMs: Long,
    nowMs: Long,
): Boolean =
    announcementId.isNotBlank() &&
        announcementId == dismissedAnnouncementId &&
        nowMs < snoozedUntilMs

@Composable
private fun EntryAnnouncementBanner(
    announcement: Announcement,
    isTv: Boolean,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = TnTheme.colors
    val severity = announcement.severity.lowercase()
    val accent = when (severity) {
        "critical" -> Color(0xFFE11D48)
        "warning" -> Color(0xFFF59E0B)
        else -> Color(0xFF3B82F6)
    }
    val background = when {
        severity == "critical" && c.isDark -> Color(0xFF3B111C)
        severity == "critical" -> Color(0xFFFFE4EA)
        severity == "warning" && c.isDark -> Color(0xFF352711)
        severity == "warning" -> Color(0xFFFFF4D6)
        c.isDark -> Color(0xFF142640)
        else -> Color(0xFFEAF3FF)
    }
    val shape = RoundedCornerShape(18.dp)
    val clickableModifier = if (announcement.ctaUrl.isNotBlank() && !isTv) {
        Modifier.clickable { onOpen(announcement.ctaUrl) }
    } else {
        Modifier
    }
    val closeFocusRequester = remember { FocusRequester() }
    var closeFocused by remember { mutableStateOf(false) }

    LaunchedEffect(isTv, announcement.dismissalId(), announcement.dismissible) {
        if (isTv && announcement.dismissible) {
            delay(100)
            runCatching { closeFocusRequester.requestFocus() }
        }
    }

    Row(
        modifier
            .focusGroup()
            .clip(shape)
            .background(background)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .then(clickableModifier)
            .padding(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            TnIcon("info", size = 21.dp, tint = accent)
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (announcement.title.isNotBlank()) {
                Text(
                    announcement.title,
                    color = c.ink,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                )
            }
            if (announcement.message.isNotBlank()) {
                Text(announcement.message, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (announcement.ctaUrl.isNotBlank()) {
                val ctaModifier = if (isTv) {
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(announcement.ctaUrl) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                } else {
                    Modifier
                }
                Text(
                    (announcement.ctaLabel.ifBlank { "Buka" }) + "  ›",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = ctaModifier,
                )
            }
        }

        if (announcement.dismissible) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(if (closeFocused) 4.dp else 0.dp, Color.White, RoundedCornerShape(12.dp))
                    .border(if (closeFocused) 2.dp else 0.dp, accent, RoundedCornerShape(12.dp))
                    .focusRequester(closeFocusRequester)
                    .onFocusChanged { closeFocused = it.isFocused }
                    .clickable(onClick = onDismiss)
                    .onPreviewKeyEvent { event ->
                        if (
                            isTv &&
                            event.type == KeyEventType.KeyDown &&
                            event.eventKey in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                        ) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = c.ink2, fontSize = 24.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun openAnnouncementUrl(context: Context, rawUrl: String, state: AppState) {
    val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return
    val scheme = uri.scheme?.lowercase().orEmpty()
    if (scheme !in setOf("http", "https", "tetonova")) return
    if (scheme == "tetonova") {
        when (uri.host) {
            "trial" -> {
                state.openSubscription(showPaidPlans = false)
                return
            }
            "premium" -> {
                state.openSubscription(showPaidPlans = true)
                return
            }
        }
    }
    detailArgFromDeepLink(uri)?.let {
        state.openDetail(it)
        return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

@Composable
private fun SystemBarsEffect(darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

@Composable
private fun MainShell(state: AppState, useRail: Boolean, tv: Boolean) {
    val screen = state.screen
    val panelVersion = TnData.panelVersion
    val extensionStateVersion = TnData.extStateVersion
    val subscription = TnData.subscription
    val hasInstalledExtensions = remember(panelVersion, extensionStateVersion) {
        TnData.hasInstalledExtensions()
    }
    val showPremiumUpgrade = subscription?.status != "active"
    val dest = (screen as? Screen.Tab)?.dest
    val isSettings = screen is Screen.Settings
    val isSubscription = screen is Screen.Subscription
    val navCurrent = when {
        isSettings -> NavDest.PROFILE
        isSubscription -> state.currentTab
        else -> dest
    }
    if (isSettings || isSubscription) BackHandler { state.back() }
    val showTopbar = dest in setOf(NavDest.SEARCH, NavDest.FORUM, NavDest.EXTENSIONS)

    Row(Modifier.fillMaxSize()) {
        if (useRail) {
            NavRail(current = navCurrent, tv = tv, animated = !state.lite, onSelect = state::selectTab)
        }
        Column(Modifier.fillMaxSize()) {
            if (showTopbar && dest != null) TopBar(dest.label)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current
                val contentWidthPx = with(density) { maxWidth.toPx() }
                val contentHeightPx = with(density) { maxHeight.toPx() }
                when {
                    isSettings -> SettingsScreen(state = state)
                    isSubscription -> SubscriptionScreen(
                        state = state,
                        showPaidPlans = (screen as Screen.Subscription).showPaidPlans,
                    )
                    dest != null -> TabContent(state, dest)
                }
                if (screen is Screen.Tab && hasInstalledExtensions && showPremiumUpgrade) {
                    PremiumQuickAccess(
                        state = state,
                        containerWidthPx = contentWidthPx,
                        containerHeightPx = contentHeightPx,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 14.dp),
                    )
                }
            }
            if (!useRail) BottomBar(current = navCurrent, animated = !state.lite, onSelect = state::selectTab)
        }
    }
}

private const val PREMIUM_SHORTCUT_HIDDEN_UNTIL_KEY = "premium_shortcut_hidden_until"
private const val PREMIUM_SHORTCUT_SNOOZE_MS = 15L * 60L * 1000L
private const val PREMIUM_SHORTCUT_X_KEY = "premium_shortcut_drag_x"
private const val PREMIUM_SHORTCUT_Y_KEY = "premium_shortcut_drag_y"

@Composable
private fun PremiumQuickAccess(
    state: AppState,
    containerWidthPx: Float,
    containerHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val c = TnTheme.colors
    val density = LocalDensity.current
    var hiddenUntil by remember {
        mutableStateOf(SettingsStore.getLong(PREMIUM_SHORTCUT_HIDDEN_UNTIL_KEY, 0L))
    }
    var dragX by remember { mutableStateOf(SettingsStore.getLong(PREMIUM_SHORTCUT_X_KEY, 0L).toFloat()) }
    var dragY by remember { mutableStateOf(SettingsStore.getLong(PREMIUM_SHORTCUT_Y_KEY, 0L).toFloat()) }
    val shortcutWidthPx = with(density) { 168.dp.toPx() }
    val shortcutHeightPx = with(density) { 62.dp.toPx() }
    val minDragX = (shortcutWidthPx - containerWidthPx).coerceAtMost(0f)
    val minDragY = (shortcutHeightPx - containerHeightPx).coerceAtMost(0f)
    LaunchedEffect(containerWidthPx, containerHeightPx) {
        dragX = dragX.coerceIn(minDragX, 0f)
        dragY = dragY.coerceIn(minDragY, 0f)
    }
    LaunchedEffect(hiddenUntil) {
        val remaining = hiddenUntil - System.currentTimeMillis()
        if (remaining > 0L) {
            delay(remaining)
            hiddenUntil = 0L
            SettingsStore.setLong(PREMIUM_SHORTCUT_HIDDEN_UNTIL_KEY, 0L)
        }
    }
    if (System.currentTimeMillis() < hiddenUntil) return

    val shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
    Row(
        modifier
            .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
            .pointerInput(containerWidthPx, containerHeightPx) {
                detectDragGestures(
                    onDragEnd = {
                        SettingsStore.setLong(PREMIUM_SHORTCUT_X_KEY, dragX.roundToInt().toLong())
                        SettingsStore.setLong(PREMIUM_SHORTCUT_Y_KEY, dragY.roundToInt().toLong())
                    },
                    onDragCancel = {
                        SettingsStore.setLong(PREMIUM_SHORTCUT_X_KEY, dragX.roundToInt().toLong())
                        SettingsStore.setLong(PREMIUM_SHORTCUT_Y_KEY, dragY.roundToInt().toLong())
                    },
                ) { change, amount ->
                    change.consume()
                    dragX = (dragX + amount.x).coerceIn(minDragX, 0f)
                    dragY = (dragY + amount.y).coerceIn(minDragY, 0f)
                }
            }
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .background(c.rose)
                .clickable { state.openSubscription() }
                .padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TnIcon("sparkle", size = 17.dp, tint = Color.White, filled = true)
            Text("Upgrade Premium", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        Box(
            Modifier
                .clickable {
                    hiddenUntil = System.currentTimeMillis() + PREMIUM_SHORTCUT_SNOOZE_MS
                    SettingsStore.setLong(PREMIUM_SHORTCUT_HIDDEN_UNTIL_KEY, hiddenUntil)
                }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("×", color = c.muted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TabContent(state: AppState, dest: NavDest) {
    when (dest) {
        NavDest.HOME -> HomeScreen(
            onOpenDetail = state::openDetail,
            onOpenExtensions = { state.selectTab(NavDest.EXTENSIONS) },
            onOpenSubscription = state::openSubscription,
            lite = state.lite,
            onToggleLite = { state.lite = !state.lite },
            dataSaver = state.dataSaver,
            onToggleDataSaver = { state.dataSaver = !state.dataSaver },
        )
        NavDest.SEARCH -> SearchScreen(onOpenDetail = state::openDetail)
        NavDest.MANGA -> MangaTab(onOpenManga = state::openManga)
        NavDest.FORUM -> ForumScreen()
        NavDest.EXTENSIONS -> ExtensionsScreen()
        NavDest.PROFILE -> ProfileScreen(state = state)
    }
}

@Composable
private fun TopBar(title: String) {
    val c = TnTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.bg.copy(alpha = 0.92f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
    }
}

@Composable
private fun NavRail(
    current: NavDest?,
    tv: Boolean,
    animated: Boolean,
    onSelect: (NavDest) -> Unit,
) {
    val c = TnTheme.colors
    val railShape = RoundedCornerShape(30.dp)
    Box(
        Modifier
            .width(112.dp)
            .fillMaxHeight()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(92.dp)
                .shadow(9.dp, railShape)
                .clip(railShape)
                .background(c.surface)
                .border(1.dp, c.line, railShape)
                .padding(horizontal = 7.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            navTabs.forEach { d ->
                NavRailItem(
                    icon = d.icon,
                    label = d.label,
                    selected = d == current,
                    tv = tv,
                    animated = animated,
                ) { onSelect(d) }
            }
        }
    }
}

@Composable
private fun NavRailItem(
    icon: String,
    label: String,
    selected: Boolean,
    tv: Boolean,
    animated: Boolean,
    onClick: () -> Unit,
) {
    val c = TnTheme.colors
    var focused by remember { mutableStateOf(false) }
    val targetBg = when {
        selected -> c.roseTint
        tv && focused -> c.surface2
        else -> Color.Transparent
    }
    val animatedBg by animateColorAsState(targetBg, label = "rail-bg")
    val targetFg = if (selected) c.rose else c.muted
    val animatedFg by animateColorAsState(targetFg, label = "rail-color")
    val targetScale = when {
        selected -> 1.13f
        tv && focused -> 1.08f
        else -> 1f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "rail-icon-scale",
    )
    val targetLineWidth = if (selected) 18.dp else 0.dp
    val animatedLineWidth by animateDpAsState(targetLineWidth, label = "rail-line")
    val bg = if (animated) animatedBg else targetBg
    val fg = if (animated) animatedFg else targetFg
    val scale = if (animated) animatedScale else targetScale
    val lineWidth = if (animated) animatedLineWidth else targetLineWidth
    val focusRing = if (tv && focused) c.rose else Color.Transparent
    Column(
        Modifier
            .width(76.dp)
            .height(70.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(bg)
            .border(3.dp, focusRing, RoundedCornerShape(19.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(vertical = 7.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TnIcon(
            icon,
            size = 23.dp,
            tint = fg,
            filled = selected,
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(lineWidth)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.rose),
        )
    }
}

// Tabs shown in the nav bar/rail. Manga is built but not shipped in this release — the screens still
// compile (TabContent/Screen.Manga stay), it's just unreachable. Drop the filter to bring it back.
private val navTabs = NavDest.entries.filter { it != NavDest.MANGA }

@Composable
private fun BottomBar(current: NavDest?, animated: Boolean, onSelect: (NavDest) -> Unit) {
    val c = TnTheme.colors
    val barShape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 420.dp)
                .shadow(9.dp, barShape)
                .clip(barShape)
                .background(c.surface)
                .border(width = 1.dp, color = c.line, shape = barShape)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val gap = 2.dp
            val count = navTabs.size
            val availableItemWidth = (maxWidth - gap * (count - 1)) / count
            val itemWidth = if (availableItemWidth < 66.dp) availableItemWidth else 66.dp
            val trackWidth = itemWidth * count + gap * (count - 1)
            val selectedIndex = navTabs.indexOf(current).coerceAtLeast(0)
            val targetOffset = (itemWidth + gap) * selectedIndex
            val movingOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                label = "nav-indicator",
            )

            Box(Modifier.width(trackWidth).height(62.dp)) {
                Box(
                    Modifier
                        .offset(x = if (animated) movingOffset else targetOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(17.dp))
                        .background(c.roseTint),
                )
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navTabs.forEach { d ->
                        BarItem(
                            icon = d.icon,
                            label = d.label,
                            selected = d == current,
                            animated = animated,
                            modifier = Modifier.width(itemWidth),
                        ) { onSelect(d) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BarItem(
    icon: String,
    label: String,
    selected: Boolean,
    animated: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = TnTheme.colors
    val targetFg = if (selected) c.rose else c.muted
    val animatedFg by animateColorAsState(targetFg, label = "nav-color")
    val targetScale = if (selected) 1.13f else 1f
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "nav-icon-scale",
    )
    val targetLift = if (selected) (-2).dp else 0.dp
    val animatedLift by animateDpAsState(targetLift, label = "nav-icon-lift")
    val targetLineWidth = if (selected) 18.dp else 0.dp
    val animatedLineWidth by animateDpAsState(targetLineWidth, label = "nav-line")
    val fg = if (animated) animatedFg else targetFg
    val scale = if (animated) animatedScale else targetScale
    val lift = if (animated) animatedLift else targetLift
    val lineWidth = if (animated) animatedLineWidth else targetLineWidth
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(17.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TnIcon(
            icon,
            size = 23.dp,
            tint = fg,
            filled = selected,
            modifier = Modifier.graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationY = with(LocalDensity.current) { lift.toPx() },
            ),
        )
        Spacer(Modifier.height(3.dp))
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(lineWidth)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.rose),
        )
    }
}

@Composable
fun BrandDot(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(TnTheme.colors.rose),
        contentAlignment = Alignment.Center,
    ) {
        TnIcon("sparkle", size = size * 0.42f, tint = Color.White, filled = true)
    }
}

/** Shown when a non-entitled user taps premium content (see TnData.isTitleBlocked / isPlayBlocked gates). */
@Composable
private fun PremiumPrompt(onSubscribe: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Konten Premium") },
        text = { Text("Konten ini khusus pelanggan Premium. Silakan berlangganan dulu untuk menontonnya.") },
        confirmButton = { TextButton(onClick = onSubscribe) { Text("Berlangganan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
