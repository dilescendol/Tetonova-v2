package com.tetonova.app.data

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tetonova.app.BuildConfig
import com.tetonova.app.ui.DetailArg
import com.tetonova.core.model.ExtCategory
import com.tetonova.core.model.ExtItem
import com.tetonova.core.model.PosterItem
import com.tetonova.core.model.SampleData
import com.tetonova.core.model.SpotItem
import com.tetonova.core.model.SpotTag
import com.tetonova.core.scraper.LiveClient
import com.tetonova.core.scraper.LiveDetail
import com.tetonova.core.scraper.LiveItem
import com.tetonova.core.scraper.LivePage
import com.tetonova.core.scraper.LiveSource
import com.tetonova.core.scraper.VideoServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.Locale
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A Home row: custom panel label + the source it came from + that source's posters. */
data class HomeSection(val label: String, val sourceId: String, val url: String, val posters: List<PosterItem>)

/** A search result group: one extension (source) + the posters it returned for the query. */
data class SearchGroup(val sourceId: String, val displayName: String, val posters: List<PosterItem>)

/** A Home source-filter chip. `id == null` is the "Semua" (all sources) chip. */
data class HomeSourceChip(val id: String?, val label: String)

private val NUMBERED_PAGE = Regex("([?&]page=)(\\d+)", RegexOption.IGNORE_CASE)
private val PATH_PAGE = Regex("(/page/)(\\d+)(?=/?(?:[?#]|$))", RegexOption.IGNORE_CASE)

/** Common fallback for APIs that page with `?page=N` but omit next-page metadata. */
internal fun nextNumberedPageUrl(url: String, hasItems: Boolean): String? {
    if (!hasItems) return null
    val match = NUMBERED_PAGE.find(url) ?: return null
    val page = match.groupValues[2].toLongOrNull()?.takeIf { it < Long.MAX_VALUE } ?: return null
    return url.replaceRange(match.groups[2]!!.range, (page + 1).toString())
}

/**
 * A few catalog providers omit rel=next (and the panel cache historically omitted nextUrl), even
 * though their paging URL is deterministic. Keep this deliberately limited to the affected
 * providers so a one-page rail from an unrelated source does not grow a fake page-2 request.
 */
internal fun nextKnownCatalogPageUrl(url: String, sourceId: String, hasItems: Boolean): String? {
    if (!hasItems) return null
    nextNumberedPageUrl(url, true)?.let { return it }
    PATH_PAGE.find(url)?.let { match ->
        val page = match.groupValues[2].toLongOrNull()?.takeIf { it < Long.MAX_VALUE } ?: return null
        return url.replaceRange(match.groups[2]!!.range, (page + 1).toString())
    }

    val identity = "$sourceId $url".lowercase(Locale.ROOT)
    val isKnownPagedCatalog = listOf(
        "kuramanime", "lk21", "nontondrama", "nontonanime", "oploverz", "pusatfilm", "samehadaku",
    ).any { it in identity }
    if (!isKnownPagedCatalog) return null

    // Kuramanime's quick routes and Oploverz's JSON adapter page through a query parameter.
    if ("kuramanime" in identity || "oploverz" in identity) {
        val fragment = url.substringAfter('#', "").takeIf { '#' in url }
        val base = url.substringBefore('#')
        val separator = if ('?' in base) '&' else '?'
        return "$base${separator}page=2" + fragment?.let { "#$it" }.orEmpty()
    }

    // LK21/NontonDrama use /page/N routes; the remaining affected sites are WordPress catalogs.
    val fragment = url.substringAfter('#', "").takeIf { '#' in url }
    val withoutFragment = url.substringBefore('#')
    val query = withoutFragment.substringAfter('?', "").takeIf { '?' in withoutFragment }
    val path = withoutFragment.substringBefore('?').trimEnd('/')
    return "$path/page/2/" + query?.let { "?$it" }.orEmpty() + fragment?.let { "#$it" }.orEmpty()
}

/** Server-side search (premium proxy or a native JSON search API) is already relevant — never
 *  re-filter its hits by title (localized titles won't literally contain the typed query). Only
 *  generic WordPress `/?s=` hits, which may echo a homepage, go through the title matcher. */
internal fun trustSearchResults(premium: Boolean, apiBaseUrl: String): Boolean =
    premium || LiveSource.hasNativeSearch(apiBaseUrl)

/**
 * App-facing data facade. The **control panel** (`/api/v1/sources`) is the source of truth for
 * which sources exist, their display names/categories and their Home-section links; the bundled
 * repo-tn catalogs supply the actual titles per source (the live catalog proxy is currently down).
 * Everything falls back to [SampleData] / the bundled registry when the panel is unreachable.
 */
object TnData {

    private var registry: ExtensionRegistry? = null
    private val panelCacheJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private const val PANEL_CACHE_BASE = "panel_sources_last_good_base"
    private const val PANEL_CACHE_JSON = "panel_sources_last_good_json"
    private const val HOME_CACHE_WAIT_MS = 2_500L
    // Upper bound only: the `finally` in startLiveSearch turns loading off earlier once ALL sources
    // finish, so fast searches stay instant. This cap just gives slow short-drama JSON APIs room to land.
    private const val SEARCH_SETTLE_TIMEOUT_MS = 25_000L
    private const val GOODBOS_ACCESS_CODE = "A49C5D6FED6BD029B18F525D8A8B0F5E"

    fun init(context: Context) {
        if (registry != null) return
        registry = ExtensionRegistry(context.applicationContext).apply { load() }
        // Wire the Android-specific bypass hooks into the pure-JVM LiveClient: a WebView-backed
        // challenge solver (Turnstile/verify_human/kuramadrive) + a CookieManager-backed jar so
        // clearance cookies are reused by OkHttp.
        LiveClient.cookieJar = WebViewCookieJar
        LiveClient.challengeSolver = WebViewChallengeSolver(context.applicationContext)
    }

    // ---------------- panel state ----------------

    private var panelSources: List<SourceOverride> = emptyList()
    /** Panel base URL (for [CatalogApi] cache reads); set on every [refreshFromPanel]. */
    private var panelBase: String = ""
    internal fun telemetryPanelBase(): String = panelBase
    private var panelRefreshJob: Job? = null

    /** Support/donation card config from the panel (null until fetched). */
    var supportMe: SupportMe? = null
        private set

    /** Global entry banner published by the panel. */
    var announcement by mutableStateOf<Announcement?>(null)
        private set

    /** Telemetry ingest token from the panel â€” reused to auth the in-app problem report. */
    var telemetryToken: String = ""
        private set

    /** Changelog published by the panel (null until fetched); shown at Settings â†’ Catatan rilis. */
    var releaseNotes by mutableStateOf<ReleaseNotes?>(null)
        private set

    /** Help-center content published by the panel; shown at Settings â†’ Pusat bantuan. */
    var helpCenter by mutableStateOf<HelpCenter?>(null)
        private set

    /** Premium trial duration published by the panel. Defaults to the original 1-hour design. */
    var trialDurationSeconds by mutableStateOf(TrialStore.DEFAULT_DURATION_MS / 1000L)
        private set

    fun trialDurationMs(): Long = trialDurationSeconds.coerceIn(0L, 31_536_000L) * 1000L

    /** Top search terms (last 7 days, cross-user) from the panel; chips on Search. Empty until fetched. */
    var popularSearches by mutableStateOf<List<String>>(emptyList())
        private set

    /** Most-opened content this week (cross-user) from the panel; the Search "Trending minggu ini" rail. */
    var trendingPosters by mutableStateOf<List<PosterItem>>(emptyList())
        private set

    /** Anonymous per-install id, used to auth/dedup telemetry the same way as the report flow. */
    private val installId: String get() = SettingsStore.installId()

    /** Snapshot-state counter so panel-dependent UI recomposes after a fetch. */
    var panelVersion by mutableStateOf(0)
        private set

    // ---------------- Extension install state (client-side) ----------------
    // Debug auto-installs every source (default = true) so the app is usable out of the box; release
    // ships with nothing installed (default = false) — the user installs each extension manually. The
    // per-source default is BuildConfig.DEBUG; explicit install/uninstall actions override it. Bumping
    // extStateVersion recomposes the Extensions/Home/Search screens (same pattern as panelVersion).
    var extStateVersion by mutableStateOf(0)
        private set

    private const val PREF_EXT_INSTALLED = "ext_installed"
    private const val PREF_EXT_UNINSTALLED = "ext_uninstalled"

    private fun extIdSet(key: String): MutableSet<String> =
        SettingsStore.getStr(key, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

    /** True when [sourceId] is installed. Explicit user choice wins; otherwise the build default. */
    fun isExtInstalled(sourceId: String): Boolean = when (sourceId) {
        in extIdSet(PREF_EXT_UNINSTALLED) -> false
        in extIdSet(PREF_EXT_INSTALLED) -> true
        else -> BuildConfig.DEBUG
    }

    /** True after the user has at least one usable extension installed. The explicit preference
     *  fallback keeps first-run UI accurate while the panel/registry is still loading. */
    fun hasInstalledExtensions(): Boolean {
        val candidates = when {
            hasPanel -> catalogSources().map { it.sourceId }
            registry?.extensions?.isNotEmpty() == true -> registry!!.extensions.map { it.id }
            else -> emptyList()
        }
        return if (candidates.isNotEmpty()) {
            candidates.any(::isExtInstalled)
        } else {
            extIdSet(PREF_EXT_INSTALLED).isNotEmpty()
        }
    }

    /** Install/uninstall a source; persists across both override sets and recomposes dependent UI. */
    fun setExtInstalled(sourceId: String, install: Boolean) {
        if (sourceId.isBlank()) return
        val installed = extIdSet(PREF_EXT_INSTALLED)
        val uninstalled = extIdSet(PREF_EXT_UNINSTALLED)
        if (install) { installed.add(sourceId); uninstalled.remove(sourceId) }
        else { uninstalled.add(sourceId); installed.remove(sourceId) }
        SettingsStore.setStr(PREF_EXT_INSTALLED, installed.joinToString(","))
        SettingsStore.setStr(PREF_EXT_UNINSTALLED, uninstalled.joinToString(","))
        extStateVersion++
    }

    /** Called when the Settings 18+ toggle flips, so mature-gated source lists re-filter instantly. */
    fun onMatureChanged() { extStateVersion++ }

    fun warmPanel(panelUrl: String, force: Boolean = false) {
        val url = panelUrl.trim()
        if (url.isBlank()) return
        val current = panelRefreshJob
        if (!force && current?.isActive == true) return
        panelRefreshJob = liveScope.launch {
            runCatching { refreshFromPanel(url) }
        }
    }

    // ---------------- cultivation XP / realm (watch telemetry â†’ server XP engine) ----------------

    /** The user's live XP/level/streak/realm + 30-day stats from the panel (null until fetched). */
    var userXp by mutableStateOf<UserXp?>(null)
        private set

    /** The cultivation realm ladder for the "Jalan Kultivasi" track (empty until fetched). */
    var realms by mutableStateOf<List<RealmTier>>(emptyList())
        private set

    /** One-shot level-up event for the breakthrough toast; the UI reads it then clears it to null. */
    var breakthrough by mutableStateOf<BreakthroughEvent?>(null)

    data class BreakthroughEvent(val level: Int, val realmName: String, val realmChanged: Boolean)

    /** Cultivation achievements (Pencapaian); server evaluates + persists unlocks on fetch. */
    var achievements by mutableStateOf<List<Achievement>>(emptyList())
        private set

    /** Firebase ID token when signed in â†’ cultivation is owned by ACCOUNT (acct:uid); null = anon install. */
    private suspend fun cultivationBearer(): String? = if (AuthManager.signedIn) AuthManager.idToken() else null

    /** Refresh achievements (best-effort) â€” called when Profile opens (eval is on-read server-side). */
    suspend fun refreshAchievements() {
        if (panelBase.isBlank() || !AuthManager.signedIn) return
        val b = cultivationBearer()
        if (telemetryToken.isBlank() && b == null) return
        UserApi(panelBase).fetchAchievements(installId, telemetryToken, b)?.let { achievements = it }
    }

    /** Equip a reached realm's frame on the avatar, then refresh XP so the hero reflects it. */
    fun equipFrame(realmId: String) {
        if (panelBase.isBlank() || !AuthManager.signedIn) return
        liveScope.launch {
            val b = cultivationBearer()
            if (telemetryToken.isBlank() && b == null) return@launch
            if (UserApi(panelBase).equipFrame(installId, realmId, telemetryToken, b)) refreshUserXp()
        }
    }

    /** On sign-in: absorb this device's anonymous progress into the account once, then refresh. */
    suspend fun onSignedIn() {
        if (panelBase.isBlank()) return
        val b = AuthManager.idToken() ?: return
        LiveSource.configurePremiumProxy(panelBase, b)
        val uid = AuthManager.user?.uid ?: return
        val flag = "cult_merged_$uid"
        if (!SettingsStore.getBool(flag, false)) {
            if (UserApi(panelBase).mergeCultivation(installId, b)) SettingsStore.setBool(flag, true)
        }
        refreshUserXp(); refreshAchievements()
    }

    /** On sign-out: clear account-only state; signed-out users never touch customer telemetry. */
    fun onSignedOutRefresh() {
        LiveSource.configurePremiumProxy("", "")
        subscription = null
        userXp = null
        achievements = emptyList()
        panelVersion++
    }

    suspend fun refreshFromPanel(panelUrl: String) {
        panelBase = panelUrl.trim().trimEnd('/')
        preparePremiumProxy()
        restoreCachedPanelSnapshot(panelBase)
        restoreBundledSourceSnapshot()
        val resp = SourceApi(panelUrl).fetch()
        android.util.Log.d("TnPanel", "refreshFromPanel($panelUrl): ${resp?.sources?.size ?: "NULL"} sources, flare=${resp?.proxyBypass?.flareSolverrEndpoint?.ifBlank { "blank" } ?: "none"}")
        if (resp == null) return
        applyPanelSnapshot(resp)
        cachePanelSnapshot(panelBase, resp)
        // Hand the Cloudflare-bypass config to the live scraper so gated upstreams can be solved.
        resp.proxyBypass?.takeIf { it.enabled && it.flareSolverrEndpoint.isNotBlank() }?.let {
            LiveClient.flareEndpoint = it.flareSolverrEndpoint
            LiveClient.flareToken = it.flareSolverrToken
        }
        if (resp.sources.isNotEmpty() || resp.supportMe != null) {
            panelVersion++
            primeHomeSections()
        }
        refreshTrending()
        if (realms.isEmpty()) UserApi(panelBase).fetchRealms()?.takeIf { it.isNotEmpty() }?.let { realms = it }
        refreshUserXp()
        refreshSubscription() // no-op when signed-out; fills the Langganan hero + premium gating at cold start
    }

    private fun applyPanelSnapshot(resp: SourcesResponse) {
        if (resp.sources.isNotEmpty()) {
            val normalized = withBundledIndoMax21(
                withBundledJavHey(
                    withBundledDutamovie(withBundledIdlix(resp.sources.map(::normalizePanelSource))),
                ),
            )
            panelSources = normalized
            configureLiveAccessCodes(normalized)
        }
        if (resp.supportMe != null) supportMe = resp.supportMe
        resp.announcement?.let { announcement = it }
        resp.telemetry?.ingestToken?.takeIf { it.isNotBlank() }?.let { telemetryToken = it }
        resp.trial?.durationSeconds?.let { trialDurationSeconds = it.coerceIn(0L, 31_536_000L) }
        resp.releaseNotes?.let { releaseNotes = it }
        resp.helpCenter?.let { helpCenter = it }
    }

    /** Idlix is an on-device adapter, so it remains available before the remote panel adds a row. */
    private fun withBundledIdlix(sources: List<SourceOverride>): List<SourceOverride> {
        if (sources.any { it.sourceId == "idlix-compat" }) return sources
        val base = "https://z2.idlixku.com"
        val merged = sources + SourceOverride(
            sourceId = "idlix-compat",
            displayName = "Idlix",
            enabled = true,
            apiBaseUrl = base,
            webBaseUrl = base,
            category = "movie",
            lastProbeStatus = "ok",
            homeLinks = HomeLinks(
                showAll = listOf(HomeLink("Idlix Terbaru", "$base/api/movies?page=1&limit=36&sort=createdAt")),
                showOnClick = listOf(
                    HomeLink("Film Terbaru", "$base/api/movies?page=1&limit=36&sort=createdAt"),
                    HomeLink("Series Terbaru", "$base/api/series?page=1&limit=36&sort=createdAt"),
                    HomeLink("Populer", "$base/api/browse?page=1&limit=36&sort=popular"),
                ),
            ),
        )
        android.util.Log.i("TnIdlix", "added bundled Idlix source beside ${sources.size} panel sources")
        return merged
    }

    /** DutaMovie uses a native Muvipro adapter and remains available before the panel adds its row. */
    private fun withBundledDutamovie(sources: List<SourceOverride>): List<SourceOverride> {
        if (sources.any { it.sourceId == "dutamovie-compat" }) return sources
        val base = "https://restaurantesabadell.com"
        val merged = sources + SourceOverride(
            sourceId = "dutamovie-compat",
            displayName = "DutaMovie",
            enabled = true,
            apiBaseUrl = base,
            webBaseUrl = base,
            category = "movie",
            lastProbeStatus = "ok",
            homeLinks = HomeLinks(
                showAll = listOf(HomeLink("DutaMovie Terbaru", "$base/")),
                showOnClick = listOf(
                    HomeLink("Rilis Terbaru", "$base/"),
                    HomeLink("Box Office", "$base/box-office/"),
                    HomeLink("TV Series", "$base/serial-tv/"),
                    HomeLink("Film Indonesia", "$base/country/indonesia/"),
                    HomeLink("Drama Korea", "$base/country/korea/"),
                    HomeLink("Film China", "$base/country/china/"),
                ),
            ),
        )
        android.util.Log.i("TnDutamovie", "added bundled DutaMovie beside ${sources.size} panel sources")
        return merged
    }

    /** JavHey is mature-only and uses a native catalog/Base64 mirror adapter. */
    private fun withBundledJavHey(sources: List<SourceOverride>): List<SourceOverride> {
        if (sources.any { it.sourceId == "javhey-compat" }) return sources
        val base = "https://javhey.com"
        return sources + SourceOverride(
            sourceId = "javhey-compat",
            displayName = "JavHey",
            enabled = true,
            apiBaseUrl = base,
            webBaseUrl = base,
            category = "adult",
            lastProbeStatus = "ok",
            homeLinks = HomeLinks(
                showAll = listOf(HomeLink("JavHey Terbaru", "$base/videos/paling-baru")),
                showOnClick = listOf(
                    HomeLink("Terbaru", "$base/videos/paling-baru"),
                    HomeLink("Censored", "$base/category/2/censored"),
                    HomeLink("Uncensored", "$base/category/31/decensored"),
                    HomeLink("Paling Dilihat", "$base/videos/paling-dilihat"),
                    HomeLink("Rating Teratas", "$base/videos/top-rating"),
                ),
            ),
        )
    }

    /** IndoMax21 rotates domains; onperfect.com is the current upstream landing domain (moved off
     *  otrarevista.com, which the ISP now sinkholes to the "Internet Baik" block page). */
    private fun withBundledIndoMax21(sources: List<SourceOverride>): List<SourceOverride> {
        if (sources.any { it.sourceId == "indomax21-compat" }) return sources
        val base = "https://onperfect.com"
        return sources + SourceOverride(
            sourceId = "indomax21-compat",
            displayName = "IndoMax21",
            enabled = true,
            apiBaseUrl = base,
            webBaseUrl = base,
            category = "adult",
            lastProbeStatus = "ok",
            homeLinks = HomeLinks(
                showAll = listOf(HomeLink("IndoMax21 Terbaru", "$base/category/anime/")),
                showOnClick = listOf(
                    HomeLink("Anime", "$base/category/anime/"),
                    HomeLink("Donghua", "$base/category/donghua/"),
                    HomeLink("TV Show", "$base/category/serial-tv/"),
                    HomeLink("Asia", "$base/category/asia-m/"),
                    HomeLink("VivaMax", "$base/category/vivamax/"),
                    HomeLink("JAV", "$base/category/jav/"),
                    HomeLink("Western", "$base/category/semi-barat/"),
                    HomeLink("Indonesia", "$base/category/bokep-indo/"),
                ),
            ),
        )
    }

    private fun normalizePanelSource(src: SourceOverride): SourceOverride {
        fun linksEmpty(links: HomeLinks?): Boolean =
            links == null || (links.showAll.isEmpty() && links.showOnClick.isEmpty())
        return when {
            src.sourceId.contains("dramawave", ignoreCase = true) -> {
                val popular = "https://dramawave.goodbos.online/api/recommend?lang=in&next=1"
                val anime = "https://dramawave.goodbos.online/api/anime?lang=in&next=1"
                src.copy(
                    apiBaseUrl = "https://dramawave.goodbos.online",
                    webBaseUrl = "https://dramawave.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = if (linksEmpty(src.homeLinks)) {
                        HomeLinks(
                            showAll = listOf(HomeLink("Pilihan Populer", popular)),
                            showOnClick = listOf(
                                HomeLink("Pilihan Populer", popular),
                                HomeLink("Anime", anime),
                            ),
                        )
                    } else {
                        src.homeLinks
                    },
                )
            }
            src.sourceId.contains("dramabite", ignoreCase = true) -> {
                val home = "https://dramabite.goodbos.online/home?lang=id"
                val more = "https://dramabite.goodbos.online/module?page=1&lang=id"
                src.copy(
                    apiBaseUrl = "https://dramabite.goodbos.online",
                    webBaseUrl = "https://dramabite.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = if (linksEmpty(src.homeLinks)) {
                        HomeLinks(
                            showAll = listOf(HomeLink("Paling Populer", home)),
                            showOnClick = listOf(
                                HomeLink("Paling Populer", home),
                                HomeLink("Rekomendasi", more),
                            ),
                        )
                    } else {
                        src.homeLinks
                    },
                )
            }
            src.sourceId.contains("melolo", ignoreCase = true) -> {
                val home = "https://melolo.goodbos.online/api/home?lang=id&offset=0"
                src.copy(
                    apiBaseUrl = "https://melolo.goodbos.online",
                    webBaseUrl = "https://melolo.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = if (linksEmpty(src.homeLinks)) {
                        HomeLinks(
                            showAll = listOf(HomeLink("Home / Trending", home)),
                            showOnClick = listOf(HomeLink("Home / Trending", home)),
                        )
                    } else {
                        src.homeLinks
                    },
                )
            }
            src.sourceId.contains("flickreels", ignoreCase = true) -> {
                val home = "https://flickreels.goodbos.online/api/home?lang=6&page=1"
                val trending = "https://flickreels.goodbos.online/trending?lang=6"
                src.copy(
                    apiBaseUrl = "https://flickreels.goodbos.online",
                    webBaseUrl = "https://flickreels.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = if (linksEmpty(src.homeLinks)) {
                        HomeLinks(
                            showAll = listOf(HomeLink("Rekomendasi", home)),
                            showOnClick = listOf(
                                HomeLink("Rekomendasi", home),
                                HomeLink("Trending", trending),
                            ),
                        )
                    } else {
                        src.homeLinks
                    },
                )
            }
            src.sourceId.contains("shortmax", ignoreCase = true) -> {
                val latest = "https://shortmax.goodbos.online/api/v1/new?lang=id&page=1"
                val popular = "https://shortmax.goodbos.online/api/v1/popular?lang=id&page=1"
                val hot = "https://shortmax.goodbos.online/api/v1/hot?lang=id&page=1"
                val ranking = "https://shortmax.goodbos.online/api/v1/ranking?lang=id&type=monthly"
                src.copy(
                    apiBaseUrl = "https://shortmax.goodbos.online",
                    webBaseUrl = "https://shortmax.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Baru", latest)),
                        showOnClick = listOf(
                            HomeLink("Baru", latest),
                            HomeLink("Populer", popular),
                            HomeLink("Hot", hot),
                            HomeLink("Peringkat", ranking),
                        ),
                    ),
                )
            }
            src.sourceId.contains("stardust", ignoreCase = true) -> {
                val latest = "https://stardusttv2.goodbos.online/api/v1/videos?page=1&per_page=18&lang=id"
                val trending = "https://stardusttv2.goodbos.online/api/v1/trending?lang=id"
                src.copy(
                    apiBaseUrl = "https://stardusttv2.goodbos.online",
                    webBaseUrl = "https://stardusttv2.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", latest)),
                        showOnClick = listOf(
                            HomeLink("Terbaru", latest),
                            HomeLink("Trending", trending),
                        ),
                    ),
                )
            }
            src.sourceId.contains("velolo", ignoreCase = true) -> {
                val latest = "https://velolo.goodbos.online/new?lang=id&page=1&page_size=18"
                val hot = "https://velolo.goodbos.online/hot?lang=id"
                src.copy(
                    apiBaseUrl = "https://velolo.goodbos.online",
                    webBaseUrl = "https://velolo.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Baru", latest)),
                        showOnClick = listOf(
                            HomeLink("Baru", latest),
                            HomeLink("Hot", hot),
                        ),
                    ),
                )
            }
            src.sourceId.contains("happyshort", ignoreCase = true) -> {
                val latest = "https://happyshort.goodbos.online/api/hs/home?page=1&size=20&lang=id"
                val forYou = "https://happyshort.goodbos.online/api/hs/foryou?lang=id"
                src.copy(
                    apiBaseUrl = "https://happyshort.goodbos.online",
                    webBaseUrl = "https://happyshort.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", latest)),
                        showOnClick = listOf(
                            HomeLink("Terbaru", latest),
                            HomeLink("Rekomendasi", forYou),
                        ),
                    ),
                )
            }
            src.sourceId.contains("dramanova", ignoreCase = true) -> {
                val latest = "https://dramanova.goodbos.online/api/home?lang=id&page=1&sort=views"
                val hot = "https://dramanova.goodbos.online/api/hot?lang=id&page=1&sort=views"
                src.copy(
                    apiBaseUrl = "https://dramanova.goodbos.online",
                    webBaseUrl = "https://dramanova.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", latest)),
                        showOnClick = listOf(
                            HomeLink("Terbaru", latest),
                            HomeLink("Hot", hot),
                        ),
                    ),
                )
            }
            src.sourceId.contains("cubetv", ignoreCase = true) -> {
                val popular = "https://cubetv.goodbos.online/api/module?lang=id&moduleid=PaEpZ7&page=1"
                val selected = "https://cubetv.goodbos.online/api/module?lang=id&moduleid=9vb8aR&page=1"
                src.copy(
                    apiBaseUrl = "https://cubetv.goodbos.online",
                    webBaseUrl = "https://cubetv.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Rekomendasi Populer", popular)),
                        showOnClick = listOf(
                            HomeLink("Drama Pilihan", selected),
                            HomeLink("Rekomendasi Populer", popular),
                        ),
                    ),
                )
            }
            src.sourceId.contains("goodshort", ignoreCase = true) -> {
                val listUrl = { channel: Int -> "https://goodshort.goodbos.online/home?lang=in&channel=$channel&page=1&size=20" }
                src.copy(
                    apiBaseUrl = "https://goodshort.goodbos.online",
                    webBaseUrl = "https://goodshort.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = if (linksEmpty(src.homeLinks)) {
                        HomeLinks(
                            showAll = listOf(HomeLink("Terbaru", listUrl(563))),
                            showOnClick = listOf(
                                HomeLink("Tren", listUrl(-1)),
                                HomeLink("Terbaru", listUrl(563)),
                                HomeLink("Anime", listUrl(656)),
                                HomeLink("Dubing", listUrl(567)),
                            ),
                        )
                    } else {
                        src.homeLinks
                    },
                )
            }
            src.sourceId.contains("fundrama", ignoreCase = true) -> {
                val list = "https://drakula.goodbos.online/api/fundrama/dramas?lang=id&page=1&limit=20"
                src.copy(
                    apiBaseUrl = "https://drakula.goodbos.online/fundrama",
                    webBaseUrl = "https://drakula.goodbos.online/fundrama",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = if (linksEmpty(src.homeLinks)) {
                        HomeLinks(
                            showAll = listOf(HomeLink("Terbaru", list)),
                            showOnClick = listOf(HomeLink("Terbaru", list)),
                        )
                    } else {
                        src.homeLinks
                    },
                )
            }
            src.sourceId.contains("microdrama", ignoreCase = true) -> {
                val list = "https://drakula.goodbos.online/api/microdrama/list?lang=id&page=1&limit=20"
                src.copy(
                    apiBaseUrl = "https://drakula.goodbos.online/microdrama",
                    webBaseUrl = "https://drakula.goodbos.online/microdrama",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", list)),
                        showOnClick = listOf(HomeLink("Terbaru", list)),
                    ),
                )
            }
            src.sourceId.contains("netshort", ignoreCase = true) -> {
                val home = "https://netshort.goodbos.online/api/home/1?lang=in"
                val list = "https://netshort.goodbos.online/api/list/1?lang=in"
                val banner = "https://netshort.goodbos.online/api/banner?lang=in"
                src.copy(
                    apiBaseUrl = "https://netshort.goodbos.online",
                    webBaseUrl = "https://netshort.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", home)),
                        showOnClick = listOf(
                            HomeLink("Terbaru", home),
                            HomeLink("Populer", list),
                            HomeLink("Banner", banner),
                        ),
                    ),
                )
            }
            src.sourceId.contains("reelife", ignoreCase = true) -> {
                val home = "https://reelife.goodbos.online/api/v1/home?page=1&lang=in"
                val trending = "https://reelife.goodbos.online/api/v1/rank?type=trending&lang=in"
                src.copy(
                    apiBaseUrl = "https://reelife.goodbos.online",
                    webBaseUrl = "https://reelife.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    proxyEnabled = false,
                    proxyPaths = null,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", home)),
                        showOnClick = listOf(
                            HomeLink("Terbaru", home),
                            HomeLink("Sedang Tren", trending),
                        ),
                    ),
                )
            }
            src.sourceId.contains("idrama", ignoreCase = true) -> {
                val tabUrl = { tab: String, section: String ->
                    "https://idrama.goodbos.online/tab/$tab?lang=id&section=$section"
                }
                val latestUrl = "https://idrama.goodbos.online/tab/channel_7e89a1a2?lang=id"
                src.copy(
                    apiBaseUrl = "https://idrama.goodbos.online",
                    webBaseUrl = "https://idrama.goodbos.online",
                    category = "short-drama",
                    accessCode = src.accessCode?.takeIf { it.isNotBlank() } ?: GOODBOS_ACCESS_CODE,
                    homeLinks = HomeLinks(
                        showAll = listOf(HomeLink("Terbaru", latestUrl)),
                        showOnClick = listOf(
                            HomeLink("Terbaru", latestUrl),
                            HomeLink("Populer", tabUrl("channel_022dd4f1", "section_bccf2ae7,section_b5c35a7c")),
                            HomeLink("Sedang Tren", tabUrl("channel_f4904f0b", "section_d557ba94")),
                            HomeLink("Hits Terbaru", tabUrl("channel_a57c8658", "section_37f1f85e")),
                        ),
                    ),
                )
            }
            else -> src
        }
    }

    private fun configureLiveAccessCodes(sources: List<SourceOverride>) {
        LiveSource.configureAccessCodes(
            sources.flatMap { src ->
                val code = if (src.category.equals("short-drama", true) || src.category.equals("dramabos", true)) {
                    "server"
                } else {
                    src.accessCode?.trim().orEmpty()
                }
                if (code.isBlank()) {
                    emptyList()
                } else {
                    val bases = buildList {
                        add(src.apiBaseUrl)
                        src.webBaseUrl?.let(::add)
                        if (src.sourceId.contains("reelshort", ignoreCase = true)) {
                            add("https://reelshort.goodbos.online")
                        }
                        if (src.sourceId.contains("flextv", ignoreCase = true)) {
                            add("https://flextv.goodbos.online")
                        }
                        if (src.sourceId.contains("freereels", ignoreCase = true)) {
                            add("https://drakula.goodbos.online")
                        }
                        if (src.sourceId.contains("dramabox", ignoreCase = true)) {
                            add("https://dramabox.goodbos.online")
                        }
                        if (src.sourceId.contains("bilitv", ignoreCase = true)) {
                            add("https://bilitv.goodbos.online")
                        }
                        if (src.sourceId.contains("dotdrama", ignoreCase = true)) {
                            add("https://dotdrama.goodbos.online")
                        }
                        if (src.sourceId.contains("dramawave", ignoreCase = true)) {
                            add("https://dramawave.goodbos.online")
                        }
                        if (src.sourceId.contains("dramabite", ignoreCase = true)) {
                            add("https://dramabite.goodbos.online")
                        }
                        if (src.sourceId.contains("goodshort", ignoreCase = true)) {
                            add("https://goodshort.goodbos.online")
                        }
                        if (src.sourceId.contains("shortmax", ignoreCase = true)) {
                            add("https://shortmax.goodbos.online")
                        }
                        if (src.sourceId.contains("stardust", ignoreCase = true)) {
                            add("https://stardusttv2.goodbos.online")
                        }
                        if (src.sourceId.contains("velolo", ignoreCase = true)) {
                            add("https://velolo.goodbos.online")
                        }
                        if (src.sourceId.contains("happyshort", ignoreCase = true)) {
                            add("https://happyshort.goodbos.online")
                        }
                        if (src.sourceId.contains("dramanova", ignoreCase = true)) {
                            add("https://dramanova.goodbos.online")
                        }
                        if (src.sourceId.contains("cubetv", ignoreCase = true)) {
                            add("https://cubetv.goodbos.online")
                        }
                        if (src.sourceId.contains("fundrama", ignoreCase = true)) {
                            add("https://drakula.goodbos.online")
                            add("https://drakula.goodbos.online/fundrama")
                        }
                        if (src.sourceId.contains("microdrama", ignoreCase = true)) {
                            add("https://drakula.goodbos.online")
                            add("https://drakula.goodbos.online/microdrama")
                        }
                        if (src.sourceId.contains("netshort", ignoreCase = true)) {
                            add("https://netshort.goodbos.online")
                        }
                        if (src.sourceId.contains("reelife", ignoreCase = true)) {
                            add("https://reelife.goodbos.online")
                        }
                        if (src.sourceId.contains("idrama", ignoreCase = true)) {
                            add("https://idrama.goodbos.online")
                        }
                    }
                    bases.filter { it.isNotBlank() }.distinct().map { it to code }
                }
            }.toMap(),
        )
    }

    private fun cachePanelSnapshot(base: String, resp: SourcesResponse) {
        if (resp.sources.isEmpty()) return
        val safe = resp.copy(proxyBypass = null)
        runCatching {
            SettingsStore.setStr(PANEL_CACHE_BASE, base)
            SettingsStore.setStr(PANEL_CACHE_JSON, panelCacheJson.encodeToString(SourcesResponse.serializer(), safe))
        }
    }

    private fun restoreCachedPanelSnapshot(base: String) {
        if (panelSources.isNotEmpty()) return
        val cachedBase = SettingsStore.getStr(PANEL_CACHE_BASE, "").trim().trimEnd('/')
        if (cachedBase.isNotBlank() && cachedBase != base) return
        val raw = SettingsStore.getStr(PANEL_CACHE_JSON, "")
        if (raw.isBlank()) return
        runCatching { panelCacheJson.decodeFromString(SourcesResponse.serializer(), raw) }
            .getOrNull()
            ?.takeIf { it.sources.isNotEmpty() }
            ?.let {
                applyPanelSnapshot(it)
                panelVersion++
                primeHomeSections()
                android.util.Log.d("TnPanel", "restored cached panel snapshot: ${it.sources.size} sources")
            }
    }

    private fun restoreBundledSourceSnapshot() {
        if (panelSources.isNotEmpty()) return
        val sources = registry?.extensions.orEmpty().mapNotNull { ext ->
            val base = ext.apiBaseUrl ?: ext.webBaseUrl ?: ext.upstreamUrl ?: return@mapNotNull null
            SourceOverride(
                sourceId = ext.id,
                displayName = ext.name.ifBlank { ext.id },
                enabled = true,
                apiBaseUrl = base,
                webBaseUrl = ext.webBaseUrl ?: ext.upstreamUrl ?: base,
                category = ext.category,
                accessCode = ext.accessCode,
                homeLinks = bundledHomeLinks(ext, base),
                proxyEnabled = false,
            )
        }
        if (sources.isEmpty()) return
        panelSources = sources
        configureLiveAccessCodes(sources)
        panelVersion++
        primeHomeSections()
        android.util.Log.d("TnPanel", "restored bundled live sources: ${sources.size}")
    }

    private fun bundledHomeLinks(ext: RegistryExtension, base: String): HomeLinks {
        val cleanBase = base.trimEnd('/')
        if (ext.id == "idlix-compat") {
            val movies = "$cleanBase/api/movies?page=1&limit=36&sort=createdAt"
            val series = "$cleanBase/api/series?page=1&limit=36&sort=createdAt"
            val popular = "$cleanBase/api/browse?page=1&limit=36&sort=popular"
            return HomeLinks(
                showAll = listOf(HomeLink("Idlix Terbaru", movies)),
                showOnClick = listOf(
                    HomeLink("Film Terbaru", movies),
                    HomeLink("Series Terbaru", series),
                    HomeLink("Populer", popular),
                ),
            )
        }
        if (ext.id == "dutamovie-compat") {
            return HomeLinks(
                showAll = listOf(HomeLink("DutaMovie Terbaru", "$cleanBase/")),
                showOnClick = listOf(
                    HomeLink("Rilis Terbaru", "$cleanBase/"),
                    HomeLink("Box Office", "$cleanBase/box-office/"),
                    HomeLink("TV Series", "$cleanBase/serial-tv/"),
                    HomeLink("Film Indonesia", "$cleanBase/country/indonesia/"),
                    HomeLink("Drama Korea", "$cleanBase/country/korea/"),
                    HomeLink("Film China", "$cleanBase/country/china/"),
                ),
            )
        }
        if (ext.id == "javhey-compat") {
            return HomeLinks(
                showAll = listOf(HomeLink("JavHey Terbaru", "$cleanBase/videos/paling-baru")),
                showOnClick = listOf(
                    HomeLink("Terbaru", "$cleanBase/videos/paling-baru"),
                    HomeLink("Censored", "$cleanBase/category/2/censored"),
                    HomeLink("Uncensored", "$cleanBase/category/31/decensored"),
                    HomeLink("Paling Dilihat", "$cleanBase/videos/paling-dilihat"),
                    HomeLink("Rating Teratas", "$cleanBase/videos/top-rating"),
                ),
            )
        }
        if (ext.id == "indomax21-compat") {
            return HomeLinks(
                showAll = listOf(HomeLink("IndoMax21 Terbaru", "$cleanBase/category/anime/")),
                showOnClick = listOf(
                    HomeLink("Anime", "$cleanBase/category/anime/"),
                    HomeLink("Donghua", "$cleanBase/category/donghua/"),
                    HomeLink("TV Show", "$cleanBase/category/serial-tv/"),
                    HomeLink("Asia", "$cleanBase/category/asia-m/"),
                    HomeLink("VivaMax", "$cleanBase/category/vivamax/"),
                    HomeLink("JAV", "$cleanBase/category/jav/"),
                    HomeLink("Western", "$cleanBase/category/semi-barat/"),
                    HomeLink("Indonesia", "$cleanBase/category/bokep-indo/"),
                ),
            )
        }
        if (ext.id == "winbu-compat") {
            return HomeLinks(
                showAll = listOf(HomeLink("Winbu", "https://winbu.net/animedonghua/")),
                showOnClick = listOf(
                    HomeLink("Rilis Terbaru", "https://winbu.net/animedonghua/"),
                    HomeLink("Film", "https://winbu.net/film/"),
                    HomeLink("JP-KR-CH", "https://winbu.net/others/"),
                    HomeLink("Tv Show", "https://winbu.net/tvshow/"),
                ),
            )
        }
        if (ext.id == "reelshort-compat") {
            val tabUrl = { tab: String -> "$cleanBase/home?tab=$tab&lang=in" }
            return HomeLinks(
                showAll = listOf(HomeLink("Populer", tabUrl("populer"))),
                showOnClick = listOf(
                    HomeLink("Populer", tabUrl("populer")),
                    HomeLink("Terbaru", tabUrl("terbaru")),
                    HomeLink("Ranking", tabUrl("ranking")),
                    HomeLink("Asia", tabUrl("asia")),
                    HomeLink("Pria", tabUrl("pria")),
                    HomeLink("Wanita", tabUrl("wanita")),
                ),
            )
        }
        if (ext.id == "flextv-compat") {
            val listUrl = { classifyId: Int -> "$cleanBase/api/drama/list?classify_id=$classifyId&lang=id" }
            return HomeLinks(
                showAll = listOf(HomeLink("Baru", listUrl(18))),
                showOnClick = listOf(
                    HomeLink("Baru", listUrl(18)),
                    HomeLink("Semua Episode", "${listUrl(18)}&template=4"),
                    HomeLink("Pilihan Editor", "${listUrl(17)}&floor=Pilihan%20Editor"),
                    HomeLink("Populer", "${listUrl(17)}&floor=Populer"),
                    HomeLink("Pria", listUrl(20)),
                    HomeLink("Wanita", listUrl(21)),
                ),
            )
        }
        if (ext.id == "freereels-compat") {
            val listUrl = { lane: String -> "$cleanBase/api/freereels/$lane?lang=id" }
            return HomeLinks(
                showAll = listOf(HomeLink("New", listUrl("new"))),
                showOnClick = listOf(
                    HomeLink("New", listUrl("new")),
                    HomeLink("For You", listUrl("foryou")),
                    HomeLink("Popular", listUrl("popular")),
                ),
            )
        }
        if (ext.id == "dramabox-compat") {
            val api = "$cleanBase/api/v1"
            return HomeLinks(
                showAll = listOf(HomeLink("Rekomendasi", "$api/homepage?lang=in")),
                showOnClick = listOf(
                    HomeLink("Rekomendasi", "$api/homepage?lang=in"),
                    HomeLink("For You", "$api/foryou?lang=in"),
                    HomeLink("Dubbed", "$api/dubbed?classify=terpopuler&lang=in"),
                ),
            )
        }
        if (ext.id == "bilitv-compat") {
            val list = "https://bilitv.goodbos.online/api/home?page=1&limit=20&lang=id"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", list)),
                showOnClick = listOf(HomeLink("Terbaru", list)),
            )
        }
        if (ext.id == "dotdrama-compat") {
            val list = "https://dotdrama.goodbos.online/api/drama/list?lang=id&page=1&limit=18"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", list)),
                showOnClick = listOf(HomeLink("Terbaru", list)),
            )
        }
        if (ext.id == "dramawave-compat") {
            val popular = "https://dramawave.goodbos.online/api/recommend?lang=in&next=1"
            val anime = "https://dramawave.goodbos.online/api/anime?lang=in&next=1"
            return HomeLinks(
                showAll = listOf(HomeLink("Pilihan Populer", popular)),
                showOnClick = listOf(
                    HomeLink("Pilihan Populer", popular),
                    HomeLink("Anime", anime),
                ),
            )
        }
        if (ext.id == "dramabite-compat") {
            val home = "https://dramabite.goodbos.online/home?lang=id"
            val more = "https://dramabite.goodbos.online/module?page=1&lang=id"
            return HomeLinks(
                showAll = listOf(HomeLink("Paling Populer", home)),
                showOnClick = listOf(
                    HomeLink("Paling Populer", home),
                    HomeLink("Rekomendasi", more),
                ),
            )
        }
        if (ext.id == "dramanova-compat") {
            val latest = "https://dramanova.goodbos.online/api/home?lang=id&page=1&sort=views"
            val hot = "https://dramanova.goodbos.online/api/hot?lang=id&page=1&sort=views"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", latest)),
                showOnClick = listOf(
                    HomeLink("Terbaru", latest),
                    HomeLink("Hot", hot),
                ),
            )
        }
        if (ext.id == "goodshort-compat") {
            val listUrl = { channel: Int -> "https://goodshort.goodbos.online/home?lang=in&channel=$channel&page=1&size=20" }
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", listUrl(563))),
                showOnClick = listOf(
                    HomeLink("Tren", listUrl(-1)),
                    HomeLink("Terbaru", listUrl(563)),
                    HomeLink("Anime", listUrl(656)),
                    HomeLink("Dubing", listUrl(567)),
                ),
            )
        }
        if (ext.id == "fundrama-compat") {
            val list = "https://drakula.goodbos.online/api/fundrama/dramas?lang=id&page=1&limit=20"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", list)),
                showOnClick = listOf(HomeLink("Terbaru", list)),
            )
        }
        if (ext.id == "microdrama-compat") {
            val list = "https://drakula.goodbos.online/api/microdrama/list?lang=id&page=1&limit=20"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", list)),
                showOnClick = listOf(HomeLink("Terbaru", list)),
            )
        }
        if (ext.id == "netshort-compat") {
            val home = "https://netshort.goodbos.online/api/home/1?lang=in"
            val list = "https://netshort.goodbos.online/api/list/1?lang=in"
            val banner = "https://netshort.goodbos.online/api/banner?lang=in"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", home)),
                showOnClick = listOf(
                    HomeLink("Terbaru", home),
                    HomeLink("Populer", list),
                    HomeLink("Banner", banner),
                ),
            )
        }
        if (ext.id == "reelife-compat") {
            val home = "https://reelife.goodbos.online/api/v1/home?page=1&lang=in"
            val trending = "https://reelife.goodbos.online/api/v1/rank?type=trending&lang=in"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", home)),
                showOnClick = listOf(
                    HomeLink("Terbaru", home),
                    HomeLink("Sedang Tren", trending),
                ),
            )
        }
        if (ext.id == "idrama-compat") {
            val tabUrl = { tab: String, section: String ->
                "https://idrama.goodbos.online/tab/$tab?lang=id&section=$section"
            }
            val latestUrl = "https://idrama.goodbos.online/tab/channel_7e89a1a2?lang=id"
            return HomeLinks(
                showAll = listOf(HomeLink("Terbaru", latestUrl)),
                showOnClick = listOf(
                    HomeLink("Terbaru", latestUrl),
                    HomeLink("Populer", tabUrl("channel_022dd4f1", "section_bccf2ae7,section_b5c35a7c")),
                    HomeLink("Sedang Tren", tabUrl("channel_f4904f0b", "section_d557ba94")),
                    HomeLink("Hits Terbaru", tabUrl("channel_a57c8658", "section_37f1f85e")),
                ),
            )
        }
        return HomeLinks(
            showAll = listOf(HomeLink(ext.name.ifBlank { ext.id }, cleanBase)),
            showOnClick = listOf(HomeLink("Terbaru", cleanBase)),
        )
    }

    /** Pull the user's XP/level/streak/realm; fires a breakthrough event when the level rises. */
    suspend fun refreshUserXp() {
        if (panelBase.isBlank() || !AuthManager.signedIn) return
        val b = cultivationBearer()
        if (telemetryToken.isBlank() && b == null) return
        val xp = UserApi(panelBase).fetchXp(installId, telemetryToken, b) ?: return
        val prev = userXp
        userXp = xp
        if (prev != null && xp.level > prev.level) {
            breakthrough = BreakthroughEvent(
                level = xp.level,
                realmName = xp.realm?.displayName ?: "Level ${xp.level}",
                realmChanged = xp.realm?.realmId != prev.realm?.realmId,
            )
        }
    }

    // ---------------- editable account profile (display name / handle / avatar) ----------------

    /** The user's editable profile synced to their account (null until fetched). */
    var userProfile by mutableStateOf<UserProfile?>(null)
        private set

    private fun profileApi(): ProfileApi? = if (panelBase.isBlank()) null else ProfileApi(panelBase)

    /** Effective hero display name: synced profile â†’ local cache â†’ Google account â†’ default. */
    val profileName: String
        get() = userProfile?.displayName?.takeIf { it.isNotBlank() }
            ?: SettingsStore.getStr("profile_name", "").takeIf { it.isNotBlank() }
            ?: AuthManager.user?.displayName?.takeIf { it.isNotBlank() }
            ?: "Rafa Nova"

    /** Effective public handle (no leading @). */
    val profileUsername: String
        get() = userProfile?.username?.takeIf { it.isNotBlank() }
            ?: SettingsStore.getStr("profile_username", "").takeIf { it.isNotBlank() }
            ?: SettingsStore.profileUsernameFallback()

    /** Contact string attached to content reports so admins can see the signed-in profile. */
    val profileContact: String
        get() {
            if (!AuthManager.signedIn) return ""
            val name = profileName.takeIf { it.isNotBlank() }.orEmpty()
            val handle = profileUsername.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty()
            val email = AuthManager.user?.email?.takeIf { it.isNotBlank() }.orEmpty()
            return listOf(
                listOf(name, handle).filter { it.isNotBlank() }.joinToString(" ").trim(),
                email,
            ).filter { it.isNotBlank() }.joinToString(" | ")
        }

    /** Effective avatar URL, or null to fall back to the name initial. */
    val profilePhotoUrl: String?
        get() = (userProfile?.photoUrl?.takeIf { it.isNotBlank() }
            ?: SettingsStore.getStr("profile_photo", "").takeIf { it.isNotBlank() }
            ?: AuthManager.user?.photoUrl?.toString())

    /** Pull the account profile (best-effort) â€” called when Profile opens; no-op when signed out. */
    suspend fun refreshUserProfile() {
        val api = profileApi() ?: return
        val token = AuthManager.idToken() ?: return
        api.getProfile(token)?.let { cacheProfile(it) }
    }

    /** Mirror the synced profile into snapshot state + SettingsStore so the hero is instant + offline-safe. */
    private fun cacheProfile(p: UserProfile) {
        userProfile = p
        SettingsStore.setStr("profile_name", p.displayName)
        SettingsStore.setStr("profile_username", p.username)
        SettingsStore.setStr("profile_photo", p.photoUrl)
    }

    /** Save display name + handle to the account; returns the typed outcome for the edit dialog. */
    suspend fun updateProfile(name: String, username: String): ProfileApi.SaveOutcome {
        val api = profileApi() ?: return ProfileApi.SaveOutcome.Failed
        val token = AuthManager.idToken() ?: return ProfileApi.SaveOutcome.Failed
        return api.updateProfile(token, name, username).also {
            if (it is ProfileApi.SaveOutcome.Success) cacheProfile(it.profile)
        }
    }

    /** Upload an avatar photo to the account; true on success (the hero then reflects the new photo). */
    suspend fun updateAvatar(bytes: ByteArray, mime: String): Boolean {
        val api = profileApi() ?: return false
        val token = AuthManager.idToken() ?: return false
        return api.uploadAvatar(token, bytes, mime)?.let { cacheProfile(it); true } ?: false
    }

    // ---------------- subscription / billing (panel `/api/v1/me/*`) ----------------

    /** The caller's live entitlement (null until fetched / signed-out). Drives the Langganan hero +
     *  Profile pill + premium gating. */
    var subscription by mutableStateOf<SubscriptionState?>(null)
        private set

    /** Purchasable plans, live from the panel; seeded with [FALLBACK_PLANS] so the UI is never empty. */
    var plans by mutableStateOf(FALLBACK_PLANS)
        private set

    /** Number of paid/premium sources (drives the live "N source" copy). 0 when unknown â†’ UI omits it. */
    fun premiumSourceCount(): Int = enabledPanelSources().count { it.premium }

    /** The single entitlement gate: paid-active or trial-active. Used by Home/Search/premium filters. */
    fun isEntitledToPremium(): Boolean =
        AuthManager.signedIn && subscription?.entitled == true

    private fun billingApi(): BillingApi? = if (panelBase.isBlank()) null else BillingApi(panelBase)

    /** Pull live entitlement + plans (best-effort) â€” call on sign-in + when Langganan/Profile opens. */
    suspend fun refreshSubscription() {
        val api = billingApi() ?: return
        api.getPublicPlans()?.takeIf { it.isNotEmpty() }?.let { plans = it }
        val token = AuthManager.idToken() ?: return
        LiveSource.configurePremiumProxy(panelBase, token)
        api.getSubscription(token)?.let {
            subscription = it
            panelVersion++
            if (it.entitled) primeHomeSections()
        }
        api.getPlans(token)?.takeIf { it.isNotEmpty() }?.let { plans = it }
    }

    /** Start a Violet QRIS checkout for [planCode]; the UI navigates to the QRIS screen on Success. */
    suspend fun startCheckout(planCode: String): BillingApi.CheckoutOutcome {
        val api = billingApi() ?: return BillingApi.CheckoutOutcome.Failed
        val token = AuthManager.idToken() ?: return BillingApi.CheckoutOutcome.Failed
        return api.startCheckout(token, planCode)
    }

    /** Poll a single order's payment status (QRIS screen). */
    suspend fun paymentStatus(orderId: String): String? {
        val api = billingApi() ?: return null
        val token = AuthManager.idToken() ?: return null
        return api.getPaymentStatus(token, orderId)
    }

    /** Cancel a pending QRIS order from the native checkout screen. */
    suspend fun cancelPayment(orderId: String): String? {
        val api = billingApi() ?: return null
        val token = AuthManager.idToken() ?: return null
        return api.cancelPayment(token, orderId)
    }

    /** Claim the one-shot trial server-side (per-account + per-device gates); refreshes on success. */
    suspend fun claimTrialServer(): BillingApi.TrialOutcome {
        val api = billingApi() ?: return BillingApi.TrialOutcome.Failed
        val token = AuthManager.idToken() ?: return BillingApi.TrialOutcome.Failed
        return api.claimTrial(token, installId).also {
            if (it is BillingApi.TrialOutcome.Success) refreshSubscription()
        }
    }

    /** Account payment history for the "Kelola langganan" sheet. */
    suspend fun paymentHistory(): List<PaymentRow> {
        val api = billingApi() ?: return emptyList()
        val token = AuthManager.idToken() ?: return emptyList()
        return api.getPayments(token).orEmpty()
    }

    /** Upload a watch session's heartbeats, then refresh XP so the Profile reflects the new progress. */
    fun reportWatchSession(sessionId: String, episodeId: String, sourceId: String, isShort: Boolean, heartbeats: List<Heartbeat>) {
        if (!AuthManager.signedIn || !hasPanel || telemetryToken.isBlank() || heartbeats.size < 2) return
        liveScope.launch {
            // Signed in â†’ heartbeats accrue to the ACCOUNT (acct:uid); else to this install.
            UserApi(panelBase).postWatchSession(installId, sessionId, episodeId, sourceId, isShort, heartbeats, telemetryToken, cultivationBearer())
            refreshUserXp()
        }
    }

    /** Forum client bound to the current panel base, or null when no panel is configured. */
    fun forumApi(): ForumApi? = panelBase.takeIf { it.isNotBlank() }?.let { ForumApi(it) }

    /** Anonymous per-install id, exposed for forum self-declared identity. */
    val deviceInstallId: String get() = installId

    /** True when a watch/detail URL is a vertical short-drama (portrait) — either a known short source
     *  (DramaBox / FreeReels / FlexTv / ReelShort) or a panel source flagged with a short/reel/micro
     *  category. Seeds the player's portrait lock; the real video aspect ratio confirms/corrects it. */
    fun isShortSource(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (LiveSource.isShortDrama(url)) return true
        val cat = sourceForUrl(url)?.category.orEmpty().lowercase(Locale.ROOT)
        return "short" in cat || "reel" in cat || "micro" in cat
    }

    /** Stable canonical source id for watch telemetry, resilient to rotating source domains. */
    fun sourceIdForUrl(url: String): String {
        sourceForUrl(url)?.sourceId?.let { return slugId(it, 120) }
        val host = hostOf(url).orEmpty().lowercase(Locale.ROOT)
        val fuzzy = panelSources
            .mapNotNull { source ->
                val stem = source.sourceId.lowercase(Locale.ROOT).removeSuffix("-compat")
                source.takeIf { stem.length >= 5 && host.contains(stem) }?.let { stem.length to source.sourceId }
            }
            .maxByOrNull { it.first }
            ?.second
        return slugId(fuzzy ?: host.ifBlank { "web" }, 120)
    }

    /** Stable, sanitized per-episode id for a watch URL (host + last path segment). */
    fun episodeIdForUrl(url: String): String {
        val host = hostOf(url) ?: "web"
        val seg = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
            .split('/').lastOrNull { it.isNotBlank() }.orEmpty()
        return slugId("$host-$seg", 120)
    }

    private fun slugId(s: String, max: Int): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._:-]+"), "-").trim('-').take(max).ifBlank { "x" }

    /** Pull the cross-user popular searches + trending content from the panel (best-effort). */
    private suspend fun refreshTrending() {
        if (panelBase.isBlank()) return
        val api = TrendingApi(panelBase)
        api.fetchSearches()?.takeIf { it.isNotEmpty() }?.let { popularSearches = it }
        api.fetchContent()?.takeIf { it.isNotEmpty() }?.let { items ->
            val raw = items.map { t ->
                PosterItem(
                    title = t.title,
                    sub = "",
                    art = artOf(t.url ?: t.title),
                    badge = t.badge?.ifBlank { null },
                    cover = t.cover?.ifBlank { null },
                    url = t.url?.ifBlank { null },
                )
            }
            trendingPosters = raw
            val enriched = raw.mapIndexed { i, poster ->
                val item = items.getOrNull(i)
                val src = item?.sourceId?.ifBlank { null }?.let(::sourceById)
                    ?: item?.url?.ifBlank { null }?.let(::sourceForUrl)
                enrichPosterCover(poster, src)
            }
            if (enriched != raw) trendingPosters = enriched
        }
    }

    private fun enabledPanelSources() = panelSources.filter { it.enabled }
    private val hasPanel: Boolean get() = panelSources.isNotEmpty()
    /** True once the panel source list has been fetched — lets Home tell "loading" from "genuinely empty". */
    val panelLoaded: Boolean get() = hasPanel
    private val hasLiveSources: Boolean get() = activeSources().isNotEmpty()

    /** 18+ visibility — the same key AppState.mature persists to. Off by default. */
    private fun matureVisible(): Boolean = SettingsStore.getBool("mature", false)

    private fun isMatureSource(src: SourceOverride): Boolean = sourceAllowsAdult(src, null)

    /** Extensions catalog: every enabled source, minus mature ones unless 18+ is enabled. */
    private fun catalogSources(): List<SourceOverride> =
        enabledPanelSources().filter { matureVisible() || !isMatureSource(it) }

    /** Usable sources: the catalog, minus the ones the user has not installed (see [isExtInstalled]). */
    private fun activeSources(): List<SourceOverride> =
        catalogSources().filter { isExtInstalled(it.sourceId) }

    /** True when [sourceId] is currently usable (installed AND not mature-hidden). The Search screen
     *  uses this to instantly drop already-displayed result groups whose source was just uninstalled or
     *  hidden by turning 18+ off. */
    fun isExtVisible(sourceId: String): Boolean = activeSources().any { it.sourceId == sourceId }

    /** Category helper kept for bundled catalog fallbacks where drama lives on a separate starter lane. */
    private fun isDramaCategory(category: String?): Boolean {
        val c = category.orEmpty().lowercase(Locale.ROOT)
        return "drama" in c || "dracin" in c || "drakor" in c
    }

    /**
     * Premium (paid) sources must NOT surface their content to a non-entitled user â€” this is the
     * defense-in-depth UI half of the "gak bocor" guarantee (the server 402 on the premium proxy is
     * the real gate). A source is hidden from Home/Search when it is `premium` and the caller has no
     * active subscription/trial. Entitled users (and the case where there are no premium sources) see
     * everything as before.
     */
    private fun premiumLocked(src: SourceOverride): Boolean = src.premium && !isEntitledToPremium()

    /** True when [url] belongs to a premium source AND the user is NOT entitled. The play-time gate that
     *  blocks premium content (→ subscribe prompt) no matter how the URL was reached (search, deep link,
     *  a stale cache row) — the UI-hiding in [browsableSources] can leak, this is the hard stop. */
    fun isPremiumBlocked(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (isEntitledToPremium()) return false
        // Blocked if the panel source is premium OR it's a short-drama URL — by policy every short-drama
        // source is premium, so gate on the category too in case a row's `premium` flag is mis-set.
        return sourceForUrl(url)?.premium == true || isShortSource(url)
    }

    /** Installed, mature-gated sources the user is allowed to browse right now (premium hidden when not
     *  entitled). Drives Home + Search, so install/uninstall + the 18+ toggle propagate here. */
    private fun browsableSources() = activeSources().filterNot { premiumLocked(it) }

    /** Home-only source list, premium gated for non-subscribers. */
    private fun homeEnabledSources() = browsableSources()

    private fun orderedHomeSources(): List<SourceOverride> =
        homeEnabledSources().sortedBy { it.displayName.ifBlank { it.sourceId }.lowercase(Locale.ROOT) }

    // ---------------- panel cache routing (1f) ----------------

    private fun sourceById(id: String) = panelSources.firstOrNull { it.sourceId == id }

    fun searchSourceStatus(id: String): String? = sourceById(id)?.lastProbeStatus

    private suspend fun <T> trackSource(sourceId: String, block: suspend () -> T): T = try {
        block().also { SourceTelemetry.record(sourceId, failed = false) }
    } catch (error: Throwable) {
        SourceTelemetry.record(sourceId, failed = true)
        throw error
    }

    suspend fun servers(url: String): List<VideoServer> {
        val sourceId = sourceForUrl(url)?.sourceId.orEmpty()
        return if (sourceId.isBlank()) LiveSource.servers(url) else trackSource(sourceId) { LiveSource.servers(url) }
    }

    /** The enabled source whose apiBaseUrl host matches [url]'s host â€” to cache-route a bare detail URL. */
    private fun sourceForUrl(url: String): SourceOverride? {
        val host = hostOf(url) ?: return null
        return enabledPanelSources().firstOrNull {
            val b = hostOf(it.apiBaseUrl); b != null && (b == host || host.endsWith(".$b") || b.endsWith(".$host"))
        }
    }

    private fun hostOf(u: String): String? =
        runCatching { java.net.URI(u).host?.removePrefix("www.")?.lowercase(Locale.ROOT) }.getOrNull()?.takeIf { it.isNotBlank() }

    /** CatalogApi bound to the current panel base, or null when no panel is configured. */
    private fun catalogApi(): CatalogApi? = panelBase.takeIf { it.isNotBlank() }?.let { CatalogApi(it) }

    // ---------------- premium (DramaBos proxy) ----------------

    /** Platforms the panel premium proxy can serve today (DramaBox first; others added as mapped). */
    private val supportedPremium = setOf("dramabox")
    private fun premiumApi(): PremiumApi? = panelBase.takeIf { it.isNotBlank() }?.let { PremiumApi(it) }

    /** Refresh the Firebase bearer used by the lightweight GoodBos JSON proxy. */
    suspend fun preparePremiumProxy() {
        if (!AuthManager.signedIn) return
        AuthManager.idToken()?.let { LiveSource.configurePremiumProxy(panelBase, it) }
    }

    private fun isManagedShortDrama(src: SourceOverride?): Boolean =
        src?.category.equals("short-drama", true) || src?.category.equals("dramabos", true)

    /** The proxy platform id for a premium source (e.g. "dramabox-compat" â†’ "dramabox"), or null when
     *  the source isn't premium or isn't a proxy-supported platform. */
    private fun premiumPlatformFor(src: SourceOverride): String? {
        if (!src.premium) return null
        if (
            src.sourceId.contains("dramabox", ignoreCase = true) &&
            listOf(src.apiBaseUrl, src.webBaseUrl.orEmpty()).any {
                it.contains("dramabox.goodbos.online", ignoreCase = true)
            }
        ) {
            return null
        }
        val p = src.sourceId.removeSuffix("-compat").lowercase(Locale.ROOT)
        return if (p in supportedPremium) p else null
    }

    /** Cache-first per-source search: premium proxy â†’ panel cache (proxy_enabled) â†’ live scrape. */
    private suspend fun searchSource(src: SourceOverride, query: String): List<LiveItem> {
        // Premium sources are served by the authed DramaBos proxy (entitlement-gated server-side),
        // never scraped on-device. browsableSources() already kept these out for non-subscribers.
        premiumPlatformFor(src)?.let { platform ->
            val token = AuthManager.idToken() ?: return emptyList()
            return runCatching { trackSource(src.sourceId) { premiumApi()?.search(token, platform, query).orEmpty() } }.getOrDefault(emptyList())
        }
        if (src.proxyEnabled && !src.proxyPaths?.search.isNullOrBlank()) {
            // Treat an EMPTY cache hit as a miss: the panel's generic scraper can't read bespoke
            // sources (e.g. oploverz's Next.js JSON API) and returns `{"items":[]}` with HTTP 200,
            // which would otherwise suppress the on-device live scrape that *can* search them.
            runCatching { trackSource(src.sourceId) { catalogApi()?.search(src, query).orEmpty() } }
                .getOrDefault(emptyList()).takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (isManagedShortDrama(src)) preparePremiumProxy()
        return runCatching { trackSource(src.sourceId) { LiveSource.search(src.apiBaseUrl, query) } }.getOrDefault(emptyList())
    }

    /** Detail for a source URL: premium proxy (tnpremium://) â†’ panel cache (proxy_enabled) â†’ live. */
    suspend fun liveDetail(url: String): LiveDetail? {
        parsePremiumUrl(url)?.let { (platform, bookId) ->
            val token = AuthManager.idToken() ?: return null
            val sourceId = panelSources.firstOrNull { premiumPlatformFor(it) == platform }?.sourceId.orEmpty()
            return if (sourceId.isBlank()) premiumApi()?.detail(token, platform, bookId)
            else runCatching { trackSource(sourceId) { premiumApi()?.detail(token, platform, bookId) } }.getOrNull()
        }
        val src = sourceForUrl(url)
        if (isManagedShortDrama(src)) preparePremiumProxy()
        if (src != null && src.proxyEnabled && !src.proxyPaths?.detail.isNullOrBlank()) {
            val cached = runCatching { trackSource(src.sourceId) { catalogApi()?.detail(src, url) } }.getOrNull()
            if (cached != null && !shouldRetryLiveDetail(url, cached)) return cached
            return runCatching { trackSource(src.sourceId) { LiveSource.detail(url) } }.getOrNull() ?: cached
        }
        return runCatching {
            if (src == null) LiveSource.detail(url) else trackSource(src.sourceId) { LiveSource.detail(url) }
        }.getOrNull()
    }

    private fun shouldRetryLiveDetail(url: String, detail: LiveDetail): Boolean {
        val u = url.lowercase(Locale.ROOT)
        val syn = detail.synopsis.orEmpty().lowercase(Locale.ROOT)
        if (("watch streaming" in syn || "nonton streaming" in syn) && "download" in syn) return true
        if ("kuramanime" in u && ("catatan: sinopsis" in syn || " tipe:" in syn || " genre:" in syn)) return true
        if ("nontonanimeid" in u && (("english:" in syn && "synonyms:" in syn) || "currently airing" in syn || "daftar episode" in syn)) return true
        if ("nekopoi" in u && syn.isBlank()) return true
        if (detail.genres.any { it.length > 32 || it.contains("download", true) || it.contains("lapor", true) }) return true
        if ("reelshort" in u && detail.episodes.isEmpty()) return true
        // PusatFilm: the panel cache may hold a stale, single-season detail scraped before the
        // season-accordion parser existed (its episodes carry no season data). Re-scrape live so the
        // full multi-season list shows; a fresh parse always tags episodes with a season.
        if ("pusatfilm" in u && detail.episodes.none { it.season != null }) return true
        if ("nekopoi" in u && detail.episodes.size <= 1 && ("/hentai/" in u || "-episode-" in u)) return true
        return false
    }

    // ---------------- live scraping (Home rails fetched from each source's real web page) ----------------

    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Live posters per Home-section URL. Snapshot-backed: filling a key recomposes the rail. */
    private val liveSections = mutableStateMapOf<String, List<PosterItem>>()
    private val liveSectionSources = mutableStateMapOf<String, String>()
    private val liveNextUrls = mutableStateMapOf<String, String>()
    private val liveLoadingMore = mutableStateMapOf<String, Boolean>()
    private val liveRequested = Collections.synchronizedSet(HashSet<String>())
    private val prefetchedPages = Collections.synchronizedMap(HashMap<String, LivePage>())
    private val prefetchingPages = Collections.synchronizedSet(HashSet<String>())
    // Cap how many Home rails fetch at once. The Home list is a plain scrolling Column (not lazy), so
    // every section's LaunchedEffect fires ensureLiveSection() on first render — without this gate all
    // N source rails would hit their (distinct) hosts in parallel on cold start, a TLS-handshake + GC
    // burst. Visible rails still fill first; lower rails queue and run as permits free.
    // ponytail: fixed 3-permit gate; raise if rails feel slow to appear.
    private val sectionFetchGate = Semaphore(3)

    /** Reactive read for a section's live posters; null until the fetch lands. */
    fun liveSectionPosters(url: String): List<PosterItem>? = liveSections[url]
    fun liveSectionCanLoadMore(url: String): Boolean = !liveNextUrls[url].isNullOrBlank()
    fun liveSectionLoadingMore(url: String): Boolean = liveLoadingMore[url] == true

    private fun primeHomeSections(limit: Int = 1) {
        if (panelSources.isEmpty()) return
        homeSectionsAll().take(limit).forEach { sec ->
            ensureLiveSection(sec.url, sec.sourceId)
        }
    }

    /**
     * Kick off (once) a fill of [url]'s Home rail. Proxy-enabled sources are fetched per configured
     * section URL so one slow/empty rail (e.g. Film) cannot make the whole source look "done".
     */
    fun ensureLiveSection(url: String, sourceId: String) {
        if (url.isBlank()) return
        if (!liveRequested.add(url)) return
        val src = sourceById(sourceId)
        val customOploverzCatalog = "oploverz" in "$sourceId $url".lowercase(Locale.ROOT)
        if (src != null && src.proxyEnabled && !src.proxyPaths?.catalog.isNullOrBlank() && !customOploverzCatalog) {
            liveScope.launch { sectionFetchGate.withPermit { fillSectionCached(url, src) } }
            return
        }
        liveScope.launch { sectionFetchGate.withPermit { fillSectionLive(url, sourceId) } }
    }

    private suspend fun fillSectionCached(url: String, src: SourceOverride) {
        val section = cachedSectionForUrl(src, url)
        val items = section?.items.orEmpty()
        if (items.isNotEmpty()) {
            val posters = items.take(20).map { enrichPosterCover(liveToPoster(it, src.sourceId), src) }
            val keys = linkedSetOf(url, section?.url.orEmpty()).filter { it.isNotBlank() }
            val next = section?.nextUrl?.takeIf { it.isNotBlank() }
                ?: nextKnownCatalogPageUrl(url, src.sourceId, hasItems = true)
            keys.forEach { key ->
                liveSections[key] = posters
                liveSectionSources[key] = src.sourceId
                next?.let { liveNextUrls[key] = it } ?: liveNextUrls.remove(key)
            }
            android.util.Log.i(
                "TnPagination",
                "seed cache source=${src.sourceId} items=${items.size} next=${next ?: "none"} url=$url",
            )
            prefetchNextPage(src, src.sourceId, next)
        } else {
            fillSectionFallback(url, src.sourceId, src)
            liveRequested.remove(url)
            liveNextUrls.remove(url)
        }
    }

    private suspend fun cachedSectionForUrl(src: SourceOverride, url: String): CachedCatalogSection? {
        val api = catalogApi() ?: return null
        val index = configuredCatalogUrls(src).indexOfFirst { sameSectionUrl(it, url) }
        return withTimeoutOrNull(HOME_CACHE_WAIT_MS) {
            if (index >= 0) {
                runCatching { trackSource(src.sourceId) { api.homePage(src, index + 1) } }.getOrNull()
            } else {
                runCatching { trackSource(src.sourceId) { api.listPage(src, url) } }.getOrNull()
                    ?.let { CachedCatalogSection(url, it.items, it.nextUrl) }
            }
        }
    }

    private suspend fun cachedListPage(src: SourceOverride, url: String): LivePage? =
        withTimeoutOrNull(HOME_CACHE_WAIT_MS) {
            runCatching { trackSource(src.sourceId) { catalogApi()?.listPage(src, url) } }.getOrNull()
        }

    private suspend fun sourceListPage(url: String, sourceId: String = ""): LivePage? =
        runCatching {
            if (sourceId.isBlank()) LiveSource.listPage(url) else trackSource(sourceId) { LiveSource.listPage(url) }
        }.getOrNull()

    private suspend fun fetchListPage(src: SourceOverride?, url: String): LivePage? {
        if (isManagedShortDrama(src)) preparePremiumProxy()
        return if (src != null && src.proxyEnabled && !src.proxyPaths?.catalog.isNullOrBlank()) {
            // The cache is useful for page one, but several catalog cache entries either drop
            // nextUrl or ignore the requested upstream page. For load-more, prefer the source's
            // real page and retain cache as a network fallback.
            if (usesLiveCatalogPagination(src.sourceId)) {
                sourceListPage(url, src.sourceId)?.takeIf { it.items.isNotEmpty() }
                    ?: cachedListPage(src, url)
            } else {
                cachedListPage(src, url)
            }
        } else {
            sourceListPage(url, src?.sourceId.orEmpty())
        }
    }

    private fun usesLiveCatalogPagination(sourceId: String): Boolean {
        val id = sourceId.lowercase(Locale.ROOT)
        return listOf(
            "kuramanime", "lk21", "nontondrama", "nontonanime", "oploverz", "pusatfilm", "samehadaku",
        ).any { it in id }
    }

    private suspend fun fillSectionLive(url: String, sourceId: String) {
        val source = sourceById(sourceId)
        if (isManagedShortDrama(source)) preparePremiumProxy()
        val premiumPlatform = source?.let { premiumPlatformFor(it) }
        val page = if (premiumPlatform != null) {
            // Premium rail: pull from the authed DramaBos proxy (ignores the scrape url), not on-device.
            LivePage(AuthManager.idToken()?.let { token ->
                runCatching { trackSource(sourceId) { premiumApi()?.list(token, premiumPlatform).orEmpty() } }.getOrDefault(emptyList())
            } ?: emptyList())
        } else {
            runCatching { trackSource(sourceId) { LiveSource.listPage(url) } }.getOrDefault(LivePage(emptyList()))
        }
        val items = page.items
        val src = sourceById(sourceId)
        if (items.isNotEmpty()) {
            val posters = items.take(20).map { enrichPosterCover(liveToPoster(it, sourceId), src) }
            liveSections[url] = posters
            liveSectionSources[url] = sourceId
        } else {
            fillSectionFallback(url, sourceId, src)
        }
        val next = page.nextUrl?.takeIf { it.isNotBlank() }
            ?: nextKnownCatalogPageUrl(url, sourceId, items.isNotEmpty())
        next?.let { liveNextUrls[url] = it } ?: liveNextUrls.remove(url)
        android.util.Log.i(
            "TnPagination",
            "seed live source=$sourceId items=${items.size} next=${next ?: "none"} url=$url",
        )
        prefetchNextPage(src, sourceId, next)
        if (items.isEmpty()) liveRequested.remove(url)
    }

    private suspend fun fillSectionFallback(url: String, sourceId: String, src: SourceOverride?) {
        val fallback = postersForSource(sourceId)
            .take(20)
            .map { enrichPosterCover(it, src) }
        if (shouldSuppressCompatFallback(url, sourceId, fallback)) {
            android.util.Log.w("TnHome", "$sourceId: live section empty for $url; suppressing bundled compat fallback")
            liveSections[url] = emptyList()
            liveSectionSources[url] = sourceId
            return
        }
        liveSections[url] = fallback
        liveSectionSources[url] = sourceId
    }

    private fun shouldSuppressCompatFallback(url: String, sourceId: String, fallback: List<PosterItem>): Boolean {
        val haystack = "$sourceId $url".lowercase(Locale.ROOT)
        val goodBosShortDrama = listOf(
            "melolo", "flickreels", "reelshort", "flextv", "freereels", "dramabox",
            "bilitv", "dotdrama", "dramawave", "dramabite", "goodshort", "shortmax", "fundrama", "idrama",
            "stardust", "velolo", "happyshort", "dramanova", "netshort", "reelife", "microdrama",
        ).any { it in haystack }
        if (!goodBosShortDrama) return false
        if (fallback.isEmpty()) return true
        return fallback.all { poster ->
            val title = poster.title.lowercase(Locale.ROOT)
            title.endsWith(" pilot") || title.endsWith(" starter") || "fallback" in title
        }
    }

    fun loadMoreLiveSection(url: String, sourceId: String) {
        val next = liveNextUrls[url]?.takeIf { it.isNotBlank() } ?: return
        if (liveLoadingMore[url] == true) return
        android.util.Log.i("TnPagination", "request source=$sourceId next=$next root=$url")
        liveLoadingMore[url] = true
        liveScope.launch {
            try {
                val src = sourceById(sourceId)
                val page = synchronized(prefetchedPages) { prefetchedPages.remove(next) }
                    ?: fetchListPage(src, next)
                if (page == null) {
                    liveNextUrls.remove(url)
                    android.util.Log.w("TnPagination", "stop source=$sourceId reason=no-page next=$next")
                    return@launch
                }
                val newPosters = page.items.map { enrichPosterCover(liveToPoster(it, sourceId), src) }
                var addedAny = false
                if (newPosters.isNotEmpty()) {
                    val seen = HashSet<String>()
                    val before = liveSections[url].orEmpty()
                    before.forEach { seen.add(it.url ?: dedupKey(it.title)) }
                    val fresh = newPosters.filter { seen.add(it.url ?: dedupKey(it.title)) }
                    addedAny = fresh.isNotEmpty()
                    if (addedAny) {
                        liveSections[url] = before + fresh
                    }
                    liveSectionSources[url] = sourceId
                }
                val newerNext = if (addedAny) {
                    page.nextUrl?.takeIf { it.isNotBlank() }
                        ?: nextKnownCatalogPageUrl(next, sourceId, page.items.isNotEmpty())
                } else null
                newerNext?.let { liveNextUrls[url] = it } ?: liveNextUrls.remove(url)
                android.util.Log.i(
                    "TnPagination",
                    "result source=$sourceId fetched=${page.items.size} added=$addedAny next=${newerNext ?: "none"}",
                )
                prefetchNextPage(src, sourceId, newerNext)
            } finally {
                liveLoadingMore.remove(url)
            }
        }
    }

    private fun prefetchNextPage(src: SourceOverride?, sourceId: String, nextUrl: String?) {
        val next = nextUrl?.takeIf { it.isNotBlank() } ?: return
        if (synchronized(prefetchedPages) { prefetchedPages.containsKey(next) }) return
        if (!prefetchingPages.add(next)) return
        liveScope.launch {
            try {
                val page = fetchListPage(src, next) ?: return@launch
                if (page.items.isNotEmpty()) {
                    synchronized(prefetchedPages) { prefetchedPages[next] = page }
                }
            } finally {
                prefetchingPages.remove(next)
            }
        }
    }

    private fun configuredCatalogUrls(src: SourceOverride): List<String> {
        val configured = (src.homeLinks?.showAll.orEmpty() + src.homeLinks?.showOnClick.orEmpty())
            .map { it.url }
            .filter { it.isNotBlank() }
            .distinct()
        return configured.ifEmpty { listOf(sourceHomeUrl(src)).filter { it.isNotBlank() } }
    }

    private fun sameSectionUrl(a: String, b: String): Boolean =
        normalizedSectionUrl(a).equals(normalizedSectionUrl(b), ignoreCase = true)

    private fun normalizedSectionUrl(url: String): String =
        url.substringBefore('#').trim().trimEnd('/')

    /** Map a scraped catalog card to a poster, badging by the source's real type/category. */
    private fun liveToPoster(item: LiveItem, sourceId: String): PosterItem {
        val src = sourceById(sourceId)
        return PosterItem(
            title = item.title,
            sub = item.status?.ifBlank { null } ?: src?.displayName?.ifBlank { null } ?: item.type.orEmpty(),
            art = artOf(item.url),
            badge = item.type?.ifBlank { null } ?: liveCategoryBadge(src?.category),
            cover = item.cover,
            url = item.url,
        )
    }

    private suspend fun enrichPosterCover(poster: PosterItem, src: SourceOverride?): PosterItem {
        val existing = poster.cover?.takeIf { it.isNotBlank() }
        val original = originalFromPanelImageProxy(existing)
        val fragileProxy = original != null && src?.sourceId?.contains("winbu", ignoreCase = true) == true
        if (existing != null && !fragileProxy) return poster
        val resolved = CoverResolver.resolve(poster.title, allowAdult = sourceAllowsAdult(src, poster.badge))
        val cover = resolved?.takeIf { it.isNotBlank() } ?: original ?: existing
        return if (cover.isNullOrBlank()) poster else poster.copy(cover = cover)
    }

    private fun originalFromPanelImageProxy(url: String?): String? = runCatching {
        if (url.isNullOrBlank() || "/api/v1/image" !in url) return@runCatching null
        val rawQuery = java.net.URI(url).rawQuery.orEmpty()
        val encoded = rawQuery.split('&')
            .firstOrNull { it.substringBefore('=') == "u" }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val b64 = URLDecoder.decode(encoded, "UTF-8")
            .replace('-', '+')
            .replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }.getOrNull()

    private fun sourceAllowsAdult(src: SourceOverride?, badge: String?): Boolean {
        val haystack = listOf(
            src?.sourceId,
            src?.displayName,
            src?.category,
            badge,
        ).joinToString(" ").lowercase(Locale.ROOT)
        return "nekopoi" in haystack || "javhey" in haystack || "indomax21" in haystack ||
            "hentai" in haystack || "adult" in haystack || "mature" in haystack
    }

    private fun liveCategoryBadge(category: String?): String {
        val cat = category.orEmpty()
        return when {
            cat.contains("drama", true) || cat.contains("drakor", true) || cat.contains("dracin", true) -> "Drama"
            cat.contains("movie", true) || cat.contains("film", true) -> "Movie"
            else -> "Anime"
        }
    }

    // ---------------- live search (across every enabled source, streamed + deduped) ----------------

    /** Reactive result groups â€” one per extension, appended as each source returns. */
    val liveSearchGroups = mutableStateListOf<SearchGroup>()
    var liveSearchLoading by mutableStateOf(false)
        private set
    private var liveSearchQuery = ""
    private var liveSearchJob: Job? = null
    /** Search terms already reported to the panel this session (avoid re-posting the same query). */
    private val reportedSearchTerms = Collections.synchronizedSet(HashSet<String>())

    /**
     * Resolve a live source-page URL for [title] by searching every enabled source in parallel and
     * taking the first relevant match. Lets the Detail screen load REAL data for items that carry no
     * URL (recommendations, bundled-catalog browse) instead of falling back to synthetic placeholders.
     * Races the searches and cancels the rest as soon as one source matches, so it stays snappy.
     */
    suspend fun resolveLiveUrl(title: String): String? {
        if (!hasLiveSources || title.isBlank()) return null
        val needle = title.lowercase(Locale.ROOT).trim()
        val tokens = needle.split(' ').filter { it.length > 1 }
        fun matches(t: String): Boolean {
            val tl = t.lowercase(Locale.ROOT)
            return tl == needle || tl.contains(needle) || (tokens.size >= 2 && tokens.all { tl.contains(it) })
        }
        return coroutineScope {
            val found = CompletableDeferred<String?>()
            val workers = browsableSources().map { src ->
                launch {
                    searchSource(src, title)
                        .firstOrNull { matches(it.title) }
                        ?.let { found.complete(it.url) }
                }
            }
            launch { workers.joinAll(); found.complete(null) } // no source matched
            found.await().also { coroutineContext.cancelChildren() }
        }
    }

    /**
     * Run a fresh live search across all enabled sources (no-op if the query is unchanged).
     * Results are grouped **per extension** (each source keeps its own posters â€” no cross-source
     * dedup), so the UI can render one horizontal row per source. Only within a single source are
     * duplicate titles collapsed.
     */
    fun startLiveSearch(query: String) {
        val q = query.trim()
        val retrying = q == liveSearchQuery
        if (retrying && liveSearchJob?.isActive == true) return
        liveSearchQuery = q
        liveSearchJob?.cancel()
        if (!retrying) liveSearchGroups.clear()
        if (q.length < 2 || !hasLiveSources) { liveSearchLoading = false; return }
        reportSearchTerm(q)
        // Premium sources are excluded for non-subscribers so their content never leaks into results.
        val completedSources = liveSearchGroups.mapTo(HashSet()) { it.sourceId }
        val sources = browsableSources().filterNot { it.sourceId in completedSources }
        // Sites that don't honour `/?s=` just echo their homepage; keep only titles that actually
        // match the query so the results stay relevant instead of leaking unrelated "latest" cards.
        liveSearchLoading = true
        liveSearchJob = liveScope.launch {
            val timeout = launch {
                delay(SEARCH_SETTLE_TIMEOUT_MS)
                withContext(Dispatchers.Main.immediate) {
                    if (liveSearchQuery == q) liveSearchLoading = false
                }
            }
            try {
                coroutineScope {
                    sources.forEach { src ->
                        launch {
                            val startedAt = android.os.SystemClock.elapsedRealtime()
                            android.util.Log.d("TnSearch", "${src.sourceId}: start")
                            val hits = searchSource(src, q)
                            android.util.Log.d(
                                "TnSearch",
                                "${src.sourceId}: ${hits.size} hits in " +
                                    "${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
                            )
                            val trusted = trustSearchResults(premiumPlatformFor(src) != null, src.apiBaseUrl)
                            val seen = HashSet<String>() // dedup WITHIN this source only
                            val posters = hits.mapNotNull { item ->
                                val tl = item.title.lowercase(Locale.ROOT)
                                val relevant = trusted || LiveSource.matchesSearchQuery(item.title, q)
                                if (relevant && seen.add(tl)) liveToPoster(item, src.sourceId) else null
                            }.map { enrichPosterCover(it, src) }
                            if (posters.isNotEmpty()) {
                                withContext(Dispatchers.Main.immediate) {
                                    if (liveSearchQuery == q) {
                                        liveSearchGroups.add(
                                            SearchGroup(src.sourceId, src.displayName.ifBlank { src.sourceId }, posters)
                                        )
                                        liveSearchGroups.sortBy { it.displayName.lowercase(Locale.ROOT) }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                timeout.cancel()
                withContext(Dispatchers.Main.immediate) {
                    if (liveSearchQuery == q) liveSearchLoading = false
                }
            }
        }
    }

    /** Report a committed search term to the panel (best-effort, once per distinct term per session). */
    private fun reportSearchTerm(term: String) {
        if (!AuthManager.signedIn || !hasPanel || telemetryToken.isBlank()) return
        if (!reportedSearchTerms.add(term.lowercase(Locale.ROOT))) return
        liveScope.launch { TrendingApi(panelBase).reportSearch(term, telemetryToken, installId) }
    }

    /** Report an opened title to the panel so it can feed the cross-user "Trending minggu ini" rail. */
    fun reportOpen(title: String, url: String?, cover: String?, badge: String?, sourceId: String? = null) {
        if (!AuthManager.signedIn || !hasPanel || telemetryToken.isBlank() || url.isNullOrBlank()) return
        val resolvedSourceId = sourceId?.ifBlank { null } ?: sourceIdForUrl(url)
        liveScope.launch { TrendingApi(panelBase).reportOpen(title, url, cover, badge, resolvedSourceId, telemetryToken, installId) }
    }

    // ---------------- Extensions screen ----------------

    val totalExtensions: Int
        get() = if (hasPanel) catalogSources().size else exts.size

    fun extCategories(): List<ExtCategory> = when {
        hasPanel -> catalogSources()
            .groupBy { (it.category ?: "lainnya").ifBlank { "lainnya" } }
            .map { (cat, list) -> ExtCategory(id = cat, label = prettyCategory(cat), count = list.size) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        exts.isNotEmpty() -> exts.groupBy { it.category.ifBlank { "Lainnya" } }
            .map { (cat, list) -> ExtCategory(id = cat, label = cat, count = list.size) }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        else -> emptyList()
    }

    fun extItems(categoryId: String): List<ExtItem> = when {
        // Premium sources stay listed (installable) but are flagged so the Extensions UI can show a
        // "Terkunci" badge until the user subscribes â€” install is allowed, content stays gated.
        hasPanel -> catalogSources().filter { (it.category ?: "lainnya").ifBlank { "lainnya" } == categoryId }
            .sortedBy { it.displayName.ifBlank { it.sourceId }.lowercase(Locale.ROOT) }
            .map {
                ExtItem(
                    name = it.displayName.ifBlank { it.sourceId },
                    source = if (it.lastProbeStatus == "ok") "Live" else "Lokal",
                    live = it.lastProbeStatus == "ok",
                    premium = it.premium,
                    locked = premiumLocked(it),
                    iconUrl = faviconUrl(registryIconBase(it).ifBlank { it.webBaseUrl.orEmpty().ifBlank { it.apiBaseUrl } }),
                    sourceId = it.sourceId,
                    installed = isExtInstalled(it.sourceId),
                )
            }
        exts.isNotEmpty() -> exts.filter { it.category == categoryId }
            .map {
                ExtItem(
                    name = it.name,
                    source = if (it.apiBaseUrl != null) "Live" else "Lokal",
                    live = it.apiBaseUrl != null,
                    iconUrl = faviconUrl(it.webBaseUrl ?: it.upstreamUrl ?: it.apiBaseUrl),
                )
            }
        else -> emptyList()
    }

    private fun faviconUrl(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (!raw.startsWith("http", ignoreCase = true)) return ""
        sourceIconOverride(raw)?.let { return it }
        return "https://www.google.com/s2/favicons?sz=128&domain_url=${URLEncoder.encode(raw, "UTF-8")}"
    }

    private fun sourceIconOverride(url: String): String? = when {
        "anichin.moe" in url -> "https://i2.wp.com/anichin.moe/wp-content/uploads/2023/05/cropped-7c6qsksq-1-300x300.png"
        else -> null
    }

    private fun registryIconBase(src: SourceOverride): String =
        exts.firstOrNull { ext ->
            ext.id == src.sourceId ||
                ext.id.removeSuffix("-compat") == src.sourceId.removeSuffix("-compat") ||
                ext.name.equals(src.displayName, ignoreCase = true)
        }?.let { it.webBaseUrl ?: it.upstreamUrl ?: it.apiBaseUrl }.orEmpty()

    // ---------------- Home: source filter + dynamic sections ----------------

    /** "Semua" + each enabled panel source that has at least one Home-section link. */
    fun homeSources(): List<HomeSourceChip> {
        if (!hasLiveSources) return listOf(HomeSourceChip(null, "Semua"))
        val sources = orderedHomeSources()
            .filter { !it.homeLinks?.showAll.isNullOrEmpty() || !it.homeLinks?.showOnClick.isNullOrEmpty() || sourceHomeUrl(it).isNotBlank() }
            .map { HomeSourceChip(it.sourceId, it.displayName.ifBlank { it.sourceId }) }
        return listOf(HomeSourceChip(null, "Semua")) + sources
    }

    /** Mode "Semua": one row per source's `showAll` link, content = that source's catalog. */
    fun homeSectionsAll(): List<HomeSection> {
        if (!hasPanel) return emptyList()
        val sources = orderedHomeSources()
        val hasCuratedLinks = sources.any { !it.homeLinks?.showAll.isNullOrEmpty() }
        val visibleSources = if (hasCuratedLinks) sources else sources.take(8)
        return visibleSources.flatMap { src ->
            homeLinksFor(src, sourceMode = false).map { link ->
                // Do not parse every bundled compat catalog on the UI thread during Home startup.
                // fillSectionFallback() resolves this source's local posters on the IO worker only
                // when its visible live rail cannot provide content.
                HomeSection(link.label.ifBlank { src.displayName }, src.sourceId, link.url, emptyList())
            }
        }.filter { it.url.isNotBlank() }
    }

    /** Mode "Klik Source": the source's `showOnClick` links, catalog sliced so rows differ. */
    fun homeSectionsForSource(sourceId: String): List<HomeSection> {
        val src = sourceById(sourceId) ?: return emptyList()
        val links = homeLinksFor(src, sourceMode = true).take(6)
        val posters = postersForSource(sourceId)
        if (links.isEmpty()) return emptyList()
        if (posters.isEmpty()) {
            return links.map { link ->
                HomeSection(link.label.ifBlank { src.displayName }, sourceId, link.url, emptyList())
            }.filter { it.url.isNotBlank() }
        }
        // Give each section a distinct slice of the catalog (no repeats); drop sections that run dry.
        val per = maxOf(4, kotlin.math.ceil(posters.size / links.size.toDouble()).toInt())
        return links.mapIndexed { i, link ->
            HomeSection(link.label.ifBlank { src.displayName }, sourceId, link.url, posters.drop(i * per).take(per))
        }.filter { it.url.isNotBlank() }
    }

    /**
     * Prefer panel-curated Home Links. If none are configured yet, synthesize a live row from the
     * panel source itself so Home still reads from the VPS/source feed instead of SampleData.
     */
    private fun homeLinksFor(src: SourceOverride, sourceMode: Boolean): List<HomeLink> {
        val configured = if (sourceMode) {
            src.homeLinks?.showOnClick.orEmpty().ifEmpty { src.homeLinks?.showAll.orEmpty() }
        } else {
            src.homeLinks?.showAll.orEmpty()
        }
        if (configured.isNotEmpty()) return configured
        val url = sourceHomeUrl(src)
        if (url.isBlank()) return emptyList()
        val label = if (sourceMode) "Terbaru" else src.displayName.ifBlank { src.sourceId }
        return listOf(HomeLink(label = label, url = url))
    }

    private fun sourceHomeUrl(src: SourceOverride): String =
        src.apiBaseUrl.ifBlank { src.webBaseUrl.orEmpty() }

    /**
     * Live "Sorotan Hari Ini" â€” one real trending title per filled source (variety + real covers),
     * reactive to [liveSections]. Falls back to the bundled [spots] until live data lands.
     */
    fun liveSpots(): List<SpotItem> {
        if (liveSections.isEmpty()) return spots
        val seen = HashSet<String>()
        val picks = liveSections.values
            .mapNotNull { it.firstOrNull() }
            .filter { !it.cover.isNullOrBlank() && seen.add(dedupKey(it.title)) }
            .take(6)
        if (picks.isEmpty()) return spots
        return picks.map { p ->
            SpotItem(
                title = p.title,
                sub = p.badge ?: "Trending",
                syn = "Lagi ramai ditonton - buka untuk sinopsis & episode lengkap.",
                tags = listOfNotNull(p.badge?.let { SpotTag(iconFor(it), it) }),
                rating = "", eps = "", status = p.badge.orEmpty(),
                art = artOf(p.url ?: p.title), cover = p.cover, url = p.url,
            )
        }
    }

    private fun postersForSource(sourceId: String): List<PosterItem> {
        val r = registry ?: return emptyList()
        val ext = r.extensionById(sourceId) ?: return emptyList()
        return r.catalogItems(ext).map { catalogToPoster(ext, it) }.take(15)
    }

    // ---------------- catalog content (lazy) â€” Search, spotlight, detail enrichment ----------------

    private val exts: List<RegistryExtension> get() = registry?.extensions ?: emptyList()

    private data class Entry(val ext: RegistryExtension, val item: CatalogItem)

    private val allItems: List<Entry> by lazy {
        val r = registry ?: return@lazy emptyList()
        exts.flatMap { ext -> r.catalogItems(ext).map { Entry(ext, it) } }
            .distinctBy { it.item.title.lowercase(Locale.ROOT) }
    }

    val posters: List<PosterItem> by lazy {
        allItems.map { catalogToPoster(it.ext, it.item) }
    }

    /** Bundled-catalog posters for Home rails / fallback, with drama hidden (it lives on its own surface). */
    val homePosters: List<PosterItem> by lazy {
        allItems.filterNot { isDramaCategory(it.ext.category) }.map { catalogToPoster(it.ext, it.item) }
    }

    val spots: List<SpotItem> by lazy {
        val withSyn = allItems.filterNot { isDramaCategory(it.ext.category) }.filter { !it.item.overview.isNullOrBlank() }
        if (withSyn.isEmpty()) return@lazy emptyList()
        withSyn.take(6).map { e ->
            val it = e.item
            SpotItem(
                title = it.title, sub = it.tagline ?: e.ext.name, syn = it.overview ?: "",
                tags = it.genres.take(4).map { g -> SpotTag(iconFor(g), g) }.ifEmpty { listOf(SpotTag("sparkle", e.ext.name)) },
                rating = ratingOf(it.id), eps = epsOf(it.id), status = statusOf(it), art = artOf(it.id),
            )
        }
    }

    private val byTitle: Map<String, Entry> by lazy { allItems.associateBy { it.item.title.lowercase(Locale.ROOT) } }

    fun search(q: String): List<PosterItem> {
        val query = q.trim().lowercase(Locale.ROOT)
        if (query.isEmpty()) return posters
        if (allItems.isEmpty()) return emptyList()
        return allItems.filter { e ->
            e.item.title.lowercase(Locale.ROOT).contains(query) ||
                e.item.genres.any { it.lowercase(Locale.ROOT).contains(query) } ||
                e.ext.name.lowercase(Locale.ROOT).contains(query)
        }.map { catalogToPoster(it.ext, it.item) }
    }

    /** "Rekomendasi serupa": use live/cache posters across extensions, then fall back to bundled catalog. */
    fun recommendations(excludeTitle: String, n: Int = 6, nearUrl: String? = null): List<PosterItem> {
        val seen = HashSet<String>().apply { add(dedupKey(excludeTitle)) }
        val live = liveRecommendationCandidates()
        return (live + posters).filter { p ->
            val k = dedupKey(p.title)
            k.isNotBlank() && seen.add(k)
        }.take(n)
    }

    private fun liveRecommendationCandidates(): List<PosterItem> {
        if (liveSections.isEmpty()) return emptyList()
        return liveSections.entries.toList().flatMap { it.value }
    }

    /** Collapse "Supreme God Emperor Episode 200" / "â€¦ Season 2" to the same series key. */
    private fun dedupKey(title: String): String = title.lowercase(Locale.ROOT)
        .replace(Regex("\\b(episode|ep)\\s*\\d+.*$"), "")
        .replace(Regex("\\b(final\\s+)?season\\s*\\d*\\b"), "")
        .replace(Regex("\\bs\\d+\\b"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun detailArgFor(p: PosterItem): DetailArg {
        // Live poster (scraped page OR premium tnpremium:// item): carry its url + cover so Detail
        // fetches live (liveDetail routes a tnpremium:// url through the DramaBos proxy).
        val url = p.url
        if (url != null && (url.startsWith("http") || url.startsWith("$PREMIUM_SCHEME://"))) {
            return DetailArg(
                title = p.title, sub = p.sub, art = p.art,
                badge = if (url.startsWith("$PREMIUM_SCHEME://")) "Drama" else (p.badge ?: "Anime"),
                ep = p.ep, progress = p.progress ?: 0, cover = p.cover, url = url,
            )
        }
        val e = byTitle[p.title.lowercase(Locale.ROOT)] ?: return DetailArg(
            title = p.title, sub = p.sub, art = p.art, badge = p.badge ?: "Donghua", ep = p.ep, progress = p.progress ?: 0,
        )
        val it = e.item
        return DetailArg(
            title = it.title, sub = it.tagline ?: e.ext.name, art = artOf(it.id),
            badge = badgeOf(it.contentType, e.ext.category, it.genres),
            ep = p.ep ?: epsOf(it.id).filter { c -> c.isDigit() }.ifEmpty { null },
            progress = p.progress ?: 0, syn = it.overview, rating = ratingOf(it.id), status = statusOf(it), genres = it.genres,
            url = it.id.takeIf { u -> u.startsWith("http") },
        )
    }

    // ---------------- mappers / cosmetic synthesis ----------------

    private fun catalogToPoster(ext: RegistryExtension, item: CatalogItem) = PosterItem(
        title = item.title,
        sub = listOfNotNull(item.yearLabel, item.genres.firstOrNull()).joinToString(" / ").ifBlank { ext.name },
        art = artOf(item.id),
        badge = badgeOf(item.contentType, ext.category, item.genres),
    )

    private fun prettyCategory(slug: String): String =
        slug.split('-', '_', ' ').joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }

    private fun artOf(s: String): Int { var h = 0; for (c in s) h = h * 31 + c.code; return ((h % 8) + 8) % 8 }
    private fun ratingOf(s: String): String { var h = 0; for (c in s) h += c.code; return String.format(Locale.US, "%.1f", 8.0 + (h % 10) / 10.0) }
    private fun epsOf(s: String): String { var h = 0; for (c in s) h += c.code * 7; return "${12 + (h % 24)} ep" }
    private fun statusOf(it: CatalogItem): String =
        if (it.releaseHint?.contains("tamat", true) == true || it.releaseHint?.contains("complete", true) == true) "Completed" else "Ongoing"
    /** Real content type from the catalog: contentType + the "Donghua" genre tag + the source category. */
    private fun badgeOf(ct: String?, category: String?, genres: List<String>): String {
        val cat = category.orEmpty()
        val drama = cat.contains("Drakor", true) || cat.contains("Dracin", true) || cat.contains("drama", true)
        return when {
            ct.equals("MOVIE", true) -> "Movie"
            ct.equals("SERIES", true) -> if (drama) "Drama" else "Movie"
            ct.equals("DRAMA", true) -> "Drama"
            genres.any { it.equals("Donghua", true) } -> "Donghua"
            ct.equals("ANIME", true) -> "Anime"
            drama -> "Drama"
            cat.contains("movie", true) -> "Movie"
            else -> "Anime"
        }
    }
    private fun iconFor(genre: String): String = when {
        genre.contains("Action", true) -> "zap"
        genre.contains("Fantasy", true) -> "sparkle"
        genre.contains("Cultivation", true) -> "shield"
        genre.contains("Romance", true) -> "heart"
        genre.contains("Donghua", true) || genre.contains("Anime", true) -> "flame2"
        else -> "star"
    }
}
