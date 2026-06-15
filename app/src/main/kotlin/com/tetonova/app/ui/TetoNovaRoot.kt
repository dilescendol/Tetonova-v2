package com.tetonova.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.feature.detail.DetailScreen
import com.tetonova.app.feature.downloads.DownloadsScreen
import com.tetonova.app.feature.extensions.ExtensionsScreen
import com.tetonova.app.feature.forum.ForumScreen
import com.tetonova.app.feature.home.HomeScreen
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

private fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

@Composable
fun TetoNovaRoot(windowSizeClass: WindowSizeClass) {
    val state = rememberAppState()
    val context = LocalContext.current
    val isTv = remember { isTelevision(context) }
    val useRail = isTv || windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact

    // One-shot merge of control-panel source overrides. No-ops when no panel URL is configured
    // or the host is unreachable, so the app keeps running on the bundled registry (local-first).
    LaunchedEffect(Unit) {
        runCatching { com.tetonova.app.data.TnData.refreshFromPanel(com.tetonova.app.BuildConfig.TETONOVA_CONTROL_PANEL_URL) }
    }

    TetoNovaTheme(darkTheme = state.darkTheme, accent = state.accentColor) {
        val c = TnTheme.colors
        // Retain the tab shell's UI state (Home scroll position, etc.) while Detail is on top, so
        // returning lands back on the exact rail/extension the user left — not scrolled to the top.
        val shellState = rememberSaveableStateHolder()
        Box(Modifier.fillMaxSize().background(c.bg)) {
            when (val scr = state.screen) {
                is Screen.Detail -> {
                    BackHandler { state.back() }
                    DetailScreen(arg = scr.arg, onBack = { state.back() }, onOpenDetail = state::openDetail)
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
                else -> shellState.SaveableStateProvider("tab-shell") {
                    MainShell(state = state, useRail = useRail, tv = isTv)
                }
            }
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
        NavDest.DOWNLOADS -> DownloadsScreen(onOpenDetail = state::openDetail)
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
        BrandDot(size = 40.dp)
        Spacer(Modifier.height(12.dp))
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
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .then(if (tv && focused) Modifier.border(3.dp, c.rose, RoundedCornerShape(18.dp)) else Modifier)
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
