package com.tetonova.app.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.tetonova.app.feature.billing.QrisCheckoutScreen
import com.tetonova.app.feature.detail.DetailScreen
import com.tetonova.app.feature.downloads.DownloadsScreen
import com.tetonova.app.feature.extensions.ExtensionsScreen
import com.tetonova.app.feature.forum.ForumScreen
import com.tetonova.app.feature.home.HomeScreen
import com.tetonova.app.feature.landing.LandingScreen
import com.tetonova.app.feature.library.FullLibraryScreen
import com.tetonova.app.feature.player.PlayerScreen
import com.tetonova.app.feature.profile.ProfileScreen
import com.tetonova.app.feature.search.SearchScreen
import com.tetonova.app.feature.settings.HelpScreen
import com.tetonova.app.feature.settings.ReleaseNotesScreen
import com.tetonova.app.feature.settings.ReportScreen
import com.tetonova.app.feature.settings.SettingsScreen
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TetoNovaTheme
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.model.NavDest

internal fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun TetoNovaRoot(windowSizeClass: WindowSizeClass, debugPlayerUrl: String? = null, deepLink: Uri? = null) {
    val state = rememberAppState()
    val context = LocalContext.current
    val isTv = remember { isTelevision(context) }
    val useRail = isTv || windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // One-shot merge of control-panel source overrides. No-ops when no panel URL is configured
    // or the host is unreachable, so the app keeps running on the bundled registry (local-first).
    LaunchedEffect(Unit) {
        com.tetonova.app.data.TnData.warmPanel(com.tetonova.app.Secrets.controlPanelUrl)
    }
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            state.finishLanding()
            com.tetonova.app.data.TnData.refreshSubscription()
            com.tetonova.app.data.TnData.warmPanel(com.tetonova.app.Secrets.controlPanelUrl, force = true)
        }
    }
    LaunchedEffect(debugPlayerUrl) {
        if (!debugPlayerUrl.isNullOrBlank()) {
            state.finishLanding()
            state.openPlayer(PlayerArg(title = "Smoke Test", url = debugPlayerUrl, episodeLabel = "URL test", badge = "Anime"))
        }
    }
    LaunchedEffect(deepLink?.toString()) {
        detailArgFromDeepLink(deepLink)?.let {
            state.finishLanding()
            state.openDetail(it)
        }
    }

    TetoNovaTheme(darkTheme = state.darkTheme, accent = state.accentColor) {
        val c = TnTheme.colors
        SystemBarsEffect(darkTheme = state.darkTheme)
        // Retain the tab shell's UI state (Home scroll position, etc.) while Detail is on top, so
        // returning lands back on the exact rail/extension the user left — not scrolled to the top.
        val shellState = rememberSaveableStateHolder()
        val rootModifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .let { if (state.screen is Screen.Player) it else it.windowInsetsPadding(WindowInsets.statusBars) }
        // TV: swap the default (touch) ripple for a rose focus ring so D-pad users can see where they are —
        // every plain `.clickable {}` in the app picks this up. Phone/tablet keep the ripple untouched.
        // Rose (the app accent) reads on both the dark hero and the light episode list; white vanished there.
        val tvIndication = remember(c.rose) { TvFocusIndication(c.rose) }
        val focusIndication = if (isTv) tvIndication else LocalIndication.current
        CompositionLocalProvider(LocalIndication provides focusIndication) {
        Box(rootModifier) {
            if (!state.signedIn && !state.landingDone) {
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
                    BackHandler { state.openSettings() }
                    QrisCheckoutScreen(initial = scr.arg, onClose = { state.openSettings() })
                }
                is Screen.Library -> {
                    BackHandler { state.back() }
                    FullLibraryScreen(onBack = { state.back() }, onOpenDetail = state::openDetail)
                }
                else -> shellState.SaveableStateProvider("tab-shell") {
                    MainShell(state = state, useRail = useRail, tv = isTv)
                }
            }
        }
        if (state.premiumPromptVisible) {
            PremiumPrompt(
                onSubscribe = { state.dismissPremiumPrompt(); state.openSettings() },
                onDismiss = { state.dismissPremiumPrompt() },
            )
        }
        }
    }
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
    val dest = (screen as? Screen.Tab)?.dest
    val isSettings = screen is Screen.Settings
    if (isSettings) BackHandler { state.back() }
    val showTopbar = dest in setOf(NavDest.SEARCH, NavDest.FORUM, NavDest.DOWNLOADS, NavDest.EXTENSIONS)

    Row(Modifier.fillMaxSize()) {
        if (useRail) {
            NavRail(current = dest, settingsSelected = isSettings, tv = tv, onSelect = state::selectTab, onSettings = state::openSettings)
        }
        Column(Modifier.fillMaxSize()) {
            if (showTopbar && dest != null) TopBar(dest.label)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isSettings -> SettingsScreen(state = state, onBack = { state.back() })
                    dest != null -> TabContent(state, dest)
                }
            }
            if (!useRail) BottomBar(current = dest, settingsSelected = isSettings, onSelect = state::selectTab, onSettings = state::openSettings)
        }
    }
}

@Composable
private fun TabContent(state: AppState, dest: NavDest) {
    when (dest) {
        NavDest.HOME -> HomeScreen(
            onOpenDetail = state::openDetail,
            lite = state.lite,
            onToggleLite = { state.lite = !state.lite },
            dataSaver = state.dataSaver,
            onToggleDataSaver = { state.dataSaver = !state.dataSaver },
        )
        NavDest.SEARCH -> SearchScreen(onOpenDetail = state::openDetail)
        NavDest.FORUM -> ForumScreen()
        NavDest.DOWNLOADS -> DownloadsScreen(onOpenDetail = state::openDetail, onOpenPlayer = state::openPlayer)
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
    settingsSelected: Boolean,
    tv: Boolean,
    onSelect: (NavDest) -> Unit,
    onSettings: () -> Unit,
) {
    val c = TnTheme.colors
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(c.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavDest.entries.forEach { d ->
            NavRailItem(icon = d.icon, label = d.label, selected = d == current, tv = tv) { onSelect(d) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(36.dp).height(1.dp).background(c.line))
        Spacer(Modifier.height(8.dp))
        NavRailItem(icon = "gear", label = "Settings", selected = settingsSelected, tv = tv, onClick = onSettings)
    }
}

@Composable
private fun NavRailItem(icon: String, label: String, selected: Boolean, tv: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    var focused by remember { mutableStateOf(false) }
    val bg = if (selected) c.roseSoft else Color.Transparent
    val fg = if (selected) c.roseDeep else c.muted
    val focusRing = if (tv && focused) c.rose else Color.Transparent
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(3.dp, focusRing, RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        TnIcon(icon, size = 22.dp, tint = fg, filled = selected)
        Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BottomBar(current: NavDest?, settingsSelected: Boolean, onSelect: (NavDest) -> Unit, onSettings: () -> Unit) {
    val c = TnTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .border(width = 1.dp, color = c.line, shape = RoundedCornerShape(0.dp))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavDest.entries.forEach { d -> BarItem(d.icon, d.label, d == current) { onSelect(d) } }
        BarItem("gear", "Settings", settingsSelected, onSettings)
    }
}

@Composable
private fun RowScope.BarItem(icon: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    val fg = if (selected) c.rose else c.muted
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TnIcon(icon, size = 20.dp, tint = fg, filled = selected)
        Text(label, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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

/** Shown when a non-entitled user taps premium content (see AppState.isPremiumBlocked gate). */
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
