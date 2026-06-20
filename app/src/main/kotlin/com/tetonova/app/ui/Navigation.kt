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

/** One entry in the player's episode list — enough to advance to the next episode in-place. */
data class EpRef(val num: Int, val label: String, val url: String?)

/** Argument bag for the in-app player. `url` is the episode's source URL (a direct stream plays
 *  in-app; a watch page is offered to an external player). */
data class PlayerArg(
    val title: String,
    val url: String?,
    val episodeLabel: String? = null,
    /** The episode number being played — remembered so Detail resumes on it after the player closes. */
    val episodeNum: Int? = null,
    /** Content type from Detail. "Donghua" stays on the manual skip button (no MAL/AniSkip timing). */
    val badge: String = "Anime",
    /** MyAnimeList id (>0 only for matched anime) — the key for AniSkip OP/ED timestamps. */
    val malId: Int = 0,
    /** Ordered episode list, so the player can auto-advance to the next one. */
    val playlist: List<EpRef> = emptyList(),
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
    data class Player(val arg: PlayerArg) : Screen
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
    // The screen the player was launched from (usually Detail), so closing the player returns there.
    private var beforePlayer: Screen? = null
    // Last episode opened in the player, keyed by the Detail it was launched from. DetailScreen leaves
    // composition while the player is on top, so its `current` state is rebuilt fresh on return — this
    // lets it resume on the episode the user actually played instead of resetting to episode 1.
    private var resumeKey: String? = null
    var resumeEpisode: Int? = null
        private set

    // Persisted settings (survive app restart via SettingsStore). signedIn stays in-memory.
    var darkTheme by persistedBool("dark_theme", false)
    var accentId by persistedString("accent_id", "rose")
    var signedIn by mutableStateOf(true)
    var lite by persistedBool("lite_mode", false)
    // TODO(player): playback settings below aren't consumed yet (playback is external via Intent.ACTION_VIEW).
    //   When the in-app Media3 player is built, wire these — see memory `player-spec` for the full design:
    //   - quality: Auto = highest variant then step-down 4K→1080p→720p→480p→360p; fixed = that reso at first play.
    //   - autoNext: on video end, go to next episode.  skipOp: skip intro/ending (needs timestamps; interim = manual button).
    //   - dataSaver: player picks a lighter variant (non-player interim: lighter images + less-frequent live refresh).
    //   Player UX: tap-1× toggles controls (not exit to Detail); double-tap L/R = ∓10s; overflow = [Resolusi]+[Source video]
    //   with host→resolution drill-down; subtitle sidecar .srt/.ass. Needs multi-host/variant sources (LiveEpisode = 1 url).
    var quality by persistedString("quality", "auto")
    var dataSaver by persistedBool("data_saver", false)
    var autoNext by persistedBool("auto_next", true)
    var skipOp by persistedBool("skip_op", false)
    var mature by persistedBool("mature", false)
    var notifFollow by persistedBool("notif_follow", true)
    var notifForum by persistedBool("notif_forum", true)
    var pushLocal by persistedBool("push_local", true)
    var autoBackup by persistedBool("auto_backup", true)
    // Offline downloads: a single resolution cap applied to every download (no per-episode picker),
    // and whether to defer downloads off metered (cellular) connections.
    var downloadQuality by persistedString("download_quality", "auto")
    var downloadWifiOnly by persistedBool("download_wifi_only", false)

    val accentColor: Color get() = TnAccents.firstOrNull { it.id == accentId }?.color ?: TnAccents[0].color

    fun selectTab(dest: NavDest) {
        prevTab = dest
        screen = Screen.Tab(dest)
    }

    fun openDetail(arg: DetailArg) {
        // Feed the cross-user "Trending minggu ini" rail: report real content opens (those with a
        // live source URL) to the panel. Best-effort + no-op when the panel/token isn't available.
        TnData.reportOpen(arg.title, arg.url, arg.cover, arg.badge)
        screen = Screen.Detail(arg)
    }

    fun openPlayer(arg: PlayerArg) {
        // Opening fresh (from Detail) records where to return; advancing within the player (auto-next
        // re-calls this with the next episode) keeps that origin and just moves the resume forward.
        if (screen !is Screen.Player) beforePlayer = screen
        (screen as? Screen.Detail)?.let { resumeKey = detailKey(it.arg) }
        resumeEpisode = arg.episodeNum
        screen = Screen.Player(arg)
    }

    /** Identity of a Detail (its source URL, else title) — keys [resumeEpisode] so the resume never
     *  bleeds onto a different title. */
    private fun detailKey(arg: DetailArg): String = arg.url ?: arg.title

    /** The episode to resume on for [arg], or null when the player wasn't last opened from it. */
    fun resumeEpisodeFor(arg: DetailArg): Int? =
        resumeEpisode?.takeIf { resumeKey != null && resumeKey == detailKey(arg) }

    fun closePlayer() {
        screen = beforePlayer ?: Screen.Tab(prevTab)
        beforePlayer = null
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
