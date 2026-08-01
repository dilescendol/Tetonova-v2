package com.tetonova.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The prototype's inline-SVG icon set (app/data.jsx → ICONS). Each entry is 24×24 SVG path
 * data; we build a Compose [ImageVector] from it via [addPathNodes]. Stroke icons by default
 * (sw≈2, round caps); pass `filled = true` for the few solid glyphs (star, play, heart…).
 */
private val ICON_PATHS: Map<String, String> = mapOf(
    "home" to "M3 10.2 12 3l9 7.2V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z",
    "search" to "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zM21 21l-4.3-4.3",
    "forum" to "M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z",
    "book" to "M4 4h7a2 2 0 0 1 2 2v14a2 2 0 0 0-2-2H4zM20 4h-7a2 2 0 0 0-2 2v14a2 2 0 0 1 2-2h7z",
    "download" to "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3",
    "ext" to "M4 5a2 2 0 0 1 2-2h4v6H4zM14 3h4a2 2 0 0 1 2 2v4h-6zM4 14h6v6H6a2 2 0 0 1-2-2zM14 14h6v4a2 2 0 0 1-2 2h-4z",
    "user" to "M20 21v-1a6 6 0 0 0-12 0v1M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
    "fire" to "M12 2c.5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.2.4-2.3 1-3a2.5 2.5 0 0 0 2.5 2.5A2.5 2.5 0 0 0 11 11c0-1.4-.5-2-1-3-1.1-2.1-.2-4 2-6z",
    "plus" to "M12 5v14M5 12h14",
    "refresh" to "M21 12a9 9 0 1 1-3-6.7M21 4v5h-5",
    "pin" to "M12 17v5M9 10.8a2 2 0 0 1-1.1 1.8l-1.8.9A2 2 0 0 0 5 15.3V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.7a2 2 0 0 0-1.1-1.8l-1.8-.9A2 2 0 0 1 15 10.8V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
    "shield" to "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zM9 12l2 2 4-4",
    "arrowUp" to "M12 19V6M5 12l7-7 7 7",
    "comment" to "M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8z",
    "clock" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2",
    "eye" to "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
    "check" to "M20 6 9 17l-5-5",
    "lock" to "M5 11h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM8 11V7a4 4 0 0 1 8 0v4",
    "sparkle" to "M12 2l1.9 5.6a2 2 0 0 0 1.3 1.3L21 11l-5.8 2.1a2 2 0 0 0-1.3 1.3L12 20l-1.9-5.6a2 2 0 0 0-1.3-1.3L3 11l5.8-2.1a2 2 0 0 0 1.3-1.3z",
    "palette" to "M12 22a10 10 0 1 1 0-20c5 0 9 3.6 9 8 0 3-2.5 4-5 4h-2a2 2 0 0 0-1 3.7A1.5 1.5 0 0 1 12 22zM7.5 12a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM12 8a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM16.5 12a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
    "frame" to "M3 3h18v18H3zM3 9h18M9 3v18",
    "banner" to "M21 15a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2zM3 14l5-5 4 4 3-3 6 6",
    "chevR" to "M9 18l6-6-6-6",
    "chevD" to "M6 9l6 6 6-6",
    "heart" to "M20.8 5.6a5.5 5.5 0 0 0-7.8 0L12 6.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8z",
    "bookmark" to "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z",
    "play" to "M6 4l14 8-14 8z",
    "star" to "M12 2l2.9 6.3 6.8.8-5 4.7 1.3 6.8L12 17.8 6 20.6l1.3-6.8-5-4.7 6.8-.8z",
    "trophy" to "M7 4h10v5a5 5 0 0 1-10 0zM7 6H4v1a3 3 0 0 0 3 3M17 6h3v1a3 3 0 0 1-3 3M9 21h6M12 14v7",
    "zap" to "M13 2 4 14h7l-1 8 10-12h-7z",
    "flame2" to "M12 2c.5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.2.4-2.3 1-3a2.5 2.5 0 0 0 2.5 2.5A2.5 2.5 0 0 0 11 11c0-1.4-.5-2-1-3-1.1-2.1-.2-4 2-6z",
    "tv" to "M3 5h18a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM8 21h8",
    "layers" to "M12 2 2 7l10 5 10-5zM2 17l10 5 10-5M2 12l10 5 10-5",
    "edit" to "M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z",
    "send" to "M22 2 11 13M22 2l-7 20-4-9-9-4z",
    "globe" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM2 12h20M12 2a15 15 0 0 1 0 20 15 15 0 0 1 0-20z",
    "flag" to "M4 22V4a1 1 0 0 1 1-1c4 0 4 2 8 2s4-2 4 2v7c0 2 0 2-4 2s-4-2-8-2",
    "smile" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM8 14s1.5 2 4 2 4-2 4-2M9 9h.01M15 9h.01",
    "gear" to "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
    "cloud" to "M17 19H7a4 4 0 0 1 0-8 6 6 0 0 1 11.4-2A4 4 0 0 1 17 19z",
    "bell" to "M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0",
    "moon" to "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z",
    "sun" to "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4",
    "trash" to "M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6",
    "logout" to "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
    "login" to "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3",
    "info" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01",
    "help" to "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01",
    "key" to "M21 2l-2 2M15.5 7.5l3.5-3.5M14 9a5 5 0 1 1-5 5l-7 7v-3l7-7a5 5 0 0 1 5-2z",
    "sliders" to "M4 6h11M4 12h6M4 18h13M19 5v4M14 11v4M21 17v4",
    "file" to "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M9 13h6M9 17h6",
    "upload" to "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12",
    "eyeOff" to "M17.9 17.9A10.4 10.4 0 0 1 12 19c-6.5 0-10-7-10-7a18 18 0 0 1 4.2-5.2M9.9 4.2A10.1 10.1 0 0 1 12 4c6.5 0 10 7 10 7a18 18 0 0 1-2.4 3.3M14.1 14.1a3 3 0 1 1-4.2-4.2M2 2l20 20",
    "mail" to "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM22 6l-10 7L2 6",
)

private val vectorCache = HashMap<String, ImageVector>()

/** Build (and cache) an [ImageVector] for an icon name. */
fun tnIcon(name: String, filled: Boolean = false): ImageVector {
    val key = (if (filled) "f:" else "s:") + name
    vectorCache[key]?.let { return it }
    val path = ICON_PATHS[name] ?: ICON_PATHS["info"]!!
    val nodes = addPathNodes(path)
    val builder = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    if (filled) {
        builder.addPath(pathData = nodes, fill = SolidColor(Color.Black))
    } else {
        builder.addPath(
            pathData = nodes,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build().also { vectorCache[key] = it }
}

/** Themed icon. Tint defaults to the current content color. */
@Composable
fun TnIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    filled: Boolean = false,
) {
    Icon(
        imageVector = tnIcon(name, filled),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}
