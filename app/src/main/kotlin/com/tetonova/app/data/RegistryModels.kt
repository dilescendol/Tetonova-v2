package com.tetonova.app.data

import kotlinx.serialization.Serializable

/**
 * Serializable mirror of repo-tn's published `index.json` (the static extension registry).
 * Bundled into the app at `assets/tetonova/extensions/index.json` and parsed offline-first.
 * Unknown keys are ignored so new published fields don't break older clients.
 */
@Serializable
data class RepoIndex(
    val schemaVersion: String = "",
    val repoName: String = "",
    val repoUrl: String = "",
    val extensions: List<RegistryExtension> = emptyList(),
)

@Serializable
data class RegistryExtension(
    val id: String,
    val name: String,
    val version: String = "",
    val contentTypes: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val bundleUrl: String? = null,
    /** Relative path to the compat catalog, e.g. `compat/anichin/catalog.json`. */
    val entry: String? = null,
    val upstreamUrl: String? = null,
    val apiBaseUrl: String? = null,
    val webBaseUrl: String? = null,
    val accessCode: String? = null,
    val category: String = "",
) {
    val isCompat: Boolean get() = id.endsWith("-compat")
}
