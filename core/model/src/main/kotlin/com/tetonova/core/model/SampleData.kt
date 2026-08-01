package com.tetonova.core.model

/**
 * Static UI config for Home's quick-action chips (Lite Mode / Data Saver / Source). These are app
 * controls, not catalog content — the only thing left here after the fabricated sample catalog/forum/
 * profile data was removed (finished features must read real live data, never placeholder slices).
 */
object SampleData {
    val homeQuickChips = listOf(
        QuickChip("Lite Mode", "Off", "zap", 0),
        QuickChip("Data Saver", "Balanced", "layers", 4),
        QuickChip("Source", "Semua", "globe", 6),
    )

    /** Stable avatar gradient index from a username (deterministic hash → 0..7). */
    fun avatarIndex(name: String): Int {
        var s = 0
        for (c in name) s += c.code
        return s % 8
    }
}
