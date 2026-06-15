package com.tetonova.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tetonova.app.ui.DetailArg
import com.tetonova.core.model.ExtCategory
import com.tetonova.core.model.ExtItem
import com.tetonova.core.model.PosterItem
import com.tetonova.core.model.SampleData
import com.tetonova.core.model.SpotItem
import com.tetonova.core.model.SpotTag
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale

/** A Home row: custom panel label + the source it came from + that source's posters. */
data class HomeSection(val label: String, val sourceId: String, val url: String, val posters: List<PosterItem>)

/** A Home source-filter chip. `id == null` is the "Semua" (all sources) chip. */
data class HomeSourceChip(val id: String?, val label: String)

/**
 * App-facing data facade. The **control panel** (`/api/v1/sources`) is the source of truth for
 * which sources exist, their display names/categories and their Home-section links; the bundled
 * repo-tn catalogs supply the actual titles per source (the live catalog proxy is currently down).
 * Everything falls back to [SampleData] / the bundled registry when the panel is unreachable.
 */
object TnData {

    private var registry: ExtensionRegistry? = null

    fun init(context: Context) {
        if (registry != null) return
        registry = ExtensionRegistry(context.applicationContext).apply { load() }
        LiveClient.appContext = context.applicationContext
    }

    // ---------------- panel state ----------------

    private var panelSources: List<SourceOverride> = emptyList()

    /** Support/donation card config from the panel (null until fetched). */
    var supportMe: SupportMe? = null
        private set

    /** Telemetry ingest token from the panel — reused to auth the in-app problem report. */
    var telemetryToken: String = ""
        private set

    /** Changelog published by the panel (null until fetched); shown at Settings → Catatan rilis. */
    var releaseNotes by mutableStateOf<ReleaseNotes?>(null)
        private set

    /** Help-center content published by the panel; shown at Settings → Pusat bantuan. */
    var helpCenter by mutableStateOf<HelpCenter?>(null)
        private set

    /** Snapshot-state counter so panel-dependent UI recomposes after a fetch. */
    var panelVersion by mutableStateOf(0)
        private set

    suspend fun refreshFromPanel(panelUrl: String) {
        val resp = SourceApi(panelUrl).fetch() ?: return
        if (resp.sources.isNotEmpty()) panelSources = resp.sources
        if (resp.supportMe != null) supportMe = resp.supportMe
        resp.telemetry?.ingestToken?.takeIf { it.isNotBlank() }?.let { telemetryToken = it }
        resp.releaseNotes?.let { releaseNotes = it }
        resp.helpCenter?.let { helpCenter = it }
        // Hand the Cloudflare-bypass config to the live scraper so gated upstreams can be solved.
        resp.proxyBypass?.takeIf { it.enabled && it.flareSolverrEndpoint.isNotBlank() }?.let {
            LiveClient.flareEndpoint = it.flareSolverrEndpoint
            LiveClient.flareToken = it.flareSolverrToken
        }
        if (resp.sources.isNotEmpty() || resp.supportMe != null) panelVersion++
    }

    private fun enabledPanelSources() = panelSources.filter { it.enabled }
    private val hasPanel: Boolean get() = panelSources.isNotEmpty()

    // ---------------- live scraping (Home rails fetched from each source's real web page) ----------------

    private val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Live posters per Home-section URL. Snapshot-backed: filling a key recomposes the rail. */
    private val liveSections = mutableStateMapOf<String, List<PosterItem>>()
    private val liveRequested = Collections.synchronizedSet(HashSet<String>())

    /** Reactive read for a section's live posters; null until the fetch lands. */
    fun liveSectionPosters(url: String): List<PosterItem>? = liveSections[url]

    /** Kick off (once per URL) a live fetch of [url]'s catalog page. Safe to call every recompose. */
    fun ensureLiveSection(url: String, sourceId: String) {
        if (url.isBlank() || !liveRequested.add(url)) return
        liveScope.launch {
            val items = runCatching { LiveSource.list(url) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) {
                liveSections[url] = items.take(20).map { liveToPoster(it, sourceId) }
            } else {
                liveRequested.remove(url) // nothing came back — allow a later retry
            }
        }
    }

    /** Map a scraped catalog card to a poster, badging by the source's real type/category. */
    private fun liveToPoster(item: LiveItem, sourceId: String): PosterItem {
        val src = panelSources.firstOrNull { it.sourceId == sourceId }
        return PosterItem(
            title = item.title,
            sub = item.status?.ifBlank { null } ?: src?.displayName?.ifBlank { null } ?: item.type.orEmpty(),
            art = artOf(item.url),
            badge = item.type?.ifBlank { null } ?: liveCategoryBadge(src?.category),
            cover = item.cover,
            url = item.url,
        )
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

    /** Reactive result list — results from each source are appended as they arrive. */
    val liveSearchHits = mutableStateListOf<PosterItem>()
    var liveSearchLoading by mutableStateOf(false)
        private set
    private var liveSearchQuery = ""
    private var liveSearchJob: Job? = null

    /**
     * Resolve a live source-page URL for [title] by searching every enabled source in parallel and
     * taking the first relevant match. Lets the Detail screen load REAL data for items that carry no
     * URL (recommendations, bundled-catalog browse) instead of falling back to synthetic placeholders.
     * Races the searches and cancels the rest as soon as one source matches, so it stays snappy.
     */
    suspend fun resolveLiveUrl(title: String): String? {
        if (!hasPanel || title.isBlank()) return null
        val needle = title.lowercase(Locale.ROOT).trim()
        val tokens = needle.split(' ').filter { it.length > 1 }
        fun matches(t: String): Boolean {
            val tl = t.lowercase(Locale.ROOT)
            return tl == needle || tl.contains(needle) || (tokens.size >= 2 && tokens.all { tl.contains(it) })
        }
        return coroutineScope {
            val found = CompletableDeferred<String?>()
            val workers = enabledPanelSources().map { src ->
                launch {
                    runCatching { LiveSource.search(src.apiBaseUrl, title) }.getOrDefault(emptyList())
                        .firstOrNull { matches(it.title) }
                        ?.let { found.complete(it.url) }
                }
            }
            launch { workers.joinAll(); found.complete(null) } // no source matched
            found.await().also { coroutineContext.cancelChildren() }
        }
    }

    /** Run a fresh live search across all enabled sources (no-op if the query is unchanged). */
    fun startLiveSearch(query: String) {
        val q = query.trim()
        if (q == liveSearchQuery) return
        liveSearchQuery = q
        liveSearchJob?.cancel()
        liveSearchHits.clear()
        if (q.length < 2 || !hasPanel) { liveSearchLoading = false; return }
        val sources = enabledPanelSources()
        val seen = Collections.synchronizedSet(HashSet<String>())
        // Sites that don't honour `/?s=` just echo their homepage; keep only titles that actually
        // match the query so the results stay relevant instead of leaking unrelated "latest" cards.
        val needle = q.lowercase(Locale.ROOT)
        val tokens = needle.split(' ').filter { it.length > 1 }
        liveSearchLoading = true
        liveSearchJob = liveScope.launch {
            try {
                coroutineScope {
                    sources.forEach { src ->
                        launch {
                            val hits = runCatching { LiveSource.search(src.apiBaseUrl, q) }.getOrDefault(emptyList())
                            val posters = hits.mapNotNull { item ->
                                val tl = item.title.lowercase(Locale.ROOT)
                                val relevant = tl.contains(needle) || (tokens.isNotEmpty() && tokens.all { tl.contains(it) })
                                if (relevant && seen.add(tl)) liveToPoster(item, src.sourceId) else null
                            }
                            if (posters.isNotEmpty()) liveSearchHits.addAll(posters)
                        }
                    }
                }
            } finally {
                liveSearchLoading = false
            }
        }
    }

    // ---------------- Extensions screen ----------------

    val totalExtensions: Int
        get() = if (hasPanel) enabledPanelSources().size else (exts.size.takeIf { it > 0 } ?: SampleData.extItems.size)

    fun extCategories(): List<ExtCategory> = when {
        hasPanel -> enabledPanelSources()
            .groupBy { (it.category ?: "lainnya").ifBlank { "lainnya" } }
            .map { (cat, list) -> ExtCategory(id = cat, label = prettyCategory(cat), count = list.size) }
            .sortedByDescending { it.count }
        exts.isNotEmpty() -> exts.groupBy { it.category.ifBlank { "Lainnya" } }
            .map { (cat, list) -> ExtCategory(id = cat, label = cat, count = list.size) }
            .sortedByDescending { it.count }
        else -> SampleData.extCategories
    }

    fun extItems(categoryId: String): List<ExtItem> = when {
        hasPanel -> enabledPanelSources().filter { (it.category ?: "lainnya").ifBlank { "lainnya" } == categoryId }
            .map { ExtItem(name = it.displayName.ifBlank { it.sourceId }, source = if (it.lastProbeStatus == "ok") "Live" else "Lokal", live = it.lastProbeStatus == "ok") }
        exts.isNotEmpty() -> exts.filter { it.category == categoryId }
            .map { ExtItem(name = it.name, source = if (it.apiBaseUrl != null) "Live" else "Lokal", live = it.apiBaseUrl != null) }
        else -> SampleData.extItems
    }

    // ---------------- Home: source filter + dynamic sections ----------------

    /** "Semua" + each enabled panel source that has at least one Home-section link. */
    fun homeSources(): List<HomeSourceChip> {
        if (!hasPanel) return listOf(HomeSourceChip(null, "Semua"))
        val sources = enabledPanelSources()
            .filter { !it.homeLinks?.showAll.isNullOrEmpty() || !it.homeLinks?.showOnClick.isNullOrEmpty() }
            .map { HomeSourceChip(it.sourceId, it.displayName.ifBlank { it.sourceId }) }
        return listOf(HomeSourceChip(null, "Semua")) + sources
    }

    /** Mode "Semua": one row per source's `showAll` link, content = that source's catalog. */
    fun homeSectionsAll(): List<HomeSection> {
        if (!hasPanel) return emptyList()
        return enabledPanelSources().flatMap { src ->
            (src.homeLinks?.showAll ?: emptyList()).map { link ->
                HomeSection(link.label.ifBlank { src.displayName }, src.sourceId, link.url, postersForSource(src.sourceId))
            }
        }.filter { it.posters.isNotEmpty() }
    }

    /** Mode "Klik Source": the source's `showOnClick` links, catalog sliced so rows differ. */
    fun homeSectionsForSource(sourceId: String): List<HomeSection> {
        val src = panelSources.firstOrNull { it.sourceId == sourceId } ?: return emptyList()
        val links = (src.homeLinks?.showOnClick.orEmpty().ifEmpty { src.homeLinks?.showAll.orEmpty() }).take(6)
        val posters = postersForSource(sourceId)
        if (links.isEmpty() || posters.isEmpty()) return emptyList()
        // Give each section a distinct slice of the catalog (no repeats); drop sections that run dry.
        val per = maxOf(4, kotlin.math.ceil(posters.size / links.size.toDouble()).toInt())
        return links.mapIndexed { i, link ->
            HomeSection(link.label.ifBlank { src.displayName }, sourceId, link.url, posters.drop(i * per).take(per))
        }.filter { it.posters.isNotEmpty() }
    }

    /**
     * Live "Sorotan Hari Ini" — one real trending title per filled source (variety + real covers),
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
                syn = "Lagi ramai ditonton — buka untuk sinopsis & episode lengkap.",
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

    // ---------------- catalog content (lazy) — Search, spotlight, detail enrichment ----------------

    private val exts: List<RegistryExtension> get() = registry?.extensions ?: emptyList()

    private data class Entry(val ext: RegistryExtension, val item: CatalogItem)

    private val allItems: List<Entry> by lazy {
        val r = registry ?: return@lazy emptyList()
        exts.flatMap { ext -> r.catalogItems(ext).map { Entry(ext, it) } }
            .distinctBy { it.item.title.lowercase(Locale.ROOT) }
    }

    val posters: List<PosterItem> by lazy {
        if (allItems.isEmpty()) SampleData.posters else allItems.map { catalogToPoster(it.ext, it.item) }
    }

    val spots: List<SpotItem> by lazy {
        val withSyn = allItems.filter { !it.item.overview.isNullOrBlank() }
        if (withSyn.isEmpty()) return@lazy SampleData.spots
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
        if (allItems.isEmpty()) return SampleData.posters.filter { it.title.lowercase(Locale.ROOT).contains(query) }
        return allItems.filter { e ->
            e.item.title.lowercase(Locale.ROOT).contains(query) ||
                e.item.genres.any { it.lowercase(Locale.ROOT).contains(query) } ||
                e.ext.name.lowercase(Locale.ROOT).contains(query)
        }.map { catalogToPoster(it.ext, it.item) }
    }

    /** "Rekomendasi serupa" across ALL extensions, with one entry per show (no Episode/Season dupes). */
    fun recommendations(excludeTitle: String, n: Int = 6): List<PosterItem> {
        val seen = HashSet<String>().apply { add(dedupKey(excludeTitle)) }
        return posters.filter { p ->
            val k = dedupKey(p.title)
            k.isNotBlank() && seen.add(k)
        }.take(n)
    }

    /** Collapse "Supreme God Emperor Episode 200" / "… Season 2" to the same series key. */
    private fun dedupKey(title: String): String = title.lowercase(Locale.ROOT)
        .replace(Regex("\\b(episode|ep)\\s*\\d+.*$"), "")
        .replace(Regex("\\b(final\\s+)?season\\s*\\d*\\b"), "")
        .replace(Regex("\\bs\\d+\\b"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun detailArgFor(p: PosterItem): DetailArg {
        // Live poster (scraped from a real page): carry its detail URL + cover so Detail fetches live.
        if (p.url?.startsWith("http") == true) {
            return DetailArg(
                title = p.title, sub = p.sub, art = p.art, badge = p.badge ?: "Anime",
                ep = p.ep, progress = p.progress ?: 0, cover = p.cover, url = p.url,
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
        sub = listOfNotNull(item.yearLabel, item.genres.firstOrNull()).joinToString(" · ").ifBlank { ext.name },
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
