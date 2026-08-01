package com.tetonova.app.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Focus-only [androidx.compose.foundation.Indication] for Android TV: draws a two-tone ring around
 * whatever element currently holds D-pad focus, so it stays visible on both light and accent surfaces. Provided via
 * `LocalIndication` on TV only (see TetoNovaRoot) — every plain `.clickable {}` picks it up automatically.
 * Touch devices keep the default ripple (this is never provided there), so nothing changes on phone/tablet.
 */
class TvFocusIndication(private val ringColor: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusRingNode(interactionSource, ringColor)
    override fun hashCode(): Int = ringColor.hashCode()
    override fun equals(other: Any?): Boolean = other is TvFocusIndication && other.ringColor == ringColor

    private class FocusRingNode(
        private val interactionSource: InteractionSource,
        private val ringColor: Color,
    ) : Modifier.Node(), DrawModifierNode {
        private var focused = false

        override fun onAttach() {
            coroutineScope.launch {
                var count = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is FocusInteraction.Focus -> count++
                        is FocusInteraction.Unfocus -> count--
                    }
                    val now = count > 0
                    if (now != focused) {
                        focused = now
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (focused) {
                val outerWidth = 4.dp.toPx()
                val innerWidth = 2.dp.toPx()
                val r = 12.dp.toPx()
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(outerWidth / 2f, outerWidth / 2f),
                    size = Size(size.width - outerWidth, size.height - outerWidth),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = outerWidth),
                )
                drawRoundRect(
                    color = ringColor,
                    topLeft = Offset(outerWidth / 2f, outerWidth / 2f),
                    size = Size(size.width - outerWidth, size.height - outerWidth),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = innerWidth),
                )
            }
        }
    }
}
