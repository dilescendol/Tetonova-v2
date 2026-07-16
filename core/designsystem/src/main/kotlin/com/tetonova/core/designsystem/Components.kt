package com.tetonova.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetonova.core.designsystem.theme.RoseGradientColors
import com.tetonova.core.designsystem.theme.TnRadii
import com.tetonova.core.designsystem.theme.TnTheme
import com.tetonova.core.designsystem.theme.gradColors
import com.tetonova.core.model.PosterItem
import com.tetonova.core.model.SampleData

/** 135° (top-left → bottom-right) gradient fill, true diagonal at any size. */
fun Modifier.tnGradient(colors: List<Color>): Modifier = drawBehind {
    drawRect(Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height)))
}

/** Real cover art for the demo titles that ship one (else null → gradient placeholder). */
fun coverForTitle(title: String?): String? {
    if (title == null) return null
    return when (title.lowercase().trim()) {
        "throne of seal" -> "file:///android_asset/covers/throne-of-seal.jpg"
        "jade dynasty" -> "file:///android_asset/covers/jade-dynasty.jpg"
        else -> null
    }
}

/**
 * Poster artwork. Uses a real cover when [coverTitle] resolves one (components.jsx → coverFor),
 * otherwise the stylized gradient placeholder (components.jsx → Art).
 */
@Composable
fun Art(art: Int, label: String, modifier: Modifier = Modifier, coverTitle: String? = null, coverUrl: String? = null) {
    // Real scraped cover (from the source site) wins; then a bundled asset cover; otherwise resolve
    // by title (Jikan/MAL) via CoverProvider. Stays a gradient placeholder until a match comes back
    // — never blocks rendering.
    val cover by androidx.compose.runtime.produceState<String?>(
        coverUrl?.takeIf { it.isNotBlank() } ?: coverForTitle(coverTitle), coverUrl, coverTitle,
    ) {
        val direct = coverUrl?.takeIf { it.isNotBlank() } ?: coverForTitle(coverTitle)
        value = direct ?: coverTitle?.let { runCatching { CoverProvider.resolve?.invoke(it) }.getOrNull() }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(TnRadii.md))
            .tnGradient(gradColors(art))
            .drawWithContent {
                drawContent()
                if (cover != null) return@drawWithContent
                // diagonal stripe overlay (≈ repeating-linear-gradient 135deg)
                val stripe = Color.White.copy(alpha = 0.08f)
                val gap = 12.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(stripe, Offset(x, 0f), Offset(x + size.height, size.height), strokeWidth = 2f)
                    x += gap
                }
            },
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = coverTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // soft corner orb
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(90.dp)
                    .clip(RoundedCornerShape(45.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            )
            Text(
                text = label.uppercase(),
                color = Color.White.copy(alpha = 0.55f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 10.dp, top = 10.dp),
            )
        }
    }
}

/** Rounded gradient tile with a centered white glyph (qchip / stat / medal icon). */
@Composable
fun GradientTile(
    icon: String,
    grad: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 20.dp,
    corner: Dp = 14.dp,
    filled: Boolean = false,
) {
    Box(
        modifier.clip(RoundedCornerShape(corner)).tnGradient(gradColors(grad)),
        contentAlignment = Alignment.Center,
    ) {
        TnIcon(icon, size = iconSize, tint = Color.White, filled = filled)
    }
}

/** Letter avatar on a stable per-name gradient. */
@Composable
fun Avatar(name: String, size: Dp, modifier: Modifier = Modifier, grad: Int = SampleData.avatarIndex(name)) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size / 2)).tnGradient(gradColors(grad)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** Slim rose progress bar. */
@Composable
fun TnProgress(progress: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(TnRadii.pill))
            .background(TnTheme.colors.line2),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0, 100) / 100f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(TnRadii.pill))
                .tnGradient(RoseGradientColors),
        )
    }
}

/** Poster card (components.jsx → Poster). */
@Composable
fun Poster(
    item: PosterItem,
    modifier: Modifier = Modifier,
    showProgress: Boolean = false,
    onClick: () -> Unit = {},
) {
    val c = TnTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .graphicsLayer {
                scaleX = if (focused) 1.04f else 1f
                scaleY = if (focused) 1.04f else 1f
            }
            .border(3.dp, if (focused) c.rose else Color.Transparent, RoundedCornerShape(TnRadii.md))
            .padding(3.dp)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(TnRadii.md))) {
            Art(item.art, item.title.substringBefore(' '), Modifier.fillMaxSize(), coverTitle = item.title, coverUrl = item.cover)
            item.badge?.let { b ->
                Pill(
                    text = b,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    bg = Color.Black.copy(alpha = 0.42f),
                    fg = Color.White,
                )
            }
            item.ep?.let { e ->
                Pill(
                    text = "EP $e",
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    bg = c.rose,
                    fg = Color.White,
                )
            }
        }
        if (showProgress && item.progress != null) {
            TnProgress(item.progress!!, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Text(
            item.title,
            color = c.ink,
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Small rounded label chip. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, bg: Color, fg: Color) {
    Box(
        modifier.clip(RoundedCornerShape(TnRadii.pill)).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Selectable filter chip (`.chip` / `.chip.on`). */
@Composable
fun TnChip(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    leadingIcon: String? = null,
    onClick: () -> Unit = {},
) {
    val c = TnTheme.colors
    var focused by remember { mutableStateOf(false) }
    val bg = if (selected) c.rose else c.surface
    val fg = if (selected) Color.White else c.ink2
    Row(
        modifier
            .clip(RoundedCornerShape(TnRadii.pill))
            .background(bg)
            .border(if (focused) 3.dp else 1.dp, if (focused) c.rose else if (selected) c.rose else c.line, RoundedCornerShape(TnRadii.pill))
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) TnIcon(leadingIcon, size = 15.dp, tint = fg)
        Text(text, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

/** Section header (components.jsx → SectionHead): title + optional sub + optional right action. */
@Composable
fun SectionHead(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = TnTheme.colors
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
        if (sub != null && action == null) {
            Spacer(Modifier.width(10.dp))
            Text(sub, color = c.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.weight(1f))
        action?.invoke()
    }
}

/** Surface card with the Teto border + radius. */
@Composable
fun TnCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val c = TnTheme.colors
    Box(
        modifier
            .clip(RoundedCornerShape(TnRadii.lg))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(TnRadii.lg)),
    ) { content() }
}
