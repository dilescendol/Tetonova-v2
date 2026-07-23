package com.tetonova.app.feature.landing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.R
import com.tetonova.app.data.rememberGoogleSignIn
import com.tetonova.app.ui.TnPrimaryButton

@Composable
fun LandingScreen(
    wide: Boolean,
) {
    val signIn = rememberGoogleSignIn()
    val art = if (wide) R.drawable.landing_wide else R.drawable.landing_phone

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = painterResource(art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (wide) {
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.86f),
                            0.45f to Color.Black.copy(alpha = 0.54f),
                            1f to Color.Black.copy(alpha = 0.12f),
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.10f),
                            0.48f to Color.Black.copy(alpha = 0.32f),
                            1f to Color.Black.copy(alpha = 0.88f),
                        )
                    },
                ),
        )

        if (wide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 48.dp, vertical = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LandingCopy(
                    compact = false,
                    onGoogle = signIn,
                    modifier = Modifier.widthIn(max = 460.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().weight(0.62f))
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                LandingCopy(
                    compact = true,
                    onGoogle = signIn,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LandingCopy(
    compact: Boolean,
    onGoogle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val align = if (compact) TextAlign.Center else TextAlign.Start
    val horizontal = if (compact) Alignment.CenterHorizontally else Alignment.Start

    Column(
        modifier,
        horizontalAlignment = horizontal,
        verticalArrangement = Arrangement.spacedBy(if (compact) 18.dp else 22.dp),
    ) {
        Text(
            "TetoNova",
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = if (compact) 48.sp else 62.sp,
            lineHeight = if (compact) 52.sp else 66.sp,
            textAlign = align,
        )
        Column(
            Modifier.fillMaxWidth().widthIn(max = if (compact) 360.dp else 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = horizontal,
        ) {
            TnPrimaryButton(
                text = "Masuk dengan Google",
                icon = "login",
                modifier = Modifier.fillMaxWidth(),
                onClick = onGoogle,
            )
        }
    }
}
