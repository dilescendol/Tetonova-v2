package com.tetonova.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the control-panel public read API (`GET /api/v1/sources`) that the app merges
 * over the bundled registry: per-source enable flag, live API base, access code, category.
 * Unknown keys (telemetry token, branding, mirrors, metadata…) are ignored.
 */
@Serializable
data class SourcesResponse(
    val generatedAt: String = "",
    val count: Int = 0,
    val sources: List<SourceOverride> = emptyList(),
    val supportMe: SupportMe? = null,
    val announcement: Announcement? = null,
    val proxyBypass: ProxyBypass? = null,
    val telemetry: Telemetry? = null,
    val trial: TrialConfig? = null,
    val payment: PaymentConfig? = null,
    val appConfig: AppUpdateConfig? = null,
    val releaseNotes: ReleaseNotes? = null,
    val helpCenter: HelpCenter? = null,
)

/** Remote version policy. The server publishes the already-resolved minimum build after grace rules. */
@Serializable
data class AppUpdateConfig(
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val minimumVersionCode: Int = 0,
    val minimumVersionName: String = "",
    val updatePageUrl: String = "",
    val apkDownloadUrl: String = "",
    /** Legacy alias kept so APKs built before the URL split still open the update page. */
    val updateUrl: String = "",
    val updateMessage: String = "",
)

/** Payer-facing QRIS admin-fee formula published by the panel, so the pre-invoice estimate tracks the
 *  active gateway without an app rebuild. `fee = fixed + ceil(subtotal * bps / 10000)`. */
@Serializable
data class PaymentConfig(
    @SerialName("admin_fee_fixed_idr") val adminFeeFixedIdr: Long = 1000L,
    @SerialName("admin_fee_bps") val adminFeeBps: Long = 70L,
    /** Minimum fee as basis points of the subtotal, so `fee = max(fixed + bps%, floorBps%)`. Lets the
     *  estimate match gateways with a percentage floor (Pakasir = max(Rp310 + 0,7%, 1%)). 0 = no floor. */
    @SerialName("admin_fee_floor_bps") val adminFeeFloorBps: Long = 0L,
)

/** Premium trial config published by the panel and mirrored by the server claim endpoint. */
@Serializable
data class TrialConfig(
    @SerialName("duration_seconds") val durationSeconds: Long = 0L,
    /** Fraction (0–1) of a short-drama's episodes free to non-subscribers. 0 = unset → app default. */
    @SerialName("short_drama_free_percent") val shortDramaFreePercent: Double = 0.0,
)

/**
 * Telemetry ingest config published by the panel. The app reuses [ingestToken] to authenticate
 * the in-app problem report (`POST <base>/api/v1/content-report`) — same shared secret the panel
 * already hands out for `/api/v1/telemetry`, so no extra credential to distribute.
 */
@Serializable
data class Telemetry(
    val ingestToken: String = "",
    val ingestPath: String = "/api/v1/telemetry",
)

/** Changelog shown in-app at Settings → Catatan rilis. [body] is rendered verbatim. */
@Serializable
data class ReleaseNotes(
    val enabled: Boolean = false,
    val body: String = "",
)

/** Help-center content shown in-app at Settings → Pusat bantuan (three free-text sections). */
@Serializable
data class HelpCenter(
    val enabled: Boolean = false,
    val faq: String = "",
    val sourceGuide: String = "",
    val contact: String = "",
)

/**
 * VPS Cloudflare-bypass config published by the panel. The app feeds [flareSolverrEndpoint]
 * (a `/v1` URL) + [flareSolverrToken] into [LiveClient] so blocked/challenged upstreams can be
 * solved server-side; the `/v1cf` (byparr) sibling is derived for managed-challenge sites.
 */
@Serializable
data class ProxyBypass(
    val enabled: Boolean = false,
    val flareSolverrEndpoint: String = "",
    val flareSolverrToken: String = "",
)

/** Donation/support card config published by the panel. */
@Serializable
data class SupportMe(
    val enabled: Boolean = false,
    val url: String = "",
    val label: String = "",
    val description: String = "",
    val buttonLabel: String = "",
)

/** Global banner shown after the user enters the app. */
@Serializable
data class Announcement(
    val id: String = "",
    val enabled: Boolean = false,
    val title: String = "",
    val message: String = "",
    val severity: String = "info",
    val ctaLabel: String = "",
    val ctaUrl: String = "",
    val dismissible: Boolean = true,
) {
    /** Backward-compatible identity for panels that predate the server-side id. */
    fun dismissalId(): String = id.ifBlank {
        listOf(title, message, severity, ctaLabel, ctaUrl, dismissible.toString())
            .joinToString("\u001f")
            .hashCode()
            .toUInt()
            .toString(16)
    }
}

@Serializable
data class SourceOverride(
    val sourceId: String,
    val displayName: String = "",
    val enabled: Boolean = true,
    val apiBaseUrl: String = "",
    val webBaseUrl: String? = null,
    val category: String? = null,
    val accessCode: String? = null,
    val lastProbeStatus: String? = null,
    /** True when this source is a paid/premium source (served via the dramabuzz API provider). Drives
     *  the live "N source" count and is gated behind the subscription paywall. */
    val premium: Boolean = false,
    val homeLinks: HomeLinks? = null,
    /** True when the panel caches this source server-side (scrape-once). The app then reads Home/
     *  search/detail from [proxyPaths] instead of scraping on-device, falling back to live on a miss. */
    val proxyEnabled: Boolean = false,
    val proxyPaths: ProxyPaths? = null,
)

/**
 * Panel cache endpoints for a source (relative to the panel base). `{id}` in [detail]/[playback] is
 * replaced with the URL-encoded source URL. Served from the `source_catalog_cache` table (CDN-frontable).
 */
@Serializable
data class ProxyPaths(
    val catalog: String = "",
    val detail: String = "",
    val playback: String = "",
    val search: String = "",
)

/** Home-section links published per source by the panel (custom label + real web URL). */
@Serializable
data class HomeLinks(
    /** Mode "Semua": shown when no source filter is active. */
    val showAll: List<HomeLink> = emptyList(),
    /** Mode "Klik Source": shown when the user selects this source in Home. */
    val showOnClick: List<HomeLink> = emptyList(),
)

@Serializable
data class HomeLink(
    val label: String = "",
    val url: String = "",
)
