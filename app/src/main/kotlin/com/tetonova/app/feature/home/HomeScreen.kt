package com.tetonova.app.feature.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.tetonova.app.data.FollowedStore
import com.tetonova.app.data.TnData
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.TnPrimaryButton
import com.tetonova.app.ui.bleedBoth
import com.tetonova.app.ui.toDetailArg
import com.tetonova.core.designsystem.Art
import com.tetonova.core.designsystem.GradientTile
import com.tetonova.core.designsystem.Pill
import com.tetonova.core.designsystem.Poster
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.tnGradient
import com.tetonova.core.designsystem.theme.HeroGradientColors
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.theme.gradColors
import com.tetonova.core.model.SampleData
import com.tetonova.core.model.SpotItem
import com.tetonova.app.ui.PageScroll

@Composable
fun HomeScreen(
    onOpenDetail: (DetailArg) -> Unit,
    lite: Boolean,
    onToggleLite: () -> Unit,
    dataSaver: Boolean,
    onToggleDataSaver: () -> Unit,
) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val scrollState = rememberScrollState()
    var restoreScrollTo by remember { mutableIntStateOf(-1) }
    var sourceReturnScroll by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(-1) }
    // Spotlight lives OUTSIDE the padded column so it can be edge-to-edge on phone.
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spotlight(onOpenDetail = onOpenDetail, wide = wide)
        Column(Modifier.widthIn(max = 1180.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(14.dp))

            // Home sections are driven by the control panel's home links:
            // "Semua" -> each source's showAll rows; a source picked -> its showOnClick rows.
            // The source is chosen from the "Source" quick-chip dropdown.
            val v = TnData.panelVersion
            val ev = TnData.extStateVersion // re-derive source chips + rails on install/uninstall + 18+ toggle
            val sources = remember(v, ev) { TnData.homeSources() }
            var selectedSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
            val selectedLabel = sources.firstOrNull { it.id == selectedSource }?.label ?: "Semua"
            val sections = remember(v, ev, selectedSource) {
                if (selectedSource == null) TnData.homeSectionsAll() else TnData.homeSectionsForSource(selectedSource!!)
            }
            fun selectSource(id: String?) {
                if (id == selectedSource) return
                if (selectedSource == null && id != null) {
                    sourceReturnScroll = scrollState.value
                    restoreScrollTo = scrollState.value
                } else if (selectedSource != null && id == null) {
                    restoreScrollTo = sourceReturnScroll.takeIf { it >= 0 } ?: scrollState.value
                    sourceReturnScroll = -1
                }
                selectedSource = id
            }
            LaunchedEffect(selectedSource, restoreScrollTo, sections.size) {
                val target = restoreScrollTo
                if (target >= 0) {
                    withFrameNanos { }
                    withFrameNanos { }
                    scrollState.scrollTo(target.coerceAtMost(scrollState.maxValue))
                    restoreScrollTo = -1
                }
            }
            // In Klik Source mode, Back returns to "Semua" instead of leaving the app.
            BackHandler(enabled = selectedSource != null) { selectSource(null) }

            QuickChips(sources = sources, selected = selectedSource, selectedLabel = selectedLabel, onSelect = { selectSource(it) }, lite = lite, onToggleLite = onToggleLite, dataSaver = dataSaver, onToggleDataSaver = onToggleDataSaver)

            if (sections.isEmpty()) {
                // No panel-curated sections yet. Show REAL bundled catalog from installed extensions if any;
                // otherwise an honest loading / "install a source" state — never fabricated sample data.
                val fallback = TnData.homePosters
                if (fallback.isNotEmpty()) {
                    SectionHead(title = "Rilisan Terbaru", sub = "Baru rilis")
                    PosterRail(posters = fallback.take(12), showProgress = false, onOpenDetail = onOpenDetail)
                } else {
                    HomeEmptyState(loading = !TnData.panelLoaded)
                }
            } else {
                sections.forEach { sec ->
                    // Fetch this rail live from the source's real web page.
                    LaunchedEffect(sec.url) { TnData.ensureLiveSection(sec.url, sec.sourceId) }
                    val live = TnData.liveSectionPosters(sec.url)
                    SectionHead(
                        title = sec.label,
                        // "Lihat semua" only in Mode "Semua" — it jumps into that source's Klik Source view.
                        // In Klik Source mode we're already focused on one source, so no button.
                        action = if (selectedSource == null) {
                            {
                                com.tetonova.app.ui.TnGhostButton(
                                    text = "Lihat semua",
                                    onClick = {
                                        selectSource(sec.sourceId)
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    )
                    if (live == null) {
                        LoadingPosterRail()
                        return@forEach
                    }
                    val posters = live.ifEmpty { sec.posters }
                    if (posters.isEmpty()) {
                        LoadingPosterRail()
                        return@forEach
                    }
                    PosterRail(
                        posters = posters,
                        showProgress = false,
                        onOpenDetail = onOpenDetail,
                        canLoadMore = TnData.liveSectionCanLoadMore(sec.url),
                        loadingMore = TnData.liveSectionLoadingMore(sec.url),
                        onLoadMore = { TnData.loadMoreLiveSection(sec.url, sec.sourceId) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoadingPosterRail() {
    LazyRow(
        Modifier.bleedBoth(20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(8) { i -> LoadingPosterSkeleton(i) }
    }
}

/** A "Source" quick chip whose tap opens a dropdown listing every panel source ("Semua" + each). */
@Composable
private fun SourceChip(
    modifier: Modifier,
    label: String,
    sources: List<com.tetonova.app.data.HomeSourceChip>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val c = TnTheme.colors
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
                .border(if (focused) 3.dp else 1.dp, if (focused) c.rose else Color.Transparent, RoundedCornerShape(TnRadii.md))
                .onFocusChanged { focused = it.isFocused }
                .clickable { open = true }.padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GradientTile("globe", 6, Modifier.size(26.dp), iconSize = 14.dp, corner = 13.dp)
            Column(Modifier.weight(1f)) {
                Text("Source", color = c.ink, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, color = c.muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TnIcon("chevR", size = 12.dp, tint = c.muted, modifier = Modifier.graphicsLayer(rotationZ = 90f))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sources.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            s.label,
                            color = if (s.id == selected) c.rose else c.ink,
                            fontWeight = if (s.id == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(s.id); open = false },
                )
            }
        }
    }
}

@Composable
private fun Spotlight(onOpenDetail: (DetailArg) -> Unit, wide: Boolean) {
    val spots = TnData.liveSpots()
    if (spots.isEmpty()) return
    val heroModifier = Modifier
        .then(if (wide) Modifier.widthIn(max = 1180.dp).fillMaxWidth().padding(horizontal = 20.dp) else Modifier.fillMaxWidth())
        .height(if (wide) 330.dp else 360.dp)
        .clip(RoundedCornerShape(if (wide) TnRadii.xl else 0.dp))
    val isTv = (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    if (isTv) {
        // Android TV: NO HorizontalPager. A focus-driven pager fights the D-pad focus system — on every
        // scroll (auto OR manual) `bringIntoView` on the focusable in-page buttons tugs against the pager's
        // snap, so it settles at a fractional offset and flickers in a loop (with a GC storm). Instead keep
        // it a rotating recommendation by *swapping the content in place* (no scroll → nothing to fight):
        // auto-cycle every 6s and let the arrows step the index instantly. The buttons stay put and focused.
        var idx by remember { mutableIntStateOf(0) }
        val safeIdx = idx % spots.size
        LaunchedEffect(spots.size) {
            if (spots.size <= 1) return@LaunchedEffect
            while (true) {
                kotlinx.coroutines.delay(6000)
                idx = (idx + 1) % spots.size
            }
        }
        Box(heroModifier) {
            SpotSlide(spots[safeIdx], wide = true, onOpenDetail)
            SpotArrow("prev", Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { idx = (safeIdx - 1 + spots.size) % spots.size }
            SpotArrow("next", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { idx = (safeIdx + 1) % spots.size }
            Box(Modifier.align(Alignment.TopEnd).padding(18.dp)) { SpotDots(spots.size, safeIdx, Color.White) }
        }
        return
    }
    // Phone / tablet (touch): auto-advancing carousel. `key(spots.size)` recreates the pager cleanly when
    // live sections stream in and grow the count, so a count change can't strand an in-flight auto-advance
    // animation (which showed as a GC storm + a carousel stuck flickering on one title).
    key(spots.size) {
        val pager = rememberPagerState(pageCount = { spots.size })
        val scope = rememberCoroutineScope()
        fun goto(page: Int) { scope.launch { pager.animateScrollToPage(((page % spots.size) + spots.size) % spots.size) } }
        LaunchedEffect(pager) {
            while (true) {
                kotlinx.coroutines.delay(6000)
                if (spots.size > 1 && !pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % spots.size)
            }
        }

        Box(heroModifier) {
            // The pager owns the horizontal drag — a flick snaps to the next slide and can't misfire
            // into a card tap (only the buttons inside a slide open Detail).
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                SpotSlide(spots[page], wide, onOpenDetail)
            }
            if (wide) {
                SpotArrow("prev", Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { goto(pager.currentPage - 1) }
                SpotArrow("next", Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { goto(pager.currentPage + 1) }
            }
            Box(Modifier.align(Alignment.TopEnd).padding(if (wide) 18.dp else 16.dp)) { SpotDots(spots.size, pager.currentPage, Color.White) }
        }
    }
}

/** One spotlight slide (cover + scrim + info); hosted as a page by the [Spotlight] pager. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpotSlide(s: SpotItem, wide: Boolean, onOpenDetail: (DetailArg) -> Unit) {
    val c = TnTheme.colors
    val ctx = LocalContext.current
    var inList by remember(s.url) { mutableStateOf(FollowedStore.isFollowed(s.url)) }
    fun toggleFollow() {
        if (s.url.isNullOrBlank()) {
            onOpenDetail(s.toDetailArg())
            return
        }
        inList = !inList
        if (inList) {
            FollowedStore.add(s.title, s.url, s.cover, s.tags.firstOrNull()?.label ?: s.sub, s.sub)
        } else {
            FollowedStore.remove(s.url)
        }
        Toast.makeText(ctx, if (inList) "Ditambahkan ke daftar" else "Dihapus dari daftar", Toast.LENGTH_SHORT).show()
    }
    Box(Modifier.fillMaxSize().tnGradient(HeroGradientColors)) {
        if (wide) {
            // cover pinned to the right, fading into the dark content area
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.52f)) {
                Art(s.art, s.title, Modifier.fillMaxSize(), coverTitle = s.title, coverUrl = s.cover)
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF2A0008),
                            Color(0xFF2A0008),
                            Color(0xD92A0008),
                            Color(0x00000000),
                        ),
                    ),
                ),
            )
        } else {
            Art(s.art, s.title, Modifier.fillMaxSize(), coverTitle = s.title, coverUrl = s.cover)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(0.15f), Color.Black.copy(0.85f)))))
        }
        Column(
            Modifier
                .align(if (wide) Alignment.CenterStart else Alignment.BottomStart)
                .then(if (wide) Modifier.fillMaxWidth(0.48f) else Modifier.fillMaxWidth())
                .padding(
                    start = if (wide) 46.dp else 22.dp,
                    top = if (wide) 24.dp else 22.dp,
                    end = if (wide) 18.dp else 22.dp,
                    bottom = if (wide) 24.dp else 22.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.rose))
                Text("SOROTAN HARI INI · ${s.sub.uppercase()}", color = Color.White.copy(0.9f), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.08.em)
            }
            Text(
                s.title,
                color = Color.White,
                fontSize = if (wide) 32.sp else 28.sp,
                lineHeight = if (wide) 36.sp else 32.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                s.tags.take(if (wide) 2 else 4).forEach { tg ->
                    Row(
                        Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        TnIcon(tg.icon, size = 13.dp, tint = Color.White)
                        Text(tg.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (s.rating.isNotBlank()) MetaStat("star", s.rating, filled = true)
                if (s.eps.isNotBlank()) MetaStat("play", s.eps, filled = true)
                if (s.status.isNotBlank()) MetaStat("flame2", s.status, filled = false)
            }
            Text(s.syn, color = Color.White.copy(0.82f), fontSize = 13.sp, maxLines = if (wide) 1 else 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TnPrimaryButton(
                    text = "Tonton Sekarang",
                    icon = "play",
                    filledIcon = true,
                    modifier = if (wide) Modifier.widthIn(min = 210.dp) else Modifier,
                ) { onOpenDetail(s.toDetailArg()) }
                if (wide) {
                    Row(
                        Modifier.widthIn(min = 190.dp).clip(RoundedCornerShape(TnRadii.pill)).background(Color.White.copy(0.16f)).clickable { toggleFollow() }.padding(horizontal = 18.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        TnIcon("plus", size = 18.dp, tint = Color.White)
                        Text(if (inList) "Di Daftar" else "Tambah ke Daftar", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(TnRadii.md)).background(Color.White.copy(0.16f)).clickable { toggleFollow() },
                        contentAlignment = Alignment.Center,
                    ) { TnIcon("plus", size = 20.dp, tint = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SpotArrow(dir: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(0.35f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        TnIcon("chevR", size = 18.dp, tint = Color.White, modifier = if (dir == "prev") Modifier.graphicsLayer(rotationZ = 180f) else Modifier)
    }
}

@Composable
private fun SpotDots(count: Int, index: Int, active: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.height(6.dp).width(if (i == index) 20.dp else 6.dp).clip(RoundedCornerShape(50)).background(if (i == index) active else Color.White.copy(0.4f)),
            )
        }
    }
}

@Composable
private fun MetaStat(icon: String, text: String, filled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TnIcon(icon, size = 14.dp, tint = Color.White, filled = filled)
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Honest Home placeholder when there's no real catalog yet — a loader while the panel is fetching,
 *  or a "install a source" hint. Replaces the old fabricated SampleData rails. */
@Composable
private fun HomeEmptyState(loading: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color(0xFFE11D48), strokeWidth = 3.dp)
            Spacer(Modifier.height(16.dp))
            Text("Memuat konten…", color = Color(0xFF9CA3AF), fontSize = 14.sp)
        } else {
            Text("Belum ada sumber", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Buka tab Extensions untuk memasang sumber — konten akan muncul di sini.",
                color = Color(0xFF9CA3AF), fontSize = 14.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun QuickChips(
    sources: List<com.tetonova.app.data.HomeSourceChip>,
    selected: String?,
    selectedLabel: String,
    onSelect: (String?) -> Unit,
    lite: Boolean,
    onToggleLite: () -> Unit,
    dataSaver: Boolean,
    onToggleDataSaver: () -> Unit,
) {
    val c = TnTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Lite Mode & Data Saver are tap-to-toggle and stay in sync with their Settings switches
        // (state.lite / state.dataSaver). "Source" is the interactive dropdown below.
        SampleData.homeQuickChips.filter { it.title != "Source" }.forEach { chip ->
            val value: String
            val onChipClick: () -> Unit
            when (chip.title) {
                "Lite Mode" -> { value = if (lite) "On" else "Off"; onChipClick = onToggleLite }
                "Data Saver" -> { value = if (dataSaver) "On" else "Off"; onChipClick = onToggleDataSaver }
                else -> { value = chip.value; onChipClick = {} }
            }
            var focused by remember(chip.title) { mutableStateOf(false) }
            Row(
                Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = if (focused) 1.02f else 1f
                        scaleY = if (focused) 1.02f else 1f
                    }
                    .clip(RoundedCornerShape(TnRadii.md))
                    .background(c.surface)
                    .border(if (focused) 3.dp else 1.dp, if (focused) c.rose else Color.Transparent, RoundedCornerShape(TnRadii.md))
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onChipClick() }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GradientTile(chip.icon, chip.grad, Modifier.size(26.dp), iconSize = 14.dp, corner = 13.dp)
                Column(Modifier.weight(1f)) {
                    Text(chip.title, color = c.ink, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(value, color = c.muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        SourceChip(Modifier.weight(1f), selectedLabel, sources, selected, onSelect)
    }
}

@Composable
fun PosterRail(
    posters: List<com.tetonova.core.model.PosterItem>,
    showProgress: Boolean,
    onOpenDetail: (DetailArg) -> Unit,
    canLoadMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    val listState = rememberLazyListState()

    // Auto-load when user scrolls near the end (within 4 items of last).
    LaunchedEffect(listState, canLoadMore, loadingMore, posters.size) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= (totalItems - 4).coerceAtLeast(0)
        }.collect { nearEnd ->
            if (nearEnd && canLoadMore && !loadingMore) {
                onLoadMore()
            }
        }
    }

    LazyRow(
        Modifier.bleedBoth(20.dp),
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(posters, key = { it.url ?: it.title }) { p ->
            Poster(
                item = p,
                modifier = Modifier.width(120.dp),
                showProgress = showProgress,
                onClick = { onOpenDetail(p.toDetailArg()) },
            )
        }
        if (loadingMore) {
            items(6) { i ->
                LoadingPosterSkeleton(i)
            }
        }
    }
}

@Composable
private fun LoadingPosterSkeleton(index: Int) {
    val c = TnTheme.colors
    Column(Modifier.width(120.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(TnRadii.md))
                .tnGradient(gradColors(index + 3)),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .width(54.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(TnRadii.pill))
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(78.dp)
                    .clip(RoundedCornerShape(39.dp))
                    .background(Color.White.copy(alpha = 0.10f)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth(0.88f)
                .height(14.dp)
                .clip(RoundedCornerShape(TnRadii.pill))
                .background(c.line2),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth(0.56f)
                .height(12.dp)
                .clip(RoundedCornerShape(TnRadii.pill))
                .background(c.line),
        )
    }
}
