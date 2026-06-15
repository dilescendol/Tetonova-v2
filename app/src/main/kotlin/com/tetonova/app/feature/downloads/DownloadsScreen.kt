package com.tetonova.app.feature.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.PosterGrid
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.model.SampleData

@Composable
fun DownloadsScreen(onOpenDetail: (DetailArg) -> Unit) {
    val c = TnTheme.colors
    PageScroll {
        SectionHead(title = "Downloads", sub = "${SampleData.downloads.size} file")
        PosterGrid(items = SampleData.downloads, onOpenDetail = onOpenDetail)
        Spacer(Modifier.height(20.dp))
        TnCard {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(c.roseTint),
                    contentAlignment = Alignment.Center,
                ) { TnIcon("download", size = 24.dp, tint = c.rose) }
                Column {
                    Text("Kelola unduhan offline", color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("File tersimpan lokal, bisa ditonton tanpa koneksi.", color = c.muted, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
