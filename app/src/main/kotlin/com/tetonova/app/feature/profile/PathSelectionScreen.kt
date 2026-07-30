package com.tetonova.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.app.ui.CultivationPathArtwork
import com.tetonova.core.designsystem.theme.TnTheme

/**
 * First-run (and switch) cultivation path picker. Both paths are mechanically IDENTICAL — same levels,
 * XP curve and gameplay (LOCKED spec §1.2, §12.7) — so the copy stresses that the choice is identity +
 * cosmetics only, never power. Shown whenever the user's `cultivationPath` is null.
 */
@Composable
fun PathSelectionScreen(
    onSelect: (String) -> Unit,
    error: String? = null,
    canDismiss: Boolean = false,
    onDismiss: () -> Unit = {},
) {
    val c = TnTheme.colors
    var chosen by remember { mutableStateOf<String?>(null) }
    val douQiFocus = remember { FocusRequester() }
    val martialFocus = remember { FocusRequester() }
    val confirmFocus = remember { FocusRequester() }
    val dismissFocus = remember { FocusRequester() }

    // The picker overlays Profile, so move D-pad focus off the page behind it.
    LaunchedEffect(Unit) {
        runCatching { douQiFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .focusGroup()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Pilih Jalan Kultivasi", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kedua jalan punya kekuatan dan kebutuhan XP yang identik. Pilihanmu menentukan nama realm, " +
                "gelar, dan kosmetik — bukan keunggulan.",
            color = c.muted,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(20.dp))

        PathCard(
            pathId = "dou-qi",
            title = "Dou Qi",
            subtitle = "Jalur Xiao Yan — dari Dou Zhi Qi hingga Dou Di, lalu Great Thousand World.",
            selected = chosen == "dou-qi",
            colors = c,
            modifier = Modifier
                .focusRequester(douQiFocus)
                .focusProperties { down = martialFocus },
        ) { chosen = "dou-qi" }
        Spacer(Modifier.height(12.dp))
        PathCard(
            pathId = "martial",
            title = "Martial",
            subtitle = "Jalur Lin Dong — dari Tempered Body hingga Ancestor Realm, lalu Great Thousand World.",
            selected = chosen == "martial",
            colors = c,
            modifier = Modifier
                .focusRequester(martialFocus)
                .focusProperties {
                    up = douQiFocus
                    down = confirmFocus
                },
        ) { chosen = "martial" }

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, color = c.rose, fontSize = 12.sp)
        }

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(confirmFocus)
                .focusProperties {
                    up = if (chosen == "martial") martialFocus else douQiFocus
                    if (canDismiss) down = dismissFocus
                }
                .clip(RoundedCornerShape(50))
                .background(if (chosen != null) c.rose else c.surface)
                .clickable(enabled = chosen != null) { chosen?.let(onSelect) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Konfirmasi Jalan",
                color = if (chosen != null) Color.White else c.muted,
                fontWeight = FontWeight.Bold,
            )
        }
        if (canDismiss) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusRequester(dismissFocus)
                    .focusProperties { up = confirmFocus }
                    .clickable { onDismiss() }
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Nanti saja", color = c.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PathCard(
    pathId: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    colors: com.tetonova.core.designsystem.theme.TnColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.roseTint else c.surface)
            .border(1.dp, if (selected) c.rose else c.line, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CultivationPathArtwork(
            pathId = pathId,
            modifier = Modifier.size(82.dp),
            contentDescription = "Jalan $title",
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = c.muted, fontSize = 12.sp)
        }
    }
}
