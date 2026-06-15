package com.tetonova.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalTnColors = staticCompositionLocalOf { LightTnColors }

/** Convenience accessor: `TnTheme.colors.rose`. */
object TnTheme {
    val colors: TnColors
        @Composable get() = LocalTnColors.current
}

/**
 * App theme. [darkTheme] drives light/dark token sets; [accent] (a [TnAccents] color) re-points
 * the rose family, exactly like `html[data-accent]` in the prototype.
 */
@Composable
fun TetoNovaTheme(
    darkTheme: Boolean = false,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkTnColors else LightTnColors
    val colors = if (accent != null) base.copy(rose = accent) else base

    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.rose,
            onPrimary = Color.White,
            secondary = colors.coral,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.ink2,
            outline = colors.line2,
        )
    } else {
        lightColorScheme(
            primary = colors.rose,
            onPrimary = Color.White,
            secondary = colors.coral,
            background = colors.bg,
            onBackground = colors.ink,
            surface = colors.surface,
            onSurface = colors.ink,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.ink2,
            outline = colors.line2,
        )
    }

    CompositionLocalProvider(LocalTnColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = TnTypography, content = content)
    }
}
