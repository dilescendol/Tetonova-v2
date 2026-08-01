package com.tetonova.app.data

import android.util.Log
import com.tetonova.core.scraper.LiveDetail
import com.tetonova.core.scraper.LiveEpisode
import com.tetonova.core.scraper.LiveItem
import com.tetonova.core.scraper.LivePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CachedCatalogSection(
    val url: String,
    val items: List<LiveItem>,
    val nextUrl: String?,
)

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
    // 12s (not 3s): a cold panel search cache scrapes upstream synchronously (up to ~20s) before
    // answering — bailing at 3s made the FIRST search of a query always miss to on-device live
    // scraping while the panel finished caching in the background ("search ulang baru muncul").
    // Home-rail waits are unaffected: they're capped separately by HOME_CACHE_WAIT_MS.
    private val client = TnHttp.client.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** Home rails for a source: (section url → items). Mirrors the service `/home` `{sections[]}`. */
    suspend fun home(source: SourceOverride): List<CachedCatalogSection>? {
        val pageCount = homePageCount(source)
        val out = ArrayList<CachedCatalogSection>()
        val seen = HashSet<String>()
        for (page in 1..pageCount) {
            val section = homePage(source, page) ?: continue
            if (section.url.isBlank() || !seen.add(section.url)) continue
            out += section
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** One configured Home link page (`page=1` => first home link, `page=2` => second, etc). */
    suspend fun homePage(source: SourceOverride, page: Int): CachedCatalogSection? {
        val path = source.proxyPaths?.catalog?.takeIf { it.isNotBlank() } ?: return null
        val body = get(withPage(path, page.coerceAtLeast(1))) ?: return null
        val dto = runCatching { json.decodeFromString<CatalogDto>(body) }.getOrNull() ?: return null
        val section = dto.sections.firstOrNull { it.items.isNotEmpty() } ?: return null
        return CachedCatalogSection(section.url, section.items.map(::toItem), section.nextUrl)
    }

    /** One cached list page by absolute source URL, used by Home "Muat lagi". */
    suspend fun listPage(source: SourceOverride, url: String): LivePage? {
        val path = source.proxyPaths?.catalog?.takeIf { it.isNotBlank() } ?: return null
        if (url.isBlank()) return null
        val body = get(path + (if ('?' in path) "&" else "?") + "url=${enc(url)}") ?: return null
        val dto = runCatching { json.decodeFromString<CatalogDto>(body) }.getOrNull() ?: return null
        val section = dto.sections.firstOrNull { it.items.isNotEmpty() } ?: return null
        return LivePage(section.items.map(::toItem), section.nextUrl)
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
    private fun withPage(path: String, page: Int): String = path + (if ('?' in path) "&" else "?") + "page=$page"
    private fun homePageCount(source: SourceOverride): Int {
        val urls = (source.homeLinks?.showAll.orEmpty() + source.homeLinks?.showOnClick.orEmpty())
            .map { it.url }
            .filter { it.isNotBlank() }
            .distinct()
        return urls.size.coerceAtLeast(1).coerceAtMost(20)
    }

    @Serializable private data class CatalogDto(val sections: List<SectionDto> = emptyList())
    @Serializable private data class SectionDto(
        val title: String = "",
        val url: String = "",
        val nextUrl: String? = null,
        val items: List<ItemDto> = emptyList(),
    )
    @Serializable private data class ItemsDto(val items: List<ItemDto> = emptyList())
    @Serializable private data class ItemDto(
        val title: String = "", val url: String = "", val cover: String? = null,
        val type: String? = null, val status: String? = null,
    )
    @Serializable private data class EpisodeDto(val num: Int = 0, val title: String = "", val url: String = "", val thumb: String? = null)
    @Serializable private data class DetailDto(
        val title: String = "", val url: String = "", val cover: String? = null, val synopsis: String? = null,
        val status: String? = null, val type: String? = null, val studio: String? = null, val released: String? = null,
        val country: String? = null, val seriesUrl: String? = null,
        val genres: List<String> = emptyList(), val episodes: List<EpisodeDto> = emptyList(),
    ) {
        private fun isReelShort(): Boolean =
            url.contains("reelshort", true) || episodes.any { it.url.contains("reelshort", true) }

        private fun normalizedEpisodes(): List<LiveEpisode> {
            val reelShort = isReelShort()
            val zeroBased = reelShort && episodes.any { it.num <= 0 }
            val repeatedZero = episodes.count { it.num == 0 } > 1
            return episodes.mapIndexed { index, ep ->
                val num = when {
                    zeroBased && repeatedZero && ep.num <= 0 -> index + 1
                    zeroBased -> ep.num + 1
                    ep.num > 0 -> ep.num
                    else -> index + 1
                }
                val title = if (reelShort && (ep.title.isBlank() || Regex("\\b(?:episode|ep|e)\\s*\\d+\\b", RegexOption.IGNORE_CASE).containsMatchIn(ep.title))) {
                    "Episode $num"
                } else {
                    ep.title
                }
                val epUrl = if (reelShort) {
                    ep.url.replace(Regex("(/episode/)\\d+", RegexOption.IGNORE_CASE)) { it.groupValues[1] + num }
                } else {
                    ep.url
                }
                LiveEpisode(num, title, epUrl, ep.thumb)
            }
        }

        fun toLive() = LiveDetail(
            title = title, cover = cover, synopsis = synopsis, status = status, type = type, studio = studio,
            released = released, genres = genres, episodes = normalizedEpisodes(),
            url = url, seriesUrl = seriesUrl, country = country,
        )
    }
}
