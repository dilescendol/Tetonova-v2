package com.tetonova.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.core.designsystem.TnIcon
import com.tetonova.core.designsystem.theme.RoseGradientColors
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.tnGradient

@Composable
fun TnPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    filledIcon: Boolean = false,
    onClick: () -> Unit = {},
) {
    Row(
        modifier
            .clip(RoundedCornerShape(TnRadii.pill))
            .tnGradient(RoseGradientColors)
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) TnIcon(icon, size = 18.dp, tint = Color.White, filled = filledIcon)
        Text(text, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
    }
}

@Composable
fun TnGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    onClick: () -> Unit = {},
) {
    val c = TnTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(TnRadii.pill))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(TnRadii.pill))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) TnIcon(icon, size = 16.dp, tint = c.ink2)
        Text(text, color = c.ink2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
