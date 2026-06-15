package com.tetonova.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.TnData
import com.tetonova.app.ui.DetailArg
import com.tetonova.app.ui.PageScroll
import com.tetonova.app.ui.PosterGrid
import com.tetonova.app.ui.bleedEnd
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.TnChip
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.model.SampleData

@Composable
fun SearchScreen(onOpenDetail: (DetailArg) -> Unit) {
    val c = TnTheme.colors
    var query by remember { mutableStateOf("") }

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
            TnIcon("search", size = 22.dp, tint = c.muted)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Cari judul, genre, atau sumber…", color = c.muted, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.rose),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionHead(title = "Pencarian populer")
        WrapChips(SampleData.searchTags, onTap = { query = it })

        // Live search across every enabled source (debounced). Results stream in + dedup by title.
        LaunchedEffect(query) {
            kotlinx.coroutines.delay(450)
            TnData.startLiveSearch(query)
        }
        val blank = query.isBlank()
        val live = TnData.liveSearchHits
        val loading = TnData.liveSearchLoading
        SectionHead(
            title = if (blank) "Trending minggu ini" else "Hasil",
            sub = when {
                blank -> null
                loading && live.isEmpty() -> "Mencari di semua sumber…"
                else -> "${live.size} judul"
            },
        )
        if (blank) {
            // No query yet: show the bundled trending rail (not a search result set).
            PosterGrid(items = TnData.posters.take(12), onOpenDetail = onOpenDetail)
        } else {
            PosterGrid(items = live.toList(), onOpenDetail = onOpenDetail)
            if (loading) {
                Text("Mencari di semua sumber…", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            } else if (live.isEmpty()) {
                Text("Tidak ada hasil untuk \"$query\".", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Popular-search chips on a single horizontally-scrolling row (full-bleed to screen edge). */
@Composable
private fun WrapChips(tags: List<String>, onTap: (String) -> Unit = {}) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.bleedEnd(20.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(tags.size) { i -> TnChip(text = tags[i], onClick = { onTap(tags[i]) }) }
    }
}
