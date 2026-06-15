package com.tetonova.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.tetonova.app.data.SettingsStore
import com.tetonova.app.data.TnData
import com.tetonova.core.designsystem.theme.TnAccents
import com.tetonova.core.model.NavDest
import com.tetonova.core.model.PosterItem
import com.tetonova.core.model.SpotItem

/** Argument bag for the Detail screen (what `enrich()` in detail.jsx consumes). */
data class DetailArg(
    val title: String,
    val sub: String,
    val art: Int,
    val badge: String = "Donghua",
    val ep: String? = null,
    val progress: Int = 0,
    val syn: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    /** The source's web page for this title (catalog item id) — opened by "Tonton" / shared. */
    val url: String? = null,
    /** Real cover artwork scraped from the source site (shown immediately in the hero). */
    val cover: String? = null,
)

// Enriches with the real catalog entry (overview/genres/year) when the title is in the
// registry; otherwise returns a basic arg built from the poster fields.
fun PosterItem.toDetailArg(): DetailArg = TnData.detailArgFor(this)

fun SpotItem.toDetailArg(): DetailArg {
    // Live spotlight: route through the real catalog item so Detail scrapes the source page.
    if (url?.startsWith("http") == true) {
        return DetailArg(
            title = title, sub = sub, art = art, badge = tags.firstOrNull()?.label ?: "Anime",
            ep = null, progress = 0, cover = cover, url = url,
            genres = tags.map { it.label },
        )
    }
    return DetailArg(
        title = title, sub = sub, art = art,
        badge = if (tags.any { it.label.equals("Donghua", true) }) "Donghua" else "Anime",
        ep = eps.filter { it.isDigit() }.ifEmpty { null },
        progress = 0, syn = syn, rating = rating, status = status,
        genres = tags.map { it.label },
    )
}

/** The current top-level screen (mirrors the prototype's view/detail/settings state). */
sealed interface Screen {
    data class Tab(val dest: NavDest) : Screen
    data class Detail(val arg: DetailArg) : Screen
    data object Settings : Screen
    data object Report : Screen
    data object ReleaseNotes : Screen
    data object Help : Screen
}

/** Hoisted app state: navigation + user-settable theme/account toggles. */
class AppState {
    var screen by mutableStateOf<Screen>(Screen.Tab(NavDest.HOME))
        private set
    private var prevTab: NavDest = NavDest.HOME

    // Persisted settings (survive app restart via SettingsStore). signedIn stays in-memory.
    var darkTheme by persistedBool("dark_theme", false)
    var accentId by persistedString("accent_id", "rose")
    var signedIn by mutableStateOf(true)
    var lite by persistedBool("lite_mode", false)
    var quality by persistedString("quality", "auto")
    var dataSaver by persistedBool("data_saver", false)
    var autoNext by persistedBool("auto_next", true)
    var skipOp by persistedBool("skip_op", false)
    var mature by persistedBool("mature", false)
    var notifFollow by persistedBool("notif_follow", true)
    var notifForum by persistedBool("notif_forum", true)
    var pushLocal by persistedBool("push_local", true)
    var autoBackup by persistedBool("auto_backup", true)

    val accentColor: Color get() = TnAccents.firstOrNull { it.id == accentId }?.color ?: TnAccents[0].color

    fun selectTab(dest: NavDest) {
        prevTab = dest
        screen = Screen.Tab(dest)
    }

    fun openDetail(arg: DetailArg) {
        screen = Screen.Detail(arg)
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    /** Sub-screens launched from Settings; their back action returns to Settings. */
    fun openReport() { screen = Screen.Report }
    fun openReleaseNotes() { screen = Screen.ReleaseNotes }
    fun openHelp() { screen = Screen.Help }

    fun back() {
        screen = Screen.Tab(prevTab)
    }
}

@Composable
fun rememberAppState(): AppState = remember { AppState() }

/** A Compose state whose writes are also persisted to [SettingsStore]. */
private fun persistedBool(key: String, default: Boolean): MutableState<Boolean> =
    object : MutableState<Boolean> {
        private val backing = mutableStateOf(SettingsStore.getBool(key, default))
        override var value: Boolean
            get() = backing.value
            set(v) { if (v != backing.value) { backing.value = v; SettingsStore.setBool(key, v) } }
        override fun component1() = value
        override fun component2(): (Boolean) -> Unit = { value = it }
    }

private fun persistedString(key: String, default: String): MutableState<String> =
    object : MutableState<String> {
        private val backing = mutableStateOf(SettingsStore.getStr(key, default))
        override var value: String
            get() = backing.value
            set(v) { if (v != backing.value) { backing.value = v; SettingsStore.setStr(key, v) } }
        override fun component1() = value
        override fun component2(): (String) -> Unit = { value = it }
    }
