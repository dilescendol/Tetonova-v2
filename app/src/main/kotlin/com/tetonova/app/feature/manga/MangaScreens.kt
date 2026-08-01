package com.tetonova.app.feature.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tetonova.app.ui.MangaArg
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.ReaderArg
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.scraper.MangaCard
import com.tetonova.core.scraper.MangaInfo
import com.tetonova.core.scraper.ReaderChapter
import com.tetonova.core.scraper.ShinigamiSource

/** Manga catalog tab: Shinigami's list, with a search box. Its own vertical (image-based), separate
 *  from the video Home/Search — a tap opens the manga detail, never the video player. */
@Composable
fun MangaTab(onOpenManga: (MangaArg) -> Unit) {
    val c = TnTheme.colors
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Accumulated results across pages (blank query = merged Project+Mirror catalog, else search).
    // `seen` dedups across pages; `nextPage` null = last page loaded.
    val cards = remember { mutableStateListOf<MangaCard>() }
    val seen = remember { mutableSetOf<String>() }
    var nextPage by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var firstLoad by remember { mutableStateOf(true) }

    suspend fun fetch(page: Int) =
        if (submitted.isBlank()) ShinigamiSource.catalogPage(page) else ShinigamiSource.searchPage(submitted, page)

    // Reset + load page 1 whenever the submitted query changes.
    LaunchedEffect(submitted) {
        cards.clear(); seen.clear(); nextPage = null; loading = true; firstLoad = true
        val p = fetch(1)
        cards.addAll(p.cards.filter { seen.add(it.id) })
        nextPage = p.nextPage; loading = false; firstLoad = false
    }
    val loadMore: () -> Unit = {
        val np = nextPage
        if (np != null && !loading) {
            loading = true
            scope.launch {
                val p = fetch(np)
                cards.addAll(p.cards.filter { seen.add(it.id) })
                nextPage = p.nextPage; loading = false
            }
        }
    }

    PageScroll {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(TnRadii.md))
                .background(c.surface)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TnIcon("search", size = 22.dp, tint = c.muted, modifier = Modifier.clickable { submitted = query.trim(); focus.clearFocus() })
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Cari manga…", color = c.muted, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.rose),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitted = query.trim(); focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHead(title = if (submitted.isBlank()) "Katalog" else "Hasil: \"$submitted\"")
        when {
            firstLoad -> LoadingRow()
            cards.isEmpty() -> Text("Tidak ada manga.", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            else -> {
                MangaGrid(cards, onOpenManga)
                Spacer(Modifier.height(16.dp))
                when {
                    loading -> LoadingRow()
                    nextPage != null -> Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(TnRadii.md))
                            .background(c.surface)
                            .clickable { loadMore() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Muat lebih banyak", color = c.rose, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Series detail: cover + synopsis + the full chapter list. A chapter tap opens the reader. */
@Composable
fun MangaDetailScreen(arg: MangaArg, onBack: () -> Unit, onOpenReader: (ReaderArg) -> Unit) {
    val c = TnTheme.colors
    val info by produceState<MangaInfo?>(initialValue = null, arg.id) {
        value = ShinigamiSource.detail(arg.id)
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BackChip(onBack)
                    Text(arg.title, color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(
                        model = (info?.cover ?: arg.cover),
                        contentDescription = arg.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(TnRadii.md)).background(c.surface),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        info?.altTitle?.let { Text(it, color = c.ink2, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        val meta = listOfNotNull(info?.status, info?.year).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, color = c.muted, fontSize = 12.sp)
                        info?.genres?.takeIf { it.isNotEmpty() }?.let { Text(it.take(4).joinToString(", "), color = c.muted, fontSize = 12.sp) }
                        info?.chapters?.let { Text("${it.size} chapter", color = c.rose, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            info?.synopsis?.let { syn ->
                item { Text(syn, color = c.ink2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            }
            item { SectionHead(title = "Chapter", modifier = Modifier.padding(horizontal = 20.dp)) }

            when (val d = info) {
                null -> item { LoadingRow() }
                else -> items(d.chapters) { ch ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenReader(ReaderArg(chapterId = ch.id, mangaTitle = arg.title)) }
                            .padding(horizontal = 20.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Chapter ${ShinigamiSource.fmtNum(ch.number)}" + (ch.title.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            color = c.ink,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TnIcon("chevR", size = 18.dp, tint = c.muted)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The reader: pages stacked vertically (works for both paged manga and long webtoon strips). Prev/next
 *  chapter re-key the screen so it reloads and scrolls back to the top. */
@Composable
fun ReaderScreen(arg: ReaderArg, onBack: () -> Unit, onOpenReader: (ReaderArg) -> Unit) {
    val c = TnTheme.colors
    val chapter by produceState<ReaderChapter?>(initialValue = null, arg.chapterId) {
        value = null
        value = ShinigamiSource.pages(arg.chapterId)
    }
    val listState = rememberLazyListState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val ch = chapter) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.rose) }
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(ch.pages) { url ->
                    // Reserve a min height so the list is scrollable *before* each page decodes —
                    // otherwise an un-loaded page is 0px tall and the reader can't scroll past page 1.
                    // The box collapses to the image's real height once it loads.
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 480.dp).background(Color(0xFF111111)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ch.prevId?.let { prev ->
                            NavButton("‹ Sebelumnya", Modifier.weight(1f)) { onOpenReader(arg.copy(chapterId = prev)) }
                        }
                        ch.nextId?.let { next ->
                            NavButton("Berikutnya ›", Modifier.weight(1f)) { onOpenReader(arg.copy(chapterId = next)) }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
        // Floating back + chapter label, always reachable over the pages.
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { TnIcon("chevR", size = 20.dp, tint = Color.White, modifier = Modifier.rotate(180f)) }
            (chapter?.number)?.let {
                Text(
                    "Ch. $it",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---- small shared bits ----

@Composable
private fun MangaGrid(cards: List<MangaCard>, onOpenManga: (MangaArg) -> Unit) {
    val cols = 3
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { card ->
                    MangaPosterCard(card, Modifier.weight(1f)) { onOpenManga(MangaArg(id = card.id, title = card.title, cover = card.cover)) }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MangaPosterCard(card: MangaCard, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = TnTheme.colors
    Column(modifier.clickable { onClick() }) {
        AsyncImage(
            model = card.cover,
            contentDescription = card.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(TnRadii.md)).background(c.surface),
        )
        Spacer(Modifier.height(6.dp))
        Text(card.title, color = c.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        card.latestChapter?.let { Text(it, color = c.muted, fontSize = 11.sp) }
    }
}

@Composable
private fun BackChip(onBack: () -> Unit) {
    val c = TnTheme.colors
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).clickable { onBack() },
        contentAlignment = Alignment.Center,
    ) { TnIcon("chevR", size = 20.dp, tint = c.ink, modifier = Modifier.rotate(180f)) }
}

@Composable
private fun NavButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = TnTheme.colors
    Box(
        modifier.clip(RoundedCornerShape(TnRadii.md)).background(c.rose).clickable { onClick() }.padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LoadingRow() {
    val c = TnTheme.colors
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = c.rose)
    }
}
