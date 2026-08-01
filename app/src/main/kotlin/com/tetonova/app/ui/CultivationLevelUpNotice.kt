package com.tetonova.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.CultivationLevelUpEvent
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.TnRadii
import kotlinx.coroutines.delay

private const val LEVEL_NOTICE_MS = 4_500L
private const val REALM_NOTICE_MS = 5_500L

@Composable
internal fun CultivationLevelUpNotice(
    event: CultivationLevelUpEvent,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(event) {
        delay(if (event.realmChanged) REALM_NOTICE_MS else LEVEL_NOTICE_MS)
        onDismiss()
    }
    if (event.realmChanged) RealmBreakthroughNotice(event, onDismiss)
    else LevelUpBanner(event, onDismiss)
}

@Composable
private fun LevelUpBanner(event: CultivationLevelUpEvent, onDismiss: () -> Unit) {
    val appear = remember(event) { Animatable(0.88f) }
    LaunchedEffect(event) { appear.animateTo(1f, tween(260)) }
    Box(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value }
                .shadow(18.dp, RoundedCornerShape(TnRadii.lg))
                .clip(RoundedCornerShape(TnRadii.lg))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF8F102E), Color(0xFFE00046), Color(0xFFFF6A3D)),
                    ),
                )
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                TnIcon("zap", size = 25.dp, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (event.levelsGained > 1) "NAIK ${event.levelsGained} LEVEL" else "KULTIVASI MENINGKAT",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    "Lv.${event.fromLevel} ke Lv.${event.level}",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "${event.realmName} - hasil sesi tontonan sudah dihitung",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 12.sp,
                )
            }
            Text(
                "OK",
                color = Color(0xFF6F0A2C),
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(CircleShape).background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun RealmBreakthroughNotice(event: CultivationLevelUpEvent, onDismiss: () -> Unit) {
    val appear = remember(event) { Animatable(0.72f) }
    val pulse = rememberInfiniteTransition(label = "realm-breakthrough")
    val glow by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
        label = "realm-glow",
    )
    LaunchedEffect(event) { appear.animateTo(1f, tween(420)) }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(24.dp)
                .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(104.dp)
                    .graphicsLayer { scaleX = 0.94f + glow * 0.08f; scaleY = 0.94f + glow * 0.08f }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFFFE17A), Color(0xFFFF5B3A), Color(0xFF9A0037)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                TnIcon("zap", size = 52.dp, tint = Color.White)
            }
            Text(
                "TEROBOSAN BERHASIL",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                event.realmName,
                color = Color(0xFFFFD166),
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Lv.${event.fromLevel} ke Lv.${event.level} - realm baru telah terbuka",
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier.padding(top = 6.dp).clip(CircleShape).background(Color.White)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(
                    "Lanjutkan perjalanan",
                    color = Color(0xFF7D0A2F),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}
