package com.tetonova.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.tetonova.app.R

@DrawableRes
fun loyaltyBadgeRes(tierId: String, tier: Int, active: Boolean): Int? {
    val resolvedTier = resolveLoyaltyTier(tierId, tier)
    return when (resolvedTier) {
        1 -> if (active) R.drawable.loyalty_murid_pendukung_v1 else R.drawable.loyalty_murid_pendukung_v1_dimmed
        2 -> if (active) R.drawable.loyalty_penjaga_sekte_v1 else R.drawable.loyalty_penjaga_sekte_v1_dimmed
        3 -> if (active) R.drawable.loyalty_tetua_luar_v1 else R.drawable.loyalty_tetua_luar_v1_dimmed
        4 -> if (active) R.drawable.loyalty_tetua_dalam_v1 else R.drawable.loyalty_tetua_dalam_v1_dimmed
        5 -> if (active) R.drawable.loyalty_pelindung_agung_v1 else R.drawable.loyalty_pelindung_agung_v1_dimmed
        else -> null
    }
}

private fun resolveLoyaltyTier(tierId: String, fallbackTier: Int): Int =
    when (tierId.trim().lowercase()) {
        "murid-pendukung" -> 1
        "penjaga-sekte" -> 2
        "tetua-luar" -> 3
        "tetua-dalam" -> 4
        "pelindung-agung" -> 5
        else -> fallbackTier
    }

@Composable
fun LoyaltyBadgeArtwork(
    tierId: String,
    tier: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val resolvedTier = resolveLoyaltyTier(tierId, tier)
    val resource = loyaltyBadgeRes(tierId, resolvedTier, active) ?: return
    val accent = when (resolvedTier) {
        1 -> Color(0xFFC98548)
        2 -> Color(0xFF63D8D1)
        3 -> Color(0xFFFFC83D)
        4 -> Color(0xFFFF453A)
        else -> Color(0xFF75F6E5)
    }
    val transition = rememberInfiniteTransition(label = "loyalty badge aura")
    val auraScale by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = if (resolvedTier >= 4) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "loyalty badge aura scale",
    )
    val auraAlpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = if (resolvedTier >= 4) 0.26f else 0.18f,
        animationSpec = infiniteRepeatable(tween(1700), RepeatMode.Reverse),
        label = "loyalty badge aura alpha",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier.fillMaxSize(0.7f).graphicsLayer {
                    scaleX = auraScale
                    scaleY = auraScale
                    alpha = auraAlpha
                }.background(accent, CircleShape),
            )
        }
        Image(
            painter = painterResource(resource),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
