package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Native adapter for IndoMax21's rotating Muvipro/WordPress domains. */
object IndoMax21Source {
    const val DEFAULT_BASE = "https://otrarevista.com"

    private val knownHosts = listOf(
        "homecookingrocks.com",
        "lomaresort.com",
        "topnetseo.com",
        "otrarevista.com",
    )

    fun isIndoMax21(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return knownHosts.any { host == it || host.endsWith(".$it") } || "indomax21" in url.lowercase()
    }

    suspend fun listPage(url: String): LivePage {
        val html = LiveClient.getHtml(url) ?: return LivePage(emptyList())
        val document = Jsoup.parse(html, url)
        val next = document.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next")
            ?.absUrl("href")?.ifBlank { null }
        return LivePage(parseItems(document), next)
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val base = baseOf(baseUrl)
        val url = "$base/?s=${URLEncoder.encode(query, "UTF-8")}" 
        return LiveClient.getHtml(url)?.let { parseItems(Jsoup.parse(it, url)) }.orEmpty()
    }

    suspend fun detail(url: String): LiveDetail? {
        val html = LiveClient.getHtml(url) ?: return null
        val document = Jsoup.parse(html, url)
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null } ?: return null
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.ifBlank { null }
            ?: document.selectFirst("figure.pull-left img, .gmr-movie-data img")?.imageUrl()
        val synopsis = document.select(".entry-content[itemprop=description] > p, .entry-content-single > p")
            .map { it.text().trim() }
            .firstOrNull { it.length >= 20 }
        val episodeAnchors = document.select(".gmr-listseries a[href], .gmr-eps-list a[href], .button-seasons a[href], ul.gmr-episodes li a[href]")
            .filterNot { it.text().contains("Lihat Semua", ignoreCase = true) }
        val isSeries = episodeAnchors.isNotEmpty() || listOf("/tv/", "/eps/", "/anime/", "/donghua/", "/hentai/", "/serial-tv/")
            .any { it in url.lowercase() }
        val episodes = if (isSeries && episodeAnchors.isNotEmpty()) {
            episodeAnchors.mapIndexedNotNull { index, anchor -> anchor.toEpisode(index + 1) }
                .distinctBy { it.url }
                .sortedWith(compareBy<LiveEpisode>({ it.season ?: 1 }, { it.epInSeason ?: it.num }))
                .mapIndexed { index, episode -> episode.copy(num = index + 1) }
        } else {
            listOf(LiveEpisode(1, title, url, cover))
        }
        val seriesUrl = document.selectFirst(".gmr-listseries a:contains(Lihat Semua Episode)[href]")
            ?.absUrl("href")?.ifBlank { null }
        val genres = document.select(".gmr-moviedata:contains(Genre) a, .gmr-movie-on a[rel~=category]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val released = document.selectFirst(".gmr-moviedata:contains(Tahun) a")?.text()?.trim()
            ?: Regex("""\b(?:19|20)\d{2}\b""").find(title)?.value

        return LiveDetail(
            title = title,
            cover = cover,
            synopsis = synopsis,
            status = if (isSeries) "Series" else "Movie",
            type = if (isSeries) "Series" else "Movie",
            studio = null,
            released = released,
            genres = genres,
            episodes = episodes,
            url = url,
            seriesUrl = seriesUrl,
            country = null,
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val html = LiveClient.getHtml(url) ?: return emptyList()
        val document = Jsoup.parse(html, url)
        val tabs = document.select("ul.muvipro-player-tabs a[href], .muvipro-player-tabs a[href]")
            .mapIndexed { index, anchor ->
                (anchor.text().trim().ifBlank { "Server ${index + 1}" }) to absolute(url, anchor.attr("href"))
            }
            .ifEmpty { listOf("Server 1" to url) }

        val servers = coroutineScope {
            tabs.map { (name, pageUrl) ->
                async {
                    val pageHtml = if (samePage(pageUrl, url)) html else LiveClient.getHtml(pageUrl)
                    val iframe = pageHtml?.let { iframeFrom(Jsoup.parse(it, pageUrl), pageUrl) }
                    iframe?.let { VideoServer(name, it) }
                }
            }.awaitAll().filterNotNull()
        }
        return servers.filter { StreamExtractor.isPlayable(it.embedUrl) }.distinctBy { it.embedUrl }
    }

    private fun parseItems(document: Document): List<LiveItem> =
        document.select("#gmr-main-load article, article.item, article.item-infinite, .gmr-item-modulepost").mapNotNull { article ->
            val anchor = article.selectFirst(".entry-title a[href], h2 a[href]") ?: return@mapNotNull null
            val title = anchor.text().trim().ifBlank { return@mapNotNull null }
            val href = absolute(document.baseUri(), anchor.attr("href"))
            val cover = article.selectFirst(".content-thumbnail img, img")?.imageUrl()
            val series = article.selectFirst(".gmr-numbeps") != null ||
                listOf("/tv/", "/anime/", "/donghua/", "/hentai/", "/serial-tv/").any { it in href.lowercase() }
            val status = article.selectFirst(".gmr-quality-item, .gmr-qual, .gmr-numbeps")?.text()?.trim()?.ifBlank { null }
            LiveItem(title, href, cover, if (series) "Series" else "Movie", status)
        }.distinctBy { it.url }

    private fun Element.toEpisode(fallback: Int): LiveEpisode? {
        val href = absolute(baseUri(), attr("href")).takeIf { it.startsWith("http") } ?: return null
        val raw = attr("title").ifBlank { text() }
            .replace(Regex("(?i)Permalink (?:ke|to):?\\s*"), "").trim()
        val season = Regex("(?i)(?:Season|S)\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("(?i)(?:Episode|Eps?|E)\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        return LiveEpisode(
            num = episode ?: fallback,
            title = episode?.let { "Episode $it" } ?: raw.ifBlank { "Episode $fallback" },
            url = href,
            season = season,
            epInSeason = episode,
        )
    }

    private fun iframeFrom(document: Document, base: String): String? {
        val iframe = document.selectFirst(".gmr-embed-responsive iframe, iframe") ?: return null
        val raw = iframe.attr("data-litespeed-src").ifBlank { iframe.attr("src") }.trim()
        return raw.takeIf { it.isNotBlank() }?.let { absolute(base, it) }
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("data-lazy-src") }.ifBlank { attr("src") }.trim()
        if (raw.isBlank() || raw.startsWith("data:")) return null
        return absolute(baseUri(), raw)
            .replace(Regex("-\\d+x\\d+(?=\\.(?:jpe?g|png|webp)(?:$|\\?))", RegexOption.IGNORE_CASE), "")
    }

    private fun baseOf(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(DEFAULT_BASE)

    private fun samePage(left: String, right: String): Boolean =
        left.substringBefore('?').trimEnd('/') == right.substringBefore('?').trimEnd('/') &&
            left.substringAfter('?', "") == right.substringAfter('?', "")

    private fun absolute(base: String, value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        else -> runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }
}
