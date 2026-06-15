package com.tetonova.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A vertically-scrolling page body with the standard 20dp horizontal padding (`.page`),
 * centered and capped to [maxContentWidth] (design `--maxw: 1180px`) so content doesn't
 * stretch awkwardly on wide tablet/TV layouts.
 */
@Composable
fun PageScroll(
    modifier: Modifier = Modifier,
    topInset: Boolean = false,
    horizontalPadding: Int = 20,
    maxContentWidth: Dp = 1180.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .let { if (topInset) it.windowInsetsPadding(WindowInsets.statusBars) else it },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = maxContentWidth).padding(horizontal = horizontalPadding.dp),
            content = content,
        )
    }
}

/**
 * Bleeds a full-width element (e.g. the Home spotlight card) past the page's [horizontal] padding
 * to BOTH screen edges. The element draws edge-to-edge; siblings keep the page inset.
 */
fun Modifier.fullBleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val pad = horizontal.roundToPx()
    val widened = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + pad * 2) else constraints
    val placeable = measurable.measure(widened)
    layout(placeable.width, placeable.height) { placeable.place(-pad, 0) }
}

/**
 * Lets a horizontal carousel keep its first item aligned with the page content but extend past the
 * page's TRAILING [horizontal] padding to the right screen edge, so trailing items peek off the
 * edge (carousel feel) instead of being hard-cut at the inset. Use WITHOUT horizontal contentPadding.
 */
fun Modifier.bleedEnd(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val pad = horizontal.roundToPx()
    val widened = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + pad) else constraints
    val placeable = measurable.measure(widened)
    val reported = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(reported, placeable.height) { placeable.place(0, 0) }
}

/**
 * Lets a horizontal carousel bleed past the page's [horizontal] padding to BOTH screen edges, so
 * the first item is flush to the left edge and trailing items peek off the right edge.
 * Use WITHOUT horizontal contentPadding.
 */
fun Modifier.bleedBoth(horizontal: Dp): Modifier = layout { measurable, constraints ->
    val pad = horizontal.roundToPx()
    val widened = if (constraints.hasBoundedWidth) constraints.copy(maxWidth = constraints.maxWidth + pad * 2) else constraints
    val placeable = measurable.measure(widened)
    val reported = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(reported, placeable.height) { placeable.place(-pad, 0) }
}
