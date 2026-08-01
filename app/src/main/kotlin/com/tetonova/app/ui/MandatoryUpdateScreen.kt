package com.tetonova.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.data.AppUpdateConfig
import com.tetonova.core.designsystem.TnIcon
import kotlinx.coroutines.delay

@Composable
internal fun MandatoryUpdateScreen(
    config: AppUpdateConfig,
    currentVersionName: String,
    currentVersionCode: Int,
    isTv: Boolean,
    onUpdate: () -> Unit,
) {
    BackHandler(enabled = true) { }

    val focusRequester = remember { FocusRequester() }
    var updateFocused by remember { mutableStateOf(false) }
    LaunchedEffect(isTv, config.minimumVersionCode) {
        if (isTv) {
            delay(120)
            runCatching { focusRequester.requestFocus() }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF4B1020), Color(0xFF160B16), Color(0xFF080A12)),
                ),
            )
            .focusRequester(focusRequester)
            .onFocusChanged { updateFocused = it.hasFocus }
            .focusable(enabled = isTv)
            .onPreviewKeyEvent { event ->
                if (!isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onUpdate()
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val wide = maxWidth >= 700.dp
        Column(
            Modifier
                .padding(horizontal = if (wide) 64.dp else 22.dp, vertical = 28.dp)
                .widthIn(max = if (wide) 720.dp else 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (wide) 32.dp else 26.dp))
                .background(Color(0xFFFDF8F7))
                .border(1.dp, Color(0xFFFF7A96).copy(alpha = 0.65f), RoundedCornerShape(if (wide) 32.dp else 26.dp))
                .padding(if (wide) 42.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(if (wide) 82.dp else 68.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFF4D7D), Color(0xFFCB0039)))),
                contentAlignment = Alignment.Center,
            ) {
                TnIcon("shield", size = if (wide) 42.dp else 34.dp, tint = Color.White)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Versi ini sudah tidak didukung",
                color = Color(0xFF21171B),
                fontSize = if (wide) 32.sp else 25.sp,
                lineHeight = if (wide) 38.sp else 31.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                config.updateMessage.ifBlank {
                    "Perbarui TetoNova agar tetap aman dan bisa menggunakan layanan terbaru."
                },
                color = Color(0xFF66575D),
                fontSize = if (wide) 17.sp else 15.sp,
                lineHeight = if (wide) 25.sp else 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFF0E8E9))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Terpasang v$currentVersionName", color = Color(0xFF66575D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("build $currentVersionCode", color = Color(0xFF9A8790), fontSize = 12.sp)
            }
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF4778), Color(0xFFC70038))))
                    .border(
                        width = if (updateFocused) 4.dp else 0.dp,
                        color = Color.White,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable(onClick = onUpdate)
                    .padding(vertical = if (wide) 17.dp else 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TnIcon("download", size = 22.dp, tint = Color.White)
                    Text("Update Sekarang", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Update wajib - layar ini tidak dapat dilewati",
                color = Color(0xFF9A8790),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
