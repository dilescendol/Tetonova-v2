package com.tetonova.app.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tetonova.app.data.download.DlItem
import com.tetonova.app.data.download.DlUiState
import com.tetonova.app.data.download.DownloadCenter
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.PlayerArg
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme

@Composable
fun DownloadsScreen(onOpenDetail: (DetailArg) -> Unit, onOpenPlayer: (PlayerArg) -> Unit) {
    val c = TnTheme.colors
    val items = DownloadCenter.items // snapshot-state-backed → updates live as downloads progress
    PageScroll {
        SectionHead(title = "Downloads", sub = "${items.size} file")
        if (items.isEmpty()) {
            TnCard {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.roseTint), contentAlignment = Alignment.Center) {
                        TnIcon("download", size = 24.dp, tint = c.rose)
                    }
                    Column {
                        Text("Belum ada unduhan", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Tap ikon unduh di episode untuk simpan offline.", color = c.muted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { item ->
                    DownloadRow(
                        item = item,
                        onPlay = {
                            onOpenPlayer(
                                PlayerArg(
                                    title = item.title, url = item.id, episodeLabel = item.ep,
                                    episodeNum = item.ep.filter { it.isDigit() }.toIntOrNull(), badge = item.badge,
                                ),
                            )
                        },
                        onCancel = { DownloadCenter.remove(item.id) },
                        onRetry = { DownloadCenter.retry(item) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DownloadRow(item: DlItem, onPlay: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit) {
    val c = TnTheme.colors
    val state = DownloadCenter.uiState(item.id)
    val done = state == DlUiState.COMPLETED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(TnRadii.md)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(TnRadii.md))
            .clickable(enabled = done) { onPlay() }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(108.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(TnRadii.sm)).background(c.surface2), contentAlignment = Alignment.Center) {
            if (item.poster != null) {
                AsyncImage(model = item.poster, contentDescription = null, modifier = Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop)
            } else {
                TnIcon("download", size = 22.dp, tint = c.muted)
            }
            if (done) {
                Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(Color.Black.copy(0.45f)), contentAlignment = Alignment.Center) {
                    TnIcon("play", size = 14.dp, tint = Color.White, filled = true)
                }
            } else if (state == DlUiState.DOWNLOADING) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(0.25f))) {
                    Box(Modifier.fillMaxWidth((item.percent / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(c.rose))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(item.ep.ifBlank { "Episode" } + " · " + subtitle(state, item), color = if (state == DlUiState.FAILED) c.rose else c.muted, fontSize = 12.sp)
        }
        // Trailing control: retry (failed) then delete/cancel.
        if (state == DlUiState.FAILED) {
            IconBtn("refresh", c.rose, onRetry)
            Spacer(Modifier.width(6.dp))
        }
        IconBtn("trash", c.muted, onCancel)
    }
}

@Composable
private fun IconBtn(icon: String, tint: Color, onClick: () -> Unit) {
    val c = TnTheme.colors
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c.surface2).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (icon == "spin") CircularProgressIndicator(Modifier.size(16.dp), color = tint, strokeWidth = 2.dp)
        else TnIcon(icon, size = 16.dp, tint = tint)
    }
}

private fun subtitle(state: DlUiState, item: DlItem): String = when (state) {
    DlUiState.COMPLETED -> fmtSize(item.bytes)
    DlUiState.DOWNLOADING -> "${item.percent}%" + (if (item.bytes > 0) " · ${fmtSize(item.bytes)}" else "")
    DlUiState.QUEUED -> "Antri"
    DlUiState.FAILED -> "Gagal — coba lagi"
    DlUiState.RESOLVING -> "Menyiapkan…"
    DlUiState.NONE -> ""
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
