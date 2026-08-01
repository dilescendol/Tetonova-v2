package com.tetonova.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tetonova.core.designsystem.Poster
import com.tetonova.core.model.PosterItem
import kotlin.math.max

/**
 * A static (non-lazy, safe inside verticalScroll) poster grid.
 *
 * - Default ([columns] = null): **fixed-width** [cell] cells (~Home rail size) so posters stay small
 *   and tidy on wide tablet/TV layouts instead of stretching to a few giant columns. Columns =
 *   however many [cell]-wide posters fit (min 2).
 * - [columns] set: exactly that many **weight-filled** columns (posters share the row width). Used by
 *   Full Library to force 2 prominent columns on a phone; short rows are padded with empty weight.
 */
@Composable
fun PosterGrid(
    items: List<PosterItem>,
    onOpenDetail: (DetailArg) -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
    cell: Dp = 140.dp,
    columns: Int? = null,
) {
    val gap = 12.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = columns ?: max(2, ((maxWidth.value + gap.value) / (cell.value + gap.value)).toInt())
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(cols).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    rowItems.forEach { p ->
                        Poster(
                            item = p,
                            modifier = if (columns != null) Modifier.weight(1f) else Modifier.width(cell),
                            showProgress = showProgress,
                            onClick = { onOpenDetail(p.toDetailArg()) },
                        )
                    }
                    // Pad the last partial row so weight-filled posters keep their column width.
                    if (columns != null) repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
