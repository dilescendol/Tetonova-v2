package com.tetonova.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Kasane-Teto crimson design-token system from the handoff
 * (design_handoff_tetonova/README.md → Design Tokens, app/app.css :root).
 * Tokens that Material3's ColorScheme can't hold live here and are provided via
 * [LocalTnColors]. Accent selection re-points the `rose` family (see TetoNovaTheme).
 */
@Immutable
data class TnColors(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val line: Color,
    val line2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val faint: Color,
    val rose: Color,
    val roseDeep: Color,
    val roseSoft: Color,
    val roseTint: Color,
    val coral: Color,
    val coralSoft: Color,
    val grape: Color,
    val grapeSoft: Color,
    val isDark: Boolean,
)

val LightTnColors = TnColors(
    bg = Color(0xFFFBEBEE),
    bg2 = Color(0xFFFDF2F4),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFFFF5F7),
    surface3 = Color(0xFFFDE6EA),
    line = Color(0xFFF4D7DD),
    line2 = Color(0xFFEEC5CD),
    ink = Color(0xFF2A0E15),
    ink2 = Color(0xFF5A2A35),
    muted = Color(0xFF94707A),
    faint = Color(0xFFC2A3AA),
    rose = Color(0xFFD7003B),
    roseDeep = Color(0xFF9E0027),
    roseSoft = Color(0xFFFFD9E0),
    roseTint = Color(0xFFFFF0F3),
    coral = Color(0xFFE6315A),
    coralSoft = Color(0xFFFFE1E7),
    grape = Color(0xFF7A0024),
    grapeSoft = Color(0xFFFFD1D9),
    isDark = false,
)

/** App dark theme (derived; the prototype only fully specifies the Detail dark tokens). */
val DarkTnColors = TnColors(
    bg = Color(0xFF130D11),
    bg2 = Color(0xFF1A1117),
    surface = Color(0xFF1E141A),
    surface2 = Color(0xFF251820),
    surface3 = Color(0xFF2E1C25),
    line = Color(0xFF3A2630),
    line2 = Color(0xFF4A2F3B),
    ink = Color(0xFFF6EDF1),
    ink2 = Color(0xFFD8B9C4),
    muted = Color(0xFFA98794),
    faint = Color(0xFF6E5560),
    rose = Color(0xFFEE7099),
    roseDeep = Color(0xFFD7003B),
    roseSoft = Color(0xFF3A1622),
    roseTint = Color(0xFF24151C),
    coral = Color(0xFFE6315A),
    coralSoft = Color(0xFF3A1C24),
    grape = Color(0xFF7A0024),
    grapeSoft = Color(0xFF3A1622),
    isDark = true,
)

/** Cinematic Detail-view dark surface tokens (always dark, independent of app theme). */
object DetailTokens {
    val bg = Color(0xFF0D090B)
    val surface = Color(0xFF1A1117)
    val surface2 = Color(0xFF241820)
    val text = Color(0xFFF6EDF1)
    val textMuted = Color(0xFFB79AA4)
    val border = Color(0xFF2E1F27)
}

/* ----- Radii (README → Radii) ----- */
object TnRadii {
    val sm = 12.dp
    val md = 18.dp
    val lg = 24.dp
    val xl = 30.dp
    val pill = 999.dp
}

/* ----- Gradient palette: GRADS[] from data.jsx (all 135deg) ----- */
private val GradStops: List<List<Color>> = listOf(
    listOf(Color(0xFFFF7A96), Color(0xFFD7003B)),
    listOf(Color(0xFFD7003B), Color(0xFF7A0024)),
    listOf(Color(0xFFFF9EB1), Color(0xFFC8003C)),
    listOf(Color(0xFF9E0027), Color(0xFF3A000D)),
    listOf(Color(0xFFFFB1A3), Color(0xFFD7003B)),
    listOf(Color(0xFFFF5E83), Color(0xFF9E0027)),
    listOf(Color(0xFFFFD1D9), Color(0xFFE6315A)),
    listOf(Color(0xFFFF3A64), Color(0xFF7A0024)),
)

/** Color stops for gradient index `i` (mirrors `g(i)` in data.jsx). */
fun gradColors(i: Int): List<Color> = GradStops[((i % GradStops.size) + GradStops.size) % GradStops.size]

/** 135deg hero gradient (README → Gradients). */
val HeroGradientColors = listOf(Color(0xFFD7003B), Color(0xFF7A0024), Color(0xFF2A0008))
val RoseGradientColors = listOf(Color(0xFFFF5E83), Color(0xFFD7003B), Color(0xFF9E0027))

/** A 135° (top-left → bottom-right) linear brush for the given size. */
fun diagonalBrush(colors: List<Color>, size: androidx.compose.ui.geometry.Size): Brush =
    Brush.linearGradient(colors = colors, start = Offset.Zero, end = Offset(size.width, size.height))

/* ----- User-selectable accent (THEMES in data.jsx) ----- */
data class AccentOption(val id: String, val label: String, val color: Color)

val TnAccents = listOf(
    AccentOption("rose", "Crimson", Color(0xFFD7003B)),
    AccentOption("grape", "Wine", Color(0xFF7A0024)),
    AccentOption("coral", "Coral", Color(0xFFE6315A)),
    AccentOption("teal", "Blush", Color(0xFFFF5E83)),
)

/* ----- Profile customization: frames + banners ----- */
data class FrameOption(val id: String, val name: String, val ring: Brush, val isGradient: Boolean)
data class BannerOption(val id: String, val brush: Brush)

val TnFrames: List<FrameOption> = listOf(
    FrameOption("default", "Default", Brush.linearGradient(listOf(Color(0xFFEEC5CD), Color(0xFFEEC5CD))), false),
    FrameOption("rose", "Crimson", Brush.sweepGradient(listOf(Color(0xFFFF5E83), Color(0xFFD7003B), Color(0xFFFF5E83))), true),
    FrameOption("gold", "Blush", Brush.sweepGradient(listOf(Color(0xFFFFD1D9), Color(0xFFFF7A96), Color(0xFFFFD1D9))), true),
    FrameOption("neon", "Wine", Brush.sweepGradient(listOf(Color(0xFFD7003B), Color(0xFF7A0024), Color(0xFFD7003B))), true),
)

val TnBanners: List<BannerOption> = listOf(
    BannerOption("grape", Brush.linearGradient(listOf(Color(0xFFD7003B), Color(0xFF7A0024), Color(0xFF2A0008)))),
    BannerOption("rose", Brush.linearGradient(listOf(Color(0xFFFF7A96), Color(0xFFD7003B), Color(0xFF9E0027)))),
    BannerOption("dusk", Brush.linearGradient(listOf(Color(0xFFFFB1A3), Color(0xFFD7003B), Color(0xFF3A000D)))),
    BannerOption("ocean", Brush.linearGradient(listOf(Color(0xFFFFD1D9), Color(0xFFFF5E83), Color(0xFF7A0024)))),
)
