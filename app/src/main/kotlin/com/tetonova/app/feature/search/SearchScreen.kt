package com.tetonova.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.TnData
import com.tetonova.app.feature.home.PosterRail
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
    var query by rememberSaveable { mutableStateOf("") }
    // The query that was actually submitted (Enter / search button / chip tap). Search + telemetry
    // only fire on submit — never per keystroke — so trending captures the FULL title, not prefixes.
    var submitted by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val runSearch: (String) -> Unit = { raw ->
        val q = raw.trim()
        query = q
        submitted = q
        focus.clearFocus()
        TnData.startLiveSearch(q)
    }
    // Re-run the last search if results were lost (e.g. process death restored `submitted` but the
    // in-memory result groups were cleared). Returning from Detail keeps both, so this is a no-op then.
    LaunchedEffect(Unit) {
        if (submitted.isNotBlank() && TnData.liveSearchGroups.isEmpty()) TnData.startLiveSearch(submitted)
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
            TnIcon("search", size = 22.dp, tint = c.muted, modifier = Modifier.clickable { runSearch(query) })
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Cari judul, lalu tekan Enter…", color = c.muted, fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = c.ink, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.rose),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Real top searches from the panel (cross-user); fall back to the bundled tags when offline.
        SectionHead(title = "Pencarian populer")
        WrapChips(TnData.popularSearches.ifEmpty { SampleData.searchTags }, onTap = runSearch)

        // Results are driven by the SUBMITTED query (grouped per extension), not the live text field.
        val blank = submitted.isBlank()
        val groups = TnData.liveSearchGroups
        val loading = TnData.liveSearchLoading
        if (blank) {
            // No query yet: show the cross-user "trending this week" rail (most-opened content),
            // falling back to the bundled home rail when the panel has no data / is offline.
            SectionHead(title = "Trending minggu ini")
            PosterGrid(items = TnData.trendingPosters.ifEmpty { TnData.homePosters }.take(12), onOpenDetail = onOpenDetail)
        } else {
            SectionHead(
                title = "Hasil",
                sub = when {
                    loading && groups.isEmpty() -> "Mencari di semua sumber…"
                    else -> "${groups.sumOf { it.posters.size }} judul"
                },
            )
            // One section per extension: source name header + a single horizontal poster row.
            groups.forEach { g ->
                SectionHead(title = g.displayName, sub = "${g.posters.size} judul")
                PosterRail(items = g.posters, showProgress = false, onOpenDetail = onOpenDetail)
            }
            if (loading) {
                Text("Mencari di semua sumber…", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            } else if (groups.isEmpty()) {
                Text("Tidak ada hasil untuk \"$submitted\".", color = c.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
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
