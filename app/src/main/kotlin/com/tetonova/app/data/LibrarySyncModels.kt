package com.tetonova.app.data

import kotlinx.serialization.Serializable

/**
 * Wire models for per-account library sync (`/api/v1/me/library`). `updatedAt` is the local
 * epoch-millis of the change — the server merges last-write-wins on it, so these carry across
 * devices and survive offline edits. Shared by the three stores' export/merge and [LibrarySyncApi].
 */
@Serializable
data class FollowedRow(
    val key: String,                 // source/series URL (identity)
    val title: String = "",
    val cover: String? = null,
    val badge: String? = null,
    val sub: String = "",
    val active: Boolean = true,      // false = unfollow tombstone
    val updatedAt: Long = 0,
)

@Serializable
data class HistoryRow(
    val key: String,                 // series URL (identity)
    val title: String = "",
    val cover: String? = null,
    val badge: String? = null,
    val sub: String = "",
    val epUrl: String? = null,       // last episode played (drives the progress bar)
    val updatedAt: Long = 0,
)

@Serializable
data class ProgressRow(
    val epUrl: String,               // episode URL (identity)
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class LibraryPayload(
    val followed: List<FollowedRow> = emptyList(),
    val history: List<HistoryRow> = emptyList(),
    val progress: List<ProgressRow> = emptyList(),
)

@Serializable
data class LibraryResponse(val library: LibraryPayload = LibraryPayload())
