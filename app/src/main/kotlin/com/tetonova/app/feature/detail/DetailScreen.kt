package com.tetonova.app.feature.detail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tetonova.app.Secrets
import com.tetonova.app.data.AnimeInfo
import com.tetonova.app.data.CharacterInfo
import com.tetonova.app.data.CoverResolver
import com.tetonova.app.data.FollowedStore
import com.tetonova.app.data.HistoryStore
import com.tetonova.core.scraper.LiveDetail
import com.tetonova.app.data.MovieInfo
import com.tetonova.app.data.OmdbResolver
import com.tetonova.app.data.TnData
import com.tetonova.app.data.WatchProgressStore
import com.tetonova.app.data.download.DlMeta
import com.tetonova.app.data.download.DlUiState
import com.tetonova.app.data.download.DownloadCenter
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.EpRef
import com.tetonova.app.ui.PlayerArg
import com.tetonova.app.ui.bleedEnd
import com.tetonova.app.ui.detailWebLink
import com.tetonova.app.ui.toDetailArg
import com.tetonova.core.designsystem.Art
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.RoseGradientColors
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.theme.gradColors
import com.tetonova.core.designsystem.tnGradient
import com.tetonova.core.model.Episode
import com.tetonova.core.model.InfoRow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MAX_W = 1180.dp

private data class DetailData(
    val title: String, val sub: String, val rating: String, val year: String, val maturity: String,
    val epCount: Int, val badge: String, val art: Int, val genres: List<String>, val syn: String,
    val status: String, val prog: Int, val studio: String = "Nova Animation", val malId: Int = 0,
    val url: String? = null, val cover: String? = null, val episodesLive: List<Episode> = emptyList(),
    /** True when MAL or OMDb supplied the metadata (so the web doesn't override status/studio/genres). */
    val extMeta: Boolean = false, val omdbActors: List<String> = emptyList(),
)

/** Overlay OMDb (IMDB) facts for Movie/Drama: plot, rating, year, genres, director, poster, cast. */
private fun DetailData.withOmdb(m: MovieInfo?): DetailData {
    if (m == null) return this
    return copy(
        rating = m.rating ?: rating,
        year = m.year ?: year,
        genres = m.genres.ifEmpty { genres }.take(6),
        studio = m.director?.takeIf { it.isNotBlank() } ?: studio,
        syn = m.plot?.takeIf { it.isNotBlank() } ?: syn, // web synopsis (if any) overrides in withLive
        cover = cover?.takeIf { it.isNotBlank() } ?: m.poster,
        extMeta = true,
        omdbActors = m.actors,
    )
}

/**
 * Source split (per product rule, to keep things un-messy):
 *  - the **synopsis** and the watchable **episode list** always come from the source website;
 *  - all other **detail metadata** (status / total-episode / rating / year / studio / type / cast)
 *    comes from MAL/Jikan when matched, and only falls back to the web when there's no MAL entry
 *    (e.g. donghua). [withMal] runs first, so `malId > 0` means "MAL owns the metadata".
 */
private fun DetailData.withLive(live: LiveDetail?): DetailData {
    if (live == null) return this
    val eps = live.episodes.map { e ->
        Episode(id = "e${e.num}", num = e.num, name = e.title.ifBlank { "Episode ${e.num}" }, dur = "± 24m", desc = "",
            progress = 0, art = (art + e.num) % 8, season = e.season, epInSeason = e.epInSeason, url = e.url, thumb = e.thumb)
    }
    // Donghua (Chinese animation) per the source's origin country / genre. MAL's episode count for a
    // long-running donghua is often stale or for a different cut (e.g. it claims 720 while the source
    // lists 654), so trust the source's own list for these and label the type "Donghua" to set them
    // apart from (Japanese) anime.
    val isDonghua = live.country.equals("CN", true) || live.genres.any { it.equals("Donghua", true) }
    return copy(
        title = live.title.ifBlank { title },
        // Carry the scraped page URL so items we resolved by title (no original arg.url) can still
        // play/share and so a movie's single "episode" has a real link.
        url = url ?: live.url.takeIf { it.startsWith("http") },
        // Always from the web:
        syn = live.synopsis?.takeIf { it.isNotBlank() } ?: syn,
        episodesLive = eps,
        // MAL/OMDb-matched titles keep their poster (the arg cover); adult/no-MAL sources (nekopoi,
        // extMeta off) have no external poster, so the web detail poster wins over the tapped episode frame.
        cover = if (!extMeta) live.cover?.takeIf { it.isNotBlank() } ?: cover?.takeIf { it.isNotBlank() }
                else cover?.takeIf { it.isNotBlank() } ?: live.cover?.takeIf { it.isNotBlank() },
        // Hero episode count: MAL owns it for anime; donghua (and titles with no MAL match) use the
        // real web episode list. Other metadata: MAL/OMDb when matched, else web fills the gap.
        epCount = if (malId <= 0 || isDonghua) eps.size.takeIf { it > 0 } ?: epCount else epCount,
        status = if (!extMeta) live.status?.let { liveStatusId(it) } ?: status else status,
        studio = if (!extMeta) live.studio?.takeIf { it.isNotBlank() } ?: studio else studio,
        genres = if (!extMeta && genres.isEmpty()) live.genres.take(6) else genres,
        badge = if (isDonghua) "Donghua" else if (!extMeta) live.type?.takeIf { it.isNotBlank() } ?: badge else badge,
    )
}

private fun DetailData.withFallbackSynopsis(s: String?): DetailData =
    if (!s.isNullOrBlank() && needsSynopsisFallback(syn)) copy(syn = s) else this

private fun needsSynopsisFallback(s: String?): Boolean {
    val text = s.orEmpty().trim()
    if (text.isBlank()) return true
    val l = text.lowercase()
    return "watch streaming" in l || "download free" in l || "subtitle indonesia terbaru di" in l
}

private fun liveStatusId(raw: String): String = when {
    raw.contains("ongoing", true) || raw.contains("airing", true) || raw.contains("berlangsung", true) || raw.contains("sedang tayang", true) -> "Ongoing"
    raw.contains("completed", true) || raw.contains("tamat", true) || raw.contains("finished", true) -> "Completed"
    else -> raw
}

private fun episodeLabel(ep: Episode): String {
    val raw = ep.name.trim()
    if (raw.isBlank()) return "Episode ${ep.num}"
    compactSpecialEpisodeLabel(raw)?.let { return it }
    val sourceNoise = Regex("\\b(?:episode|eps?|ep|e|chapter|subtitle|sub\\s*indo|subbed|bahasa\\s+indonesia)\\b", RegexOption.IGNORE_CASE)
    val episodeCode = Regex("^\\s*(?:e|ep)\\s*0*\\d+\\s*$", RegexOption.IGNORE_CASE)
    return if (sourceNoise.containsMatchIn(raw) || episodeCode.matches(raw) || raw.length > 48) {
        "Episode ${ep.num}"
    } else {
        raw
    }
}

private fun compactSpecialEpisodeLabel(raw: String): String? {
    val match = Regex("\\b(OVA|ONA|Special)\\s*0*(\\d{1,3})?\\b", RegexOption.IGNORE_CASE).find(raw) ?: return null
    val kind = when (match.groupValues[1].lowercase()) {
        "ova" -> "OVA"
        "ona" -> "ONA"
        else -> "Special"
    }
    val num = match.groupValues.getOrNull(2)?.toIntOrNull()
    return if (num != null) "$kind $num" else kind
}

private fun episodeBadgeLabel(ep: Episode): String {
    val label = episodeLabel(ep)
    // Multi-season sources (PusatFilm /tv/) carry a source-relative number; show that ("EP 5") instead
    // of the flat global index ("EP 84") the season-accordion is built on.
    return if (label.contains("OVA", true) || label.contains("Special", true) || label.contains("ONA", true)) label else "EP ${ep.epInSeason ?: ep.num}"
}

private fun episodePrefixLabel(ep: Episode): String {
    val label = episodeLabel(ep)
    return when {
        label.contains("OVA", true) -> "OVA"
        label.contains("Special", true) -> "SP"
        label.contains("ONA", true) -> "ONA"
        else -> "E${ep.epInSeason ?: ep.num}"
    }
}

/** Overlay MyAnimeList facts (status/episodes/rating/year/studio/genres) when we found a match.
 *  Synopsis stays from the web seed and language stays Indonesian — per the product rule. */
private fun DetailData.withMal(m: AnimeInfo?): DetailData {
    if (m == null) return this
    return copy(
        rating = m.score?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: rating,
        year = m.year?.toString() ?: year,
        epCount = m.episodes?.takeIf { it > 0 } ?: epCount,
        genres = m.genres.ifEmpty { genres }.take(6),
        status = m.status?.let { malStatusId(it) } ?: status,
        studio = m.studios.firstOrNull() ?: studio,
        malId = m.malId,
        extMeta = true,
    )
}

private fun malStatusId(raw: String): String = when {
    raw.contains("Currently", true) || raw.contains("Airing", true) -> "Ongoing"
    raw.contains("Finished", true) || raw.contains("Complete", true) -> "Completed"
    raw.contains("Not yet", true) -> "Segera"
    else -> raw
}

private fun enrich(arg: DetailArg): DetailData {
    // No synthetic filler: every field starts from what the card actually carried and is then filled
    // by the real web scrape / MAL / OMDb. Blanks stay blank (the UI hides empty chips and shows a
    // loading/empty state) rather than fabricating ratings, years, synopses or episode counts.
    val epCount = if (arg.badge.equals("Movie", true)) 1 else (arg.ep?.toIntOrNull() ?: 0)
    val genres = arg.genres.filter { it.isNotBlank() && !Regex("^S\\d|donghua", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(6)
    return DetailData(
        title = arg.title, sub = arg.sub.ifBlank { arg.badge },
        rating = arg.rating.orEmpty(), year = "", maturity = "17+",
        epCount = epCount, badge = arg.badge, art = arg.art, genres = genres,
        syn = arg.syn.orEmpty(),
        status = arg.status.orEmpty(), prog = arg.progress, url = arg.url, cover = arg.cover, studio = "",
    )
}

private fun makeEps(d: DetailData): Pair<List<Episode>, Int> {
    // Only ever the REAL scraped list — never fabricate episodes. The UI shows a loading/empty state
    // when nothing has been scraped yet.
    if (d.episodesLive.isNotEmpty()) {
        val list = d.episodesLive.sortedBy { it.num }
        val curIdx = if (d.prog in 1..99) max(0, min(list.size - 1, (list.size * d.prog / 100.0).roundToInt())) else 0
        return list to curIdx
    }
    // A movie is a single title, not a series — the lone "episode" IS the film (real url), not filler.
    if (d.badge.equals("Movie", true) && !d.url.isNullOrBlank()) {
        return listOf(Episode(id = "e1", num = 1, name = d.title, dur = "± 100m", desc = "", progress = d.prog, art = d.art, url = d.url)) to 0
    }
    return emptyList<Episode>() to 0
}

/**
 * Single-video categories (JAV / JAV Cosplay / Uncensored, and 3D / Live2D one-shots on nekopoi) are
 * ONE film, not a series — so the detail must not fake an episodic frame ("1 Episode", a "Tonton
 * Episode 1" button, an Episode tab that then shows "belum tersedia"). Category-pure by design: a
 * MOVIE badge or a category genre marks it single-video; regular (2D) Hentai keeps the episode UI.
 */
private fun DetailData.isSingleVideo(): Boolean {
    // A real scraped multi-episode list means it's a SERIES, not a lone film — even when the source
    // files it under a "Movie" category (PusatFilm badges every /tv/ serial "Movie", and its /eps/
    // pages carry the full season accordion). Trust the episode list over the badge.
    if (episodesLive.size > 1) return false
    if (badge.equals("Movie", true) || badge.equals("JAV", true)) return true
    // Nekopoi standalone pages (JAV / 3D / L2D one-shots) are ONE video: a nekopoi URL that is neither
    // a `/hentai/{slug}/` series nor an `-episode-N` page. This is the reliable signal — genre-based
    // detection alone breaks when a bogus MAL match overwrites the real JAV/3D genres.
    val u = url?.lowercase().orEmpty()
    if (u.startsWith("http") && "nekopoi" in u && "/hentai/" !in u && "-episode-" !in u) return true
    val singleCats = setOf("jav", "jav cosplay", "uncensored", "3d", "l2d", "live2d")
    return genres.any { it.trim().lowercase() in singleCats }
}

private val ROSE = Color(0xFFEE7099)

/** Live-scrape state for the detail page: distinguishes "still fetching" from "fetched, nothing
 *  found" so the UI can show a loading state instead of fabricated placeholder data. */
private sealed interface LiveLoad {
    data object Loading : LiveLoad
    data class Done(val detail: LiveDetail?) : LiveLoad
}

@Composable
fun DetailScreen(arg: DetailArg, resumeEpisode: Int?, onBack: () -> Unit, onOpenDetail: (DetailArg) -> Unit, onOpenPlayer: (PlayerArg) -> Unit) {
    val c = TnTheme.colors
    val context = LocalContext.current
    val base = remember(arg) { enrich(arg) }
    // Nekopoi is adult (JAV / hentai / 3D): its titles fuzzy-match the WRONG MAL/OMDb entry (e.g. a
    // JAV matched some anime → bogus "Avant Garde / Romance / 1963" metadata), so scrape web-only.
    val adultSource = arg.url?.let { url ->
        listOf("nekopoi", "javhey", "otrarevista", "homecookingrocks", "indomax21")
            .any { marker -> url.contains(marker, ignoreCase = true) }
    } == true
    // Overlay MyAnimeList facts for anime/donghua. Movies & dramas (pusatfilm/oppadrama) aren't on
    // MAL — skip it so their detail metadata comes from the web instead of a wrong fuzzy match.
    val webOnly = arg.badge.equals("Movie", true) || arg.badge.equals("Drama", true) || arg.badge.equals("Series", true)
    val mal by produceState<AnimeInfo?>(null, arg.title) {
        value = if (webOnly || adultSource) null else runCatching { CoverResolver.info(arg.title) }.getOrNull()
    }
    // Movies & dramas pull their metadata (plot/rating/year/genres/cast/poster) from OMDb (IMDB).
    val omdb by produceState<MovieInfo?>(null, arg.title) {
        value = if (webOnly && !adultSource) runCatching { OmdbResolver.info(arg.title) }.getOrNull() else null
    }
    // Scrape the source's own detail page (synopsis, status, genres, episodes, cover). Prefer the URL
    // the card carried; if it has none (a recommendation / bundled-catalog item), resolve one by
    // searching the title live — so the detail always shows REAL data, never synthetic placeholders.
    val liveLoad by produceState<LiveLoad>(LiveLoad.Loading, arg.url, arg.title) {
        value = LiveLoad.Loading
        val url = arg.url?.takeIf { it.startsWith("http") }
            ?: runCatching { TnData.resolveLiveUrl(arg.title) }.getOrNull()
        value = LiveLoad.Done(url?.let { TnData.liveDetail(it) })
    }
    val live = (liveLoad as? LiveLoad.Done)?.detail
    val liveLoading = liveLoad is LiveLoad.Loading
    val fallbackSourceSyn = if (liveLoading) null else live?.synopsis?.takeIf { it.isNotBlank() } ?: base.syn
    val fallbackSyn by produceState<String?>(null, liveLoading, fallbackSourceSyn, mal?.synopsis) {
        value = if (!liveLoading && needsSynopsisFallback(fallbackSourceSyn)) {
            runCatching { CoverResolver.translateSynopsisToId(mal?.synopsis) }.getOrNull()
        } else null
    }
    val d = remember(base, mal, omdb, live, fallbackSyn) { base.withMal(mal).withOmdb(omdb).withLive(live).withFallbackSynopsis(fallbackSyn) }
    val (eps, curIdx) = remember(d) { makeEps(d) }
    // Single-video (JAV / 3D / L2D): present movie-style — no Episode tab, "Tonton" not "Tonton
    // Episode 1", play targets the film's own page directly.
    val single = remember(d) { d.isSingleVideo() }
    // Episode list handed to the player so it can auto-advance (carries badge/malId for OP/ED skip).
    val playlist = remember(eps) { eps.map { EpRef(it.num, episodeLabel(it), it.url) } }
    // Short-drama teaser: the first ~10–15% of episodes are free; the rest are locked for non-subscribers.
    // freeUntil = highest free episode number (Int.MAX_VALUE = nothing locked). Reactive on entitlement.
    val shortDrama = TnData.isShortSource(d.url) || eps.firstOrNull()?.url?.let { TnData.isShortSource(it) } == true
    val freeUntil = if (shortDrama && !TnData.isEntitledToPremium()) TnData.freeEpisodeCount(eps.size) else Int.MAX_VALUE
    // Resume on the episode last played (returning from the player) when it exists in this list;
    // otherwise the progress-derived position, else the first episode.
    var current by remember(d) {
        mutableStateOf(resumeEpisode?.takeIf { rn -> eps.any { it.num == rn } } ?: eps.getOrNull(curIdx)?.num ?: 1)
    }
    var tab by remember(d) { mutableStateOf(if (single) "info" else "ep") }
    // "Tambah ke daftar" (Followed) — persisted in FollowedStore so it really populates the Library.
    var inList by remember(d) { mutableStateOf(FollowedStore.isFollowed(d.url)) }
    var sortAsc by remember(d) { mutableStateOf(true) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun openUrl(url: String?) {
        if (url.isNullOrBlank()) { toast("Sumber lagi nggak tersedia"); return }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("Nggak bisa buka link") }
    }
    fun share() {
        val link = detailWebLink(Secrets.controlPanelUrl, d.title, d.url, d.cover, d.badge, d.sub, d.epCount, d.genres)
        val text = buildString { append(d.title); append("\n").append(link); append("\n— via TetoNova") }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        runCatching { context.startActivity(Intent.createChooser(send, "Bagikan")) }.onFailure { toast("Nggak bisa berbagi") }
    }
    // Start playback AND log "Riwayat" history — a title counts as watched once the user actually
    // plays it (not merely opening Detail). Keyed by the series page so all episodes map to one entry.
    fun watch(p: PlayerArg) {
        val key = d.url?.takeIf { it.startsWith("http") } ?: p.url?.takeIf { it.startsWith("http") }
        if (key != null) HistoryStore.record(d.title, key, d.cover, d.badge, arg.sub, epUrl = p.url)
        onOpenPlayer(p)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= 600.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Hero(
                d, current, inList, wide, single, onBack,
                onToggleList = {
                    inList = !inList
                    if (inList) FollowedStore.add(d.title, d.url, d.cover, d.badge, arg.sub)
                    else FollowedStore.remove(d.url)
                    toast(if (inList) "Ditambahkan ke daftar" else "Dihapus dari daftar")
                },
                onPlay = {
                    val ep = eps.firstOrNull { it.num == current }
                    // Single-video (JAV/3D/L2D): play the lone video with NO episode label/number/playlist,
                    // so the player shows just the title (not a stray "Episode N" from a phantom marker).
                    watch(PlayerArg(d.title, ep?.url ?: d.url,
                        if (single) null else ep?.let { episodeLabel(it) },
                        if (single) null else (ep?.num ?: current),
                        badge = d.badge, malId = d.malId, playlist = if (single) emptyList() else playlist,
                        vertical = TnData.isShortSource(ep?.url ?: d.url)))
                },
                onShare = { share() },
            )
            // light content sheet, lifted over the hero
            Column(
                Modifier.fillMaxWidth().graphicsLayer { translationY = -24.dp.toPx() }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.bg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.fillMaxWidth().widthIn(max = MAX_W).padding(horizontal = if (wide) 28.dp else 20.dp)) {
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        // Single-video titles have no episode list — hide the Episode tab so the
                        // detail never shows an empty "belum tersedia" list for a lone film.
                        if (!single) DetailTab("Episode", tab == "ep") { tab = "ep" }
                        DetailTab("Detail", tab == "info") { tab = "info" }
                    }
                    Spacer(Modifier.height(18.dp))
                    if (!single && tab == "ep") EpisodeTab(eps, current, sortAsc, wide, liveLoading, freeUntil, { sortAsc = !sortAsc }, { current = it },
                        { ep -> current = ep.num; watch(PlayerArg(d.title, ep.url, episodeLabel(ep), ep.num,
                            badge = d.badge, malId = d.malId, playlist = playlist,
                            vertical = TnData.isShortSource(ep.url))) }, onOpenDetail, d.title, d.url, d.cover, d.badge)
                    else AboutTab(d, liveLoading)
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun Hero(
    d: DetailData, current: Int, inList: Boolean, wide: Boolean, single: Boolean,
    onBack: () -> Unit, onToggleList: () -> Unit, onPlay: () -> Unit, onShare: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().heightIn(min = if (wide) 420.dp else 540.dp)) {
        // dark blurred-ish backdrop (cover or gradient) + heavy scrim
        Art(d.art, d.title, Modifier.matchParentSize(), coverTitle = d.title, coverUrl = d.cover, roundedCorners = false)
        Box(
            Modifier.matchParentSize().background(
                // Light at the top so the cover/placeholder is actually visible, ramping to dark
                // over the lower half where the title/synopsis/CTA live (so text stays legible).
                Brush.verticalGradient(
                    0.0f to Color(0x140D090B),
                    0.28f to Color(0x590D090B),
                    0.46f to Color(0xE00D090B),
                    1.0f to Color(0xFF0D090B),
                ),
            ),
        )
        // top bar — pinned to the top of the hero, just under the status bar (was floating
        // mid-hero because it shared a vertically-centered column with the content).
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().widthIn(max = MAX_W).statusBarsPadding()
                .padding(horizontal = if (wide) 28.dp else 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.12f)).clickable { onBack() }.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TnIcon("chevR", size = 17.dp, tint = Color.White, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                Text("Kembali", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                TnIcon("sparkle", size = 15.dp, tint = ROSE, filled = true)
                Text("TetoNova", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            }
        }
        // hero content: row (poster right) on wide, column on phone — sits low in the hero,
        // just above the content sheet (sheet overlaps the bottom ~24dp, so leave clearance).
        if (wide) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().widthIn(max = MAX_W).padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 32.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Box(Modifier.weight(1f)) { HeroInfo(d, current, inList, single, onToggleList, onPlay, onShare) }
                Box(Modifier.width(210.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(TnRadii.lg))) {
                    Art(d.art, d.title, Modifier.fillMaxSize(), coverTitle = d.title, coverUrl = d.cover)
                }
            }
        } else {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 44.dp)) { HeroInfo(d, current, inList, single, onToggleList, onPlay, onShare) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroInfo(
    d: DetailData, current: Int, inList: Boolean, single: Boolean,
    onToggleList: () -> Unit, onPlay: () -> Unit, onShare: () -> Unit,
) {
    val contLabel = if (d.prog in 1..99) "Lanjutkan" else "Tonton"
    // Single-video: the button is just "Tonton"/"Lanjutkan" (the title IS the video); a series keeps
    // the per-episode label.
    val playLabel = if (single) contLabel
        else "$contLabel " + (d.episodesLive.firstOrNull { it.num == current }?.let(::episodeLabel) ?: "Episode $current")
    // Long titles shrink a notch so they stay fully visible (up to 3 lines) instead of truncating.
    val titleSize = if (d.title.length > 24) 30.sp else 36.sp
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(d.title, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = titleSize, lineHeight = titleSize * 1.1f, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // Only render facts we actually have — blanks (during load / no match) are omitted, not faked.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.rating.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TnIcon("star", size = 15.dp, tint = ROSE, filled = true)
                    Text(d.rating, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (d.year.isNotBlank()) { if (d.rating.isNotBlank()) Dot(); Text(d.year, color = Color(0xFFB79AA4), fontSize = 13.sp) }
            // Episode count is meaningless for a single video — omit it (genres already show the type).
            if (!single && d.epCount > 0) {
                if (d.rating.isNotBlank() || d.year.isNotBlank()) Dot()
                Text("${d.epCount} Episode", color = Color(0xFFB79AA4), fontSize = 13.sp)
            }
            MetaBadge("HD")
        }
        // Genres wrap to the next line when they don't fit (instead of overflowing/clipping).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            d.genres.forEach { g ->
                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.1f)).padding(horizontal = 11.dp, vertical = 5.dp)) {
                    Text(g, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // Concise in the hero — the full synopsis lives in the Detail tab. Omitted when blank.
        if (d.syn.isNotBlank()) {
            Text(d.syn, color = Color(0xFFB79AA4), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 260.dp)
                    .clip(RoundedCornerShape(TnRadii.pill))
                    .tnGradient(RoseGradientColors)
                    .clickable { onPlay() }
                    .padding(horizontal = 20.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TnIcon("play", size = 18.dp, tint = Color.White, filled = true)
                Text(
                    playLabel,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HeroIcon(if (inList) "check" else "plus", on = inList, onClick = onToggleList)
            HeroIcon("send", on = false, onClick = onShare)
        }
    }
}

@Composable
private fun HeroIcon(icon: String, on: Boolean, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(if (on) ROSE.copy(0.25f) else Color.White.copy(0.12f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { TnIcon(icon, size = 19.dp, tint = if (on) ROSE else Color.White, filled = filled) }
}

@Composable
private fun Dot() = Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFFB79AA4)))

@Composable
private fun MetaBadge(text: String) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(0.14f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun DetailTab(label: String, on: Boolean, onClick: () -> Unit) {
    val c = TnTheme.colors
    Column(Modifier.clickable { onClick() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (on) c.ink else c.muted, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(TnRadii.pill)).background(if (on) c.rose else Color.Transparent))
    }
}

@Composable
private fun EpisodeTab(
    eps: List<Episode>, current: Int, sortAsc: Boolean, wide: Boolean, liveLoading: Boolean, freeUntil: Int,
    onSort: () -> Unit, onSelect: (Int) -> Unit, onPlayEpisode: (Episode) -> Unit,
    onOpenDetail: (DetailArg) -> Unit, currentTitle: String, detailUrl: String?, cover: String?, badge: String,
) {
    val c = TnTheme.colors
    val columns = if (wide) 2 else 1
    // Episodes are grouped into fixed numeric blocks of 25 — block 0 = ep 1–25, block 1 = 26–50, … —
    // keyed by the episode NUMBER, not its position in the list. Position-based chunking (chunked(25))
    // let an episode "slip" into the wrong block whenever the numbers had gaps or the list was sorted
    // newest-first, because the 25-boundaries then shifted with the data. Bucketing on the number
    // means a block can only ever hold episodes whose number lands in its 25-wide range, so nothing
    // escapes its proper range chip.
    val pageSize = 25
    // PusatFilm /tv/ stacks multiple seasons on one page; group the pager by SEASON ("Season 1", …)
    // instead of fixed 25-wide number ranges so the picker mirrors the site. Everything else (flat
    // anime lists) keeps the numeric-block pager.
    val bySeason = remember(eps) { eps.mapNotNull { it.season }.distinct().size > 1 }
    val blocks = remember(eps) {
        if (bySeason) {
            eps.sortedBy { it.num }
                .groupBy { it.season ?: 0 }
                .toSortedMap()
                .values.toList()
        } else {
            eps.sortedBy { it.num }
                .groupBy { (it.num - 1).coerceAtLeast(0) / pageSize }
                .toSortedMap()
                .values.toList()
        }
    }
    fun chipLabel(ch: List<Episode>): String =
        if (bySeason) "Season ${ch.firstOrNull()?.season ?: "?"}"
        else "${ch.minOfOrNull { it.num } ?: 1}–${ch.maxOfOrNull { it.num } ?: eps.size}"
    // Newest-first flips the block order and the order within each block; the 25-wide boundaries
    // themselves never move, so a given episode always lives in the same range chip.
    val pages = if (sortAsc) blocks else blocks.asReversed().map { it.asReversed() }
    var page by remember(pages) {
        mutableStateOf(pages.indexOfFirst { ch -> ch.any { it.num == current } }.coerceAtLeast(0))
    }
    val shown = pages.getOrElse(page) { pages.firstOrNull().orEmpty() }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Episode", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            if (eps.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.rose).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text(eps.size.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.pill)).clickable { onSort() }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    TnIcon("layers", size = 15.dp, tint = c.ink2)
                    Text(if (sortAsc) "Terlama" else "Terbaru", color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (eps.isEmpty()) {
            // Never fabricate episodes — show an honest loading/empty state instead.
            EpisodeEmpty(liveLoading)
        } else {
            // Range chips (e.g. 1–25, 26–50). A single page collapses to one static chip.
            if (pages.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pages.size) { i ->
                        val ch = pages[i]
                        val on = i == page
                        Box(
                            Modifier.clip(RoundedCornerShape(TnRadii.pill))
                                .background(if (on) c.rose else c.surface)
                                .border(1.dp, if (on) c.rose else c.line, RoundedCornerShape(TnRadii.pill))
                                .clickable { page = i }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(chipLabel(ch), color = if (on) Color.White else c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Box(Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.rose).padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(chipLabel(pages.firstOrNull().orEmpty()), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))
            shown.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { ep -> EpCard(ep, ep.num == current, ep.num > freeUntil, cover, currentTitle, badge, Modifier.weight(1f)) { onPlayEpisode(ep) } }
                    if (row.size < columns) repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Rekomendasi Serupa", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.height(12.dp))
        val reco = TnData.recommendations(currentTitle, 20, detailUrl)
        val pad = if (wide) 28.dp else 20.dp
        LazyRow(
            modifier = Modifier.bleedEnd(pad),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(reco.size) { i ->
                val p = reco[i]
                Column(Modifier.width(124.dp).clickable { onOpenDetail(p.toDetailArg()) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(TnRadii.md))) {
                        Art(p.art, p.title.substringBefore(' '), Modifier.fillMaxSize(), coverTitle = p.title, coverUrl = p.cover)
                        p.badge?.let {
                            Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.Black.copy(0.5f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text(it, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(p.title, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Loading (spinner) / empty placeholder shown in place of a fabricated episode list. */
@Composable
private fun EpisodeEmpty(loading: Boolean) {
    val c = TnTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(TnRadii.md))
            .background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.md)).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loading) {
                CircularProgressIndicator(color = c.rose, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text("Memuat episode…", color = c.muted, fontSize = 13.sp)
            } else {
                Text("Episode belum tersedia dari sumber", color = c.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Compact horizontal episode card: landscape thumbnail left + text right (tablet design). */
@Composable
private fun EpCard(ep: Episode, isCurrent: Boolean, locked: Boolean, seriesCover: String?, seriesTitle: String, badge: String, modifier: Modifier, onClick: () -> Unit) {
    val c = TnTheme.colors
    // Watched percent from the resume store (live online/offline history) — falls back to the seed value.
    val watched = (ep.url?.let { WatchProgressStore.percent(it) } ?: 0).takeIf { it > 0 } ?: ep.progress
    Row(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(if (isCurrent) c.roseTint else c.surface)
            .border(1.dp, if (isCurrent) c.rose else c.line, RoundedCornerShape(TnRadii.md))
            .clickable { onClick() }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val badgeLabel = episodeBadgeLabel(ep)
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(TnRadii.sm))) {
            // Per-episode thumbnail when the source gave one; else a 16:9 crop of the series cover —
            // a real image, never the bare gradient placeholder. (When both are null, Art resolves
            // by title via Jikan/CoverProvider.)
            Art(ep.art, badgeLabel, Modifier.fillMaxSize(), coverTitle = seriesTitle, coverUrl = ep.thumb ?: seriesCover)
            Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Text(badgeLabel, color = Color.White.copy(0.9f), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(0.6f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(ep.dur, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color.Black.copy(if (locked) 0.6f else 0.45f)), contentAlignment = Alignment.Center) {
                if (locked) TnIcon("lock", size = 14.dp, tint = Color.White)
                else TnIcon("play", size = 14.dp, tint = Color.White, filled = true)
            }
            // Premium teaser badge so a locked episode reads as "subscribe to continue", not "broken".
            if (locked) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(TnRadii.pill)).background(c.rose).padding(horizontal = 7.dp, vertical = 2.dp)) {
                Text("Premium", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (watched > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(0.25f))) {
                    Box(Modifier.fillMaxWidth(min(100, watched) / 100f).fillMaxHeight().background(c.rose))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            if (isCurrent) {
                Text(if (watched >= 100) "● Selesai" else "● Sedang diputar", color = c.rose, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(episodePrefixLabel(ep), color = c.rose, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text(episodeLabel(ep), color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(3.dp))
            Text(ep.desc, color = c.muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // Trailing per-episode download control (no need to open the player first).
        DownloadButton(ep, seriesTitle, ep.thumb ?: seriesCover, badge, onPlay = onClick)
    }
}

/** Per-episode download affordance: tap to download (background), shows progress, then plays offline. */
@Composable
private fun DownloadButton(ep: Episode, seriesTitle: String, poster: String?, badge: String, onPlay: () -> Unit) {
    val c = TnTheme.colors
    val url = ep.url ?: return
    val state = DownloadCenter.uiState(url)
    val pct = DownloadCenter.percentOf(url)
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(if (state == DlUiState.COMPLETED) c.roseTint else c.surface)
            .border(1.dp, if (state == DlUiState.COMPLETED) c.rose else c.line, CircleShape)
            .clickable {
                when (state) {
                    DlUiState.NONE, DlUiState.FAILED ->
                        DownloadCenter.startDownload(DlMeta(url, seriesTitle, episodeLabel(ep), poster, badge))
                    DlUiState.COMPLETED -> onPlay()
                    DlUiState.DOWNLOADING, DlUiState.QUEUED -> DownloadCenter.remove(url)
                    DlUiState.RESOLVING -> {}
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            DlUiState.NONE -> TnIcon("download", size = 17.dp, tint = c.ink2)
            DlUiState.RESOLVING, DlUiState.QUEUED ->
                CircularProgressIndicator(Modifier.size(18.dp), color = c.rose, strokeWidth = 2.dp)
            DlUiState.DOWNLOADING ->
                CircularProgressIndicator(progress = { (pct / 100f).coerceIn(0f, 1f) }, modifier = Modifier.size(18.dp), color = c.rose, strokeWidth = 2.dp)
            DlUiState.COMPLETED -> TnIcon("check", size = 17.dp, tint = c.rose)
            DlUiState.FAILED -> TnIcon("refresh", size = 17.dp, tint = c.rose)
        }
    }
}

@Composable
private fun AboutTab(d: DetailData, liveLoading: Boolean) {
    val c = TnTheme.colors
    // Only facts we actually have — no fabricated rating/year/studio/duration/quality fillers.
    val info = buildList {
        if (d.status.isNotBlank()) add(InfoRow("Status", d.status))
        if (d.epCount > 0) add(InfoRow("Total Episode", "${d.epCount} episode"))
        if (d.rating.isNotBlank()) add(InfoRow("Rating", "${d.rating} / 10"))
        if (d.badge.isNotBlank()) add(InfoRow("Tipe", d.badge))
        if (d.year.isNotBlank()) add(InfoRow("Tahun Rilis", d.year))
        if (d.studio.isNotBlank()) add(InfoRow("Studio", d.studio))
        if (d.genres.isNotEmpty()) add(InfoRow("Genre", d.genres.joinToString(", ")))
        add(InfoRow("Bahasa", "Sub Indonesia"))
    }
    // Real cast from MyAnimeList when matched (anime/donghua) or OMDb (movie/drama) — never filler.
    val malCast by produceState<List<CharacterInfo>>(emptyList(), d.malId) {
        value = if (d.malId > 0) runCatching { CoverResolver.characters(d.malId) }.getOrDefault(emptyList()) else emptyList()
    }
    Column {
        Text("Tentang ${d.title}", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        // Synopsis stays real: source first, MAL translation only when the source is blank/SEO.
        val syn = d.syn.trim()
        Text(
            text = syn.ifBlank { if (liveLoading) "Memuat sinopsis…" else "Sinopsis belum tersedia dari sumber." },
            color = if (syn.isBlank()) c.muted else c.ink2, fontSize = 14.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(16.dp))
        info.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { r ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(TnRadii.sm)).padding(12.dp)) {
                        Text(r.key, color = c.muted, fontSize = 11.sp)
                        Text(r.value, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // Cast only when we have real people — no placeholder names.
        if (malCast.isNotEmpty() || d.omdbActors.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Pemeran & Karakter", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (malCast.isNotEmpty()) items(malCast.size) { i ->
                    val ch = malCast[i]
                    CastCell(name = ch.name, role = malRoleId(ch.role), imageUrl = ch.imageUrl, grad = i % 8)
                } else items(d.omdbActors.size) { i ->
                    CastCell(name = d.omdbActors[i], role = "Pemeran", imageUrl = null, grad = i % 8)
                }
            }
        }
    }
}

private fun malRoleId(role: String?): String = when {
    role == null -> ""
    role.equals("Main", true) -> "Utama"
    role.equals("Supporting", true) -> "Pendukung"
    else -> role
}

@Composable
private fun CastCell(name: String, role: String, imageUrl: String?, grad: Int) {
    val c = TnTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp)) {
        Box(Modifier.size(66.dp).clip(CircleShape).tnGradient(gradColors(grad)), contentAlignment = Alignment.Center) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else {
                Text(name.take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(role, color = c.muted, fontSize = 10.sp, maxLines = 1)
    }
}
