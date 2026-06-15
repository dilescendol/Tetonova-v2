package com.tetonova.app.data

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * Loads the bundled repo-tn registry (`assets/tetonova/extensions/`) and its compat catalogs.
 * Pure read layer — parses on demand, caches results, and never throws to callers (a missing or
 * malformed asset just yields empty data, keeping the app usable offline-first).
 */
class ExtensionRegistry(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val catalogCache = HashMap<String, List<CatalogItem>>()

    @Volatile
    var index: RepoIndex = RepoIndex()
        private set

    /** Parse the root index. Cheap — safe to call on startup. */
    fun load() {
        index = runCatching { json.decodeFromString<RepoIndex>(readAsset("$BASE/index.json")) }
            .getOrDefault(RepoIndex())
    }

    val extensions: List<RegistryExtension> get() = index.extensions

    fun extensionById(id: String): RegistryExtension? = index.extensions.firstOrNull { it.id == id }

    /** Lazily parse + cache one extension's compat catalog. */
    fun catalogItems(ext: RegistryExtension): List<CatalogItem> {
        val entry = ext.entry ?: return emptyList()
        return catalogCache.getOrPut(ext.id) {
            runCatching { json.decodeFromString<CompatCatalog>(readAsset("$BASE/$entry")).items }
                .getOrDefault(emptyList())
        }
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val BASE = "tetonova/extensions"
    }
}
