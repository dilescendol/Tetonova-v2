package com.tetonova.app.data

import android.util.Log
import com.tetonova.core.scraper.LiveDetail
import com.tetonova.core.scraper.LiveEpisode
import com.tetonova.core.scraper.LiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Client for the panel's server-side catalog cache (scrape-once, serve-to-all) — the read side of
 * [ScraperProxy]'s catalog / search / detail endpoints. For `proxy_enabled` sources the app reads
 * Home/search/detail from here (fast, CDN-frontable) instead of scraping on the device.
 *
 * Every method returns null on ANY miss/error (503 not-cached, 502, empty, non-200, parse failure)
 * so the caller can transparently fall back to live [com.tetonova.core.scraper.LiveSource] scraping.
 * The responses mirror the JVM scraper's shapes, so the DTOs map straight back to [LiveItem]/[LiveDetail].
 */
class CatalogApi(private val panelBase: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Home rails for a source: (section url → items). Mirrors the service `/home` `{sections[]}`. */
    suspend fun home(source: SourceOverride): List<Pair<String, List<LiveItem>>>? {
        val path = source.proxyPaths?.catalog?.takeIf { it.isNotBlank() } ?: return null
        val body = get(path) ?: return null
        val dto = runCatching { json.decodeFromString<CatalogDto>(body) }.getOrNull() ?: return null
        if (dto.sections.isEmpty()) return null
        return dto.sections.map { it.url to it.items.map(::toItem) }
    }

    /** Cached search hits for a source, or null on miss → caller goes live. */
    suspend fun search(source: SourceOverride, query: String): List<LiveItem>? {
        val path = source.proxyPaths?.search?.takeIf { it.isNotBlank() } ?: return null
        val body = get("$path?q=${enc(query)}") ?: return null
        val dto = runCatching { json.decodeFromString<ItemsDto>(body) }.getOrNull() ?: return null
        return dto.items.map(::toItem)
    }

    /** Cached detail for a source URL, or null on miss / unusable payload → caller goes live. */
    suspend fun detail(source: SourceOverride, id: String): LiveDetail? {
        val tmpl = source.proxyPaths?.detail?.takeIf { it.isNotBlank() } ?: return null
        val body = get(tmpl.replace("{id}", enc(id))) ?: return null
        val dto = runCatching { json.decodeFromString<DetailDto>(body) }.getOrNull() ?: return null
        // A result is only useful with a synopsis or episodes (the hero title comes from the list card).
        if (dto.synopsis.isNullOrBlank() && dto.episodes.isEmpty()) return null
        return dto.toLive()
    }

    private suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        val base = panelBase.trim().trimEnd('/')
        if (base.isEmpty() || path.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder().url(base + path).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null // 503 not-cached / 502 unreachable → live fallback
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrElse { Log.w("TnCache", "cache GET $path failed: ${it.javaClass.simpleName}"); null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun toItem(d: ItemDto) = LiveItem(d.title, d.url, d.cover, d.type, d.status)

    @Serializable private data class CatalogDto(val sections: List<SectionDto> = emptyList())
    @Serializable private data class SectionDto(val title: String = "", val url: String = "", val items: List<ItemDto> = emptyList())
    @Serializable private data class ItemsDto(val items: List<ItemDto> = emptyList())
    @Serializable private data class ItemDto(
        val title: String = "", val url: String = "", val cover: String? = null,
        val type: String? = null, val status: String? = null,
    )
    @Serializable private data class EpisodeDto(val num: Int = 0, val title: String = "", val url: String = "")
    @Serializable private data class DetailDto(
        val title: String = "", val url: String = "", val cover: String? = null, val synopsis: String? = null,
        val status: String? = null, val type: String? = null, val studio: String? = null, val released: String? = null,
        val country: String? = null, val seriesUrl: String? = null,
        val genres: List<String> = emptyList(), val episodes: List<EpisodeDto> = emptyList(),
    ) {
        fun toLive() = LiveDetail(
            title = title, cover = cover, synopsis = synopsis, status = status, type = type, studio = studio,
            released = released, genres = genres, episodes = episodes.map { LiveEpisode(it.num, it.title, it.url) },
            url = url, seriesUrl = seriesUrl, country = country,
        )
    }
}
