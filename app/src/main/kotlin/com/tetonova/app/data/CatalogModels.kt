package com.tetonova.app.data

import kotlinx.serialization.Serializable

/**
 * Serializable mirror of a compat fallback catalog (`compat/<id>/catalog.json` in repo-tn).
 * These hold the real seeded titles the app shows when an upstream source can't be reached.
 */
@Serializable
data class CompatCatalog(
    val items: List<CatalogItem> = emptyList(),
)

@Serializable
data class CatalogItem(
    val id: String,
    val title: String,
    val tagline: String? = null,
    val overview: String? = null,
    val contentType: String? = null,
    val yearLabel: String? = null,
    val genres: List<String> = emptyList(),
    val releaseHint: String? = null,
    val durationMinutes: Int? = null,
    val qualities: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val sourceNote: String? = null,
)
