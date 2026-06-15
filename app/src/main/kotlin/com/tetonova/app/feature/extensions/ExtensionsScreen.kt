package com.tetonova.app.feature.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.TnData
import com.tetonova.app.ui.PageScroll
import com.tetonova.core.designsystem.TnCard
import com.tetonova.core.designsystem.TnChip
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.SectionHead
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.theme.gradColors
import com.tetonova.core.designsystem.tnGradient
import com.tetonova.core.model.ExtItem

@Composable
fun ExtensionsScreen() {
    val c = TnTheme.colors
    // Reading panelVersion ties categories/items to the async control-panel load.
    val v = TnData.panelVersion
    val categories = remember(v) { TnData.extCategories() }
    var selectedCat by remember { mutableStateOf("") }
    LaunchedEffect(categories) { if (categories.none { it.id == selectedCat }) selectedCat = categories.firstOrNull()?.id ?: "" }
    val items = remember(selectedCat, v) { TnData.extItems(selectedCat) }
    val selectedLabel = categories.firstOrNull { it.id == selectedCat }?.label ?: ""

    PageScroll {
        Spacer(Modifier.height(8.dp))
        TnCard {
            Column(Modifier.padding(22.dp)) {
                Text("${TnData.totalExtensions} extension tersedia di repo", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                Spacer(Modifier.height(14.dp))
                // Single horizontally-scrolling row (design `.ext-cats{flex-wrap:nowrap;overflow-x:auto}`).
                // A plain Row squeezed the 3rd chip ("Dracin/Drakor") to ~0 width, wrapping it
                // vertically into a tall sliver that ballooned the card height.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(categories.size) { i ->
                        val cat = categories[i]
                        TnChip(
                            text = "${cat.label} (${cat.count})",
                            selected = selectedCat == cat.id,
                            onClick = { selectedCat = cat.id },
                        )
                    }
                }
            }
        }
        SectionHead(title = selectedLabel, sub = "${items.size} source")
        BoxWithConstraints {
            val cols = if (maxWidth >= 560.dp) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items.chunked(cols).forEachIndexed { rowIdx, row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i, e -> ExtCard(e, rowIdx * cols + i, Modifier.weight(1f)) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExtCard(e: ExtItem, index: Int, modifier: Modifier = Modifier) {
    val c = TnTheme.colors
    TnCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(TnRadii.sm)).tnGradient(gradColors(index)),
                    contentAlignment = Alignment.Center,
                ) { Text(e.name.take(1), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp) }
                Column(Modifier.weight(1f)) {
                    Text(e.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (e.live) c.rose else c.line2))
                        Text(e.source, color = c.muted, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    TnIcon("check", size = 15.dp, tint = c.rose)
                    Text("Installed", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(TnRadii.pill))
                        .background(c.surface)
                        .border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                ) { Text("Disable", color = c.ink2, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
