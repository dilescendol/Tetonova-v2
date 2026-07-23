package com.tetonova.core.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

/** Native adapter for JavHey's catalog and Base64-packed playback mirror list. */
object JavHeySource {
    const val DEFAULT_BASE = "https://javhey.com"

    fun isJavHey(url: String): Boolean = runCatching {
        URI(url).host.orEmpty().lowercase().let { it == "javhey.com" || it.endsWith(".javhey.com") }
    }.getOrDefault("javhey" in url.lowercase())

    suspend fun listPage(url: String): LivePage {
        val html = LiveClient.getHtml(url) ?: return LivePage(emptyList())
        val document = Jsoup.parse(html, url)
        return LivePage(parseItems(document), nextUrl(document))
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val base = baseOf(baseUrl)
        val url = "$base/search?s=${URLEncoder.encode(query, "UTF-8")}" 
        return LiveClient.getHtml(url)?.let { parseItems(Jsoup.parse(it, url)) }.orEmpty()
    }

    suspend fun detail(url: String): LiveDetail? {
        val html = LiveClient.getHtml(url) ?: return null
        val document = Jsoup.parse(html, url)
        val rawTitle = document.selectFirst("article.post header.post_header h1, article.post h1, h1.post_title")
            ?.text()?.trim()?.ifBlank { null } ?: return null
        val title = cleanTitle(rawTitle)
        val cover = document.selectFirst("div.product div.images img, meta[property=og:image]")?.let { node ->
            if (node.tagName() == "meta") node.attr("content") else node.imageUrl()
        }?.takeIf { it.isNotBlank() }
        val synopsis = document.selectFirst("p.video-description")?.text()?.trim()
            ?.removePrefix("Description:")?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifBlank { null }
        val meta = document.select("div.product_meta")
        val genres = meta.select("span:contains(Category) a, span:contains(Tag) a")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val released = Regex("""\b(?:19|20)\d{2}\b""")
            .find(meta.select("span:contains(Release Day)").text())?.value

        return LiveDetail(
            title = title,
            cover = cover,
            synopsis = synopsis,
            status = "Movie",
            type = "Movie",
            studio = meta.select("span:contains(Actor)").firstOrNull()?.select("a")?.joinToString(", ") { it.text() }
                ?.ifBlank { null },
            released = released,
            genres = genres,
            episodes = listOf(LiveEpisode(1, title, url, cover)),
            url = url,
            country = "JP",
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val html = LiveClient.getHtml(url) ?: return emptyList()
        val encoded = Jsoup.parse(html, url).selectFirst("#links")?.attr("value")?.trim().orEmpty()
        if (encoded.isBlank()) return emptyList()
        val decoded = runCatching { String(Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8) }
            .getOrDefault("")
        return decoded.split(",,,")
            .map(String::trim)
            .filter { it.startsWith("http") }
            .distinct()
            .filter(StreamExtractor::isPlayable)
            .mapIndexed { index, embed ->
                VideoServer(
                    // `#links` is the backing data for the boxed Server 1..N buttons below JavHey's
                    // player. Keep that exact website identity/order; host names made the picker look
                    // unrelated to the page and speed sorting shuffled the numbered buttons.
                    name = "Server ${index + 1}",
                    embedUrl = embed,
                )
            }
    }

    private fun parseItems(document: Document): List<LiveItem> =
        document.select("div.article_standard_view > article.item, article.item").mapNotNull { article ->
            val anchor = article.selectFirst("div.item_content > h3 > a[href], h3 a[href]")
                ?: return@mapNotNull null
            val title = cleanTitle(anchor.text().trim()).ifBlank { return@mapNotNull null }
            val href = absolute(document.baseUri(), anchor.attr("href"))
            val cover = article.selectFirst("div.item_header img, img")?.imageUrl()
            LiveItem(title, href, cover, "Movie", "JAV")
        }.distinctBy { it.url }

    private fun nextUrl(document: Document): String? =
        document.selectFirst("a[rel=next], .pagination a.next, a.next")
            ?.let { absolute(document.baseUri(), it.attr("href")) }
            ?.takeIf { it.startsWith("http") }

    private fun cleanTitle(value: String): String =
        value.replace(Regex("^JAV Subtitle Indonesia\\s*-\\s*", RegexOption.IGNORE_CASE), "").trim()

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("data-lazy-src") }.ifBlank { attr("src") }.trim()
        return raw.takeIf { it.isNotBlank() && !it.startsWith("data:") }?.let { absolute(baseUri(), it) }
    }

    private fun baseOf(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(DEFAULT_BASE)

    private fun absolute(base: String, value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        else -> runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }
}
