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
    val proxyBypass: ProxyBypass? = null,
    val telemetry: Telemetry? = null,
    val trial: TrialConfig? = null,
    val releaseNotes: ReleaseNotes? = null,
    val helpCenter: HelpCenter? = null,
)

/** Premium trial config published by the panel and mirrored by the server claim endpoint. */
@Serializable
data class TrialConfig(
    @SerialName("duration_seconds") val durationSeconds: Long = 0L,
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
