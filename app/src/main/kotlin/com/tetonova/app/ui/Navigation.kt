package com.tetonova.app.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.tetonova.app.data.AuthManager
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

private const val APP_LINK_SCHEME = "tetonova"
private const val APP_LINK_HOST = "detail"
private const val WEB_LINK_HOST = "tetonova.app"

fun detailDeepLink(
    title: String,
    url: String?,
    cover: String?,
    badge: String,
    sub: String,
    ep: Int?,
    genres: List<String>,
): String = detailDeepLinkUri(title, url, cover, badge, sub, ep, genres).toString()

fun detailWebLink(
    baseUrl: String,
    title: String,
    url: String?,
    cover: String?,
    badge: String,
    sub: String,
    ep: Int?,
    genres: List<String>,
): String {
    val base = baseUrl.trim().trimEnd('/').ifBlank {
        return detailDeepLink(title, url, cover, badge, sub, ep, genres)
    }
    return Uri.parse("$base/detail").buildUpon()
        .appendQueryParameter("title", title)
        .appendQueryParameter("url", url.orEmpty())
        .appendQueryParameter("cover", cover.orEmpty())
        .appendQueryParameter("badge", badge)
        .appendQueryParameter("sub", sub)
        .appendQueryParameter("ep", ep?.takeIf { it > 0 }?.toString().orEmpty())
        .appendQueryParameter("genres", genres.joinToString("|"))
        .build()
        .toString()
}

fun detailDeepLinkUri(
    title: String = "",
    url: String?,
    cover: String? = null,
    badge: String = "Anime",
    sub: String = "",
    ep: Int? = null,
    genres: List<String> = emptyList(),
): Uri = Uri.Builder()
    .scheme(APP_LINK_SCHEME)
    .authority(APP_LINK_HOST)
    .appendQueryParameter("title", title)
    .appendQueryParameter("url", url.orEmpty())
    .appendQueryParameter("cover", cover.orEmpty())
    .appendQueryParameter("badge", badge)
    .appendQueryParameter("sub", sub)
    .appendQueryParameter("ep", ep?.takeIf { it > 0 }?.toString().orEmpty())
    .appendQueryParameter("genres", genres.joinToString("|"))
    .build()

fun detailArgFromDeepLink(uri: Uri?): DetailArg? {
    if (uri == null) return null
    val custom = uri.scheme == APP_LINK_SCHEME && uri.host == APP_LINK_HOST
    val web = uri.scheme in setOf("http", "https") && uri.host == WEB_LINK_HOST && uri.path == "/detail"
    if (!custom && !web) return null
    val title = uri.getQueryParameter("title").orEmpty()
    // The deep link is a public, browser-reachable entry point — a hostile page can craft any `url`.
    // Only let http/https through so it can't drive an on-device fetch of a file://, intent:, or
    // javascript: target downstream. ponytail: scheme allowlist; add a source-host allowlist if
    // open-redirect into the scraper becomes a concern.
    val rawUrl = uri.getQueryParameter("url").orEmpty()
    val url = rawUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
    if (title.isBlank() && url.isBlank()) return null
    return DetailArg(
        title = title.ifBlank { "TetoNova" },
        sub = uri.getQueryParameter("sub").orEmpty(),
        art = url.hashCode().let { ((it % 8) + 8) % 8 },
        badge = uri.getQueryParameter("badge")?.takeIf { it.isNotBlank() } ?: "Anime",
        ep = uri.getQueryParameter("ep")?.takeIf { it.isNotBlank() },
        genres = uri.getQueryParameter("genres")?.split("|")?.filter { it.isNotBlank() }.orEmpty(),
        url = url.takeIf { it.isNotBlank() },
        cover = uri.getQueryParameter("cover")?.takeIf { it.isNotBlank() },
    )
}

/** Argument bag for the native QRIS checkout screen (one pending VioletMediaPay order). */
data class QrisArg(
    val orderId: String,
    /** VioletMediaPay QR image URL (`target`) — loaded directly in the QRIS screen. */
    val qrImageUrl: String,
    /** Hosted checkout page (`checkout_url`) — fallback if the QR image can't load. */
    val checkoutUrl: String,
    /** Base subscription price. */
    val amountIdr: Long,
    val adminFeeIdr: Long,
    val taxIdr: Long,
    /** Final amount encoded in the QR and paid by the user. */
    val totalIdr: Long,
    /** ISO-8601 UTC (`…Z`) expiry — the screen counts down to this. */
    val expiresAt: String,
    /** Plan code + display name, so the screen can label the amount and re-issue on expiry. */
    val planCode: String,
    val planName: String,
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
    /** Hint that this is a vertical short-drama (portrait micro-episode) — the player locks portrait
     *  instead of the default landscape. Confirmed/corrected at runtime from the real video aspect ratio. */
    val vertical: Boolean = false,
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

/** Argument bag for the manga series detail (image-based vertical — no video player). */
data class MangaArg(val id: String, val title: String, val cover: String?)

/** Argument bag for the manga reader. `chapterId` is the only thing that changes on prev/next. */
data class ReaderArg(val chapterId: String, val mangaTitle: String)

/** The current top-level screen (mirrors the prototype's view/detail/settings state). */
sealed interface Screen {
    data class Tab(val dest: NavDest) : Screen
    data class Detail(val arg: DetailArg) : Screen
    data class Player(val arg: PlayerArg) : Screen
    data class Manga(val arg: MangaArg) : Screen
    data class Reader(val arg: ReaderArg) : Screen
    data object Settings : Screen
    data class Subscription(val showPaidPlans: Boolean = false) : Screen
    data class Qris(val arg: QrisArg) : Screen
    data object Report : Screen
    data object ReleaseNotes : Screen
    data object Help : Screen
    data object Library : Screen
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

    // Persisted settings (survive app restart via SettingsStore).
    /** system = follow the device on every launch/config change; light/dark are manual overrides. */
    var themeMode by persistedString("theme_mode", "system")
    var accentId by persistedString("accent_id", "rose")
    // Real account state — reflects the Firebase/Google session (snapshot-backed → reactive).
    val signedIn: Boolean get() = AuthManager.signedIn
    fun signOut() = AuthManager.signOut()
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
    var pushLocal by persistedBool("push_local", false)
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

    // Set when a non-entitled user taps premium content — TetoNovaRoot shows a "subscribe" prompt
    // instead of opening it. The hard gate that guarantees premium content stays unplayable.
    var premiumPromptVisible by mutableStateOf(false)
        private set
    fun dismissPremiumPrompt() { premiumPromptVisible = false }

    fun openDetail(arg: DetailArg) {
        if (TnData.isTitleBlocked(arg.url)) { premiumPromptVisible = true; return }
        // Feed the cross-user "Trending minggu ini" rail: report real content opens (those with a
        // live source URL) to the panel. Best-effort + no-op when the panel/token isn't available.
        TnData.reportOpen(arg.title, arg.url, arg.cover, arg.badge)
        screen = Screen.Detail(arg)
    }

    fun openPlayer(arg: PlayerArg) {
        // Defense-in-depth: even if premium content somehow reached a play button, block it here.
        // Short-drama is per-episode: only episodes past the free teaser window are blocked.
        if (TnData.isPlayBlocked(arg.url, arg.episodeNum, arg.playlist.size)) { premiumPromptVisible = true; return }
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

    // Manga vertical (image-based). Detail opens from the Manga tab; the reader opens from detail and
    // remembers it so closing the reader returns to the same series page (like beforePlayer).
    private var beforeReader: Screen? = null
    fun openManga(arg: MangaArg) { screen = Screen.Manga(arg) }
    fun openReader(arg: ReaderArg) {
        if (screen !is Screen.Reader) beforeReader = screen
        screen = Screen.Reader(arg)
    }
    fun closeReader() {
        screen = beforeReader ?: Screen.Tab(NavDest.MANGA)
        beforeReader = null
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    fun openSubscription(showPaidPlans: Boolean = false) {
        screen = Screen.Subscription(showPaidPlans)
    }

    val currentTab: NavDest get() = prevTab

    /** Full Library (History / Followed / Downloads) opened from the Profile lane. */
    fun openLibrary() {
        screen = Screen.Library
    }

    /** Native QRIS checkout launched from the Langganan panel. */
    fun openQris(arg: QrisArg) { screen = Screen.Qris(arg) }

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
