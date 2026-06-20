package com.tetonova.app.data.download

import com.tetonova.core.scraper.StreamVariant

/**
 * Quality helpers shared by the player and the downloader. `resoHeight` is the same label→pixel-height
 * mapping the player uses for its "highest-first" resolution priority (lifted here so the download
 * cap can reuse it); [pickForCap] turns a per-resolution variant list + a Settings cap into the one
 * variant to download.
 */

/** Resolution height from a variant label, robust to label variants ("4K"/"UHD"/"FHD"/"HD"/"720p"…). */
fun resoHeight(label: String): Int {
    val l = label.lowercase()
    return when {
        "2160" in l || "4k" in l || "uhd" in l -> 2160
        "1440" in l || "2k" in l -> 1440
        "1080" in l || "fhd" in l -> 1080
        "720" in l || l == "hd" -> 720
        "480" in l -> 480
        "360" in l -> 360
        "240" in l -> 240
        else -> label.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}

/** Map the persisted `download_quality` setting ("auto"/"1080"/"720"/"480") to a height cap (0 = Auto). */
fun qualityCapHeight(setting: String): Int = setting.filter { it.isDigit() }.toIntOrNull() ?: 0

/**
 * Choose the variant to download for a [capHeight] (0 = Auto/highest):
 *  - a single variant (HLS manifest, or a sniff-only opaque URL) is returned as-is — resolution is
 *    a no-op here (the cap, when it matters for HLS, is a Phase-2 manifest-track concern);
 *  - otherwise pick the highest variant at or below the cap, falling back to the smallest available
 *    when every variant exceeds it (so a 480p cap on a 720p-only source still downloads *something*).
 */
fun pickForCap(variants: List<StreamVariant>, capHeight: Int): StreamVariant? {
    if (variants.isEmpty()) return null
    val highest = variants.maxByOrNull { resoHeight(it.label) } ?: variants.first()
    if (variants.size == 1 || capHeight <= 0) return highest
    val atOrBelow = variants.filter { resoHeight(it.label) in 1..capHeight }
    return atOrBelow.maxByOrNull { resoHeight(it.label) }
        ?: variants.minByOrNull { resoHeight(it.label) }
        ?: highest
}

/** Host of a URL (lowercased), or "" — the key downloads group their HTTP headers under (segments of
 *  an HLS manifest are same-host, so per-host headers cover them too). */
fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host?.lowercase() }.getOrNull().orEmpty()

/** True when the resolved URL is an HLS playlist (so the player/downloader set the m3u8 MIME type). */
fun isHlsUrl(url: String): Boolean {
    val low = url.lowercase()
    val path = low.substringBefore('?')
    return ".m3u8" in low || "/hls/" in path || "/manifest" in path
}
