package com.tetonova.app.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.FollowedStore
import com.tetonova.app.data.HistoryStore
import com.tetonova.app.data.download.DlItem
import com.tetonova.app.data.download.DownloadCenter
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.toDetailArg
import com.tetonova.core.designsystem.Art
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.model.PosterItem

/**
 * The LIVE source for one Library group — shared by the Profile lane ([com.tetonova.app.feature.profile])
 * and the Full Library screen so they never diverge. No static catalog slices: real history, follows,
 * and downloads. All three are snapshot-backed, so reading them from Compose updates the UI live.
 */
fun libraryGroup(tab: String): List<PosterItem> = when (tab) {
    "followed" -> FollowedStore.items
    "downloads" -> DownloadCenter.items.map { it.toPosterItem() }
    else -> HistoryStore.items // "history"
}

/** A finished/queued download rendered as a catalog poster (cover, progress, source URL for Detail). */
internal fun DlItem.toPosterItem(): PosterItem = PosterItem(
    title = title,
    sub = ep,
    art = artOf(id),
    badge = badge,
    ep = ep,
    progress = percent.takeIf { it in 1..100 },
    cover = poster,
    url = id,
)

private fun artOf(s: String): Int { var h = 0; for (c in s) h = h * 31 + c.code; return ((h % 8) + 8) % 8 }

/**
 * Full Library — History / Followed / Downloads STACKED VERTICALLY.
 * Tiap section: 1 baris horizontal scroll (kiri/kanan).
 * Opened from the Profile lane's "Buka Full Library" button.
 */
@Composable
fun FullLibraryScreen(onBack: () -> Unit, onOpenDetail: (DetailArg) -> Unit) {
    val c = TnTheme.colors
    PageScroll(topInset = true) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.clip(RoundedCornerShape(TnRadii.pill)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
                    .clickable { onBack() }.padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TnIcon("chevR", size = 16.dp, tint = c.ink, modifier = Modifier.graphicsLayer(rotationZ = 180f))
                    Text("Kembali", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Full Library", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }

        // Section: History
        LibrarySection("History", "Riwayat tontonan", libraryGroup("history"), onOpenDetail = onOpenDetail)

        // Section: Followed
        LibrarySection("Followed", "Judul diikuti", libraryGroup("followed"), onOpenDetail = onOpenDetail)

        // Section: Downloads
        LibrarySection("Downloads", "Tersimpan offline", libraryGroup("downloads"), onOpenDetail = onOpenDetail)

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LibrarySection(
    title: String,
    sub: String,
    items: List<PosterItem>,
    onOpenDetail: (DetailArg) -> Unit,
) {
    val c = TnTheme.colors
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        // Header
        Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 2.dp))
        Text(sub, color = c.muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))

        if (items.isEmpty()) {
            Text(
                libraryEmptyHintLib(title),
                color = c.muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        } else {
            // 1 baris horizontal scroll (kiri/kanan)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                items(items, key = { it.url.orEmpty() }) { item ->
                    CompactPosterCard(item, onOpenDetail)
                }
            }
        }
    }
}

@Composable
private fun CompactPosterCard(
    item: PosterItem,
    onOpenDetail: (DetailArg) -> Unit,
) {
    val c = TnTheme.colors
    Column(
        modifier = Modifier.width(120.dp)
            .clip(RoundedCornerShape(TnRadii.md))
            .background(c.surface)
            .clickable { onOpenDetail(item.toDetailArg()) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Poster thumbnail - aspect ratio 2:3 sama kayak Home rail
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(TnRadii.md)),
        ) {
            Art(item.art, item.title.substringBefore(' '), Modifier.fillMaxSize(), coverTitle = item.title, coverUrl = item.cover)
        }
        Text(
            item.title,
            color = c.ink,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp),
        )
        if (item.ep != null) {
            Spacer(Modifier.height(2.dp))
            Text(item.ep!!, color = c.muted, fontSize = 10.sp)
        }
    }
}


private fun libraryEmptyHintLib(title: String): String = when (title) {
    "Followed" -> "Belum ada judul yang diikuti. Tap + di halaman detail untuk mengikuti."
    "Downloads" -> "Belum ada unduhan. Tap ikon unduh di episode untuk simpan offline."
    else -> "Belum ada riwayat. Judul yang kamu buka akan muncul di sini."
}
