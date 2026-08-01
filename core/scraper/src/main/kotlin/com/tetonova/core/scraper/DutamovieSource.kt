package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Native adapter for DutaMovie's Muvipro/WordPress catalog and per-page player mirrors. */
object DutamovieSource {

    // Current entry point. DutaMovie moved off restaurantesabadell.com (that origin now answers
    // Cloudflare 522) onto a bare IP. Let's Encrypt issues IP-SAN certificates now, so this validates
    // normally — no TLS opt-out needed. Panel-configured base URLs still win at runtime; this is the
    // built-in fallback.
    const val DEFAULT_BASE = "https://204.3.234.75"

    private val knownHosts = listOf(
        // A bare IP has no domain to match on, so `isDutamovie()` would reject it without this entry
        // and the URL never reaches this scraper.
        "204.3.234.75",
        "restaurantesabadell.com",
        "bdmoviesonline.com",
        "ppspublishers.com",
        "dutamovie21",
        "dutamovies21",
        // KlikXXi (klikxxi.shop) runs the SAME gmovie/muvipro theme (article.item, gmr-*, admin-ajax
        // muvipro_player_content), so it shares this scraper. It's ISP-blocked on-device → served via
        // the panel proxy from the scrape VPS, like JavHey.
        "klikxxi",
    )

    fun isDutamovie(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return knownHosts.any { it in host } || "dutamovie" in url.lowercase()
    }

    suspend fun listPage(url: String): LivePage {
        val html = LiveClient.getHtml(url) ?: return LivePage(emptyList())
        val document = Jsoup.parse(html, url)
        val items = parseItems(document)
        val next = document.selectFirst("a.next.page-numbers, a[rel=next], .pagination a.next")
            ?.absUrl("href")?.ifBlank { null }
        return LivePage(items, next)
    }

    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        if (query.isBlank()) return emptyList()
        val base = usableBase(baseUrl)
        val url = "$base/?s=${URLEncoder.encode(query, "UTF-8")}&post_type%5B%5D=post&post_type%5B%5D=tv"
        val html = LiveClient.getHtml(url) ?: return emptyList()
        return parseItems(Jsoup.parse(html, url))
    }

    suspend fun detail(url: String): LiveDetail? {
        val html = LiveClient.getHtml(url) ?: return null
        val document = Jsoup.parse(html, url)
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null } ?: return null
        val cover = document.selectFirst("figure.pull-left img, .gmr-movie-data img")?.imageUrl()
        val synopsis = document.select("div[itemprop=description] > p")
            .map { it.text().trim() }
            .firstOrNull { it.length >= 20 }
        val isSeries = "/tv/" in url || document.select("div.gmr-listseries a[href*=/eps/], div.vid-episodes a").isNotEmpty()
        val genres = document.select(".gmr-movie-on a[rel~=category], .gmr-moviedata a[rel~=category]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = document.selectFirst(".gmr-moviedata a[href*=/year/]")?.text()?.trim()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value
        val studio = metadataValue(document, "Jaringan")
        val quality = document.selectFirst(".gmr-quality-item")?.text()?.trim()?.ifBlank { null }

        val episodeAnchors = document.select("div.vid-episodes a[href], div.gmr-listseries a[href*=/eps/]")
        val episodes = if (isSeries) {
            episodeAnchors.mapIndexedNotNull { index, anchor -> anchor.toEpisode(index + 1) }
                .distinctBy { it.url }
                .sortedWith(compareBy<LiveEpisode>({ it.season ?: 1 }, { it.epInSeason ?: it.num }))
                .mapIndexed { index, episode -> episode.copy(num = index + 1) }
        } else {
            listOf(LiveEpisode(1, title, url, cover))
        }

        return LiveDetail(
            title = title.substringBefore(" Episode ", title).trim(),
            cover = cover,
            synopsis = synopsis,
            status = quality ?: if (isSeries) "Series" else "Movie",
            type = if (isSeries) "Series" else "Movie",
            studio = studio,
            released = year,
            genres = genres,
            episodes = episodes,
            url = url,
            country = countryCode(document),
        )
    }

    suspend fun servers(url: String): List<VideoServer> {
        val html = LiveClient.getHtml(url) ?: return emptyList()
        val document = Jsoup.parse(html, url)
        val origin = baseOf(url)
        val postId = document.selectFirst("div#muvipro_player_content_id")?.attr("data-id")?.trim()
        val embeds = if (!postId.isNullOrBlank()) ajaxEmbeds(document, origin, url, postId)
        else tabEmbeds(document, url, html)

        val streamServers = embeds.mapIndexedNotNull { index, pair ->
            val embed = pair.second.takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
            VideoServer(
                // Keep the exact player-tab identity and order shown by DutaMovie. The host name belongs
                // to the implementation behind the tab; exposing it here made the picker look random.
                name = pair.first.trim().ifBlank { "Server ${index + 1}" },
                embedUrl = embed,
            )
        }

        // `gmr-download-list` is a separate download area on the page. Those links must never be mixed
        // into the playback Source picker, whose source of truth is only `muvipro-player-tabs` above.
        val all = streamServers
            .filter { StreamExtractor.isPlayable(it.embedUrl) }
            .distinctBy { it.embedUrl }
        System.out.println("[TnDutamovie] servers ${all.size} for $url -> ${all.map { it.name }}")
        return all
    }

    private suspend fun tabEmbeds(document: Document, pageUrl: String, firstHtml: String): List<Pair<String, String>> {
        val tabs = document.select("ul.muvipro-player-tabs li a[href]")
        if (tabs.isEmpty()) {
            val iframe = iframeFrom(document, pageUrl) ?: return emptyList()
            return listOf("Server 1" to iframe)
        }
        return coroutineScope {
            tabs.mapIndexed { index, anchor ->
                async {
                    val tabUrl = absolute(pageUrl, anchor.attr("href"))
                    val pageHtml = if (samePage(tabUrl, pageUrl) && !tabUrl.contains("player=")) firstHtml
                    else LiveClient.getHtml(tabUrl)
                    val iframe = pageHtml?.let { iframeFrom(Jsoup.parse(it, tabUrl), tabUrl) }
                    iframe?.let { (anchor.text().trim().ifBlank { "Server ${index + 1}" }) to it }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun ajaxEmbeds(document: Document, origin: String, referer: String, postId: String): List<Pair<String, String>> =
        coroutineScope {
            document.select("div.tab-content-ajax[id]").mapIndexed { index, tab ->
                async {
                    val form = listOf(
                        "action" to "muvipro_player_content",
                        "tab" to tab.id(),
                        "post_id" to postId,
                    ).joinToString("&") { (key, value) ->
                        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                    }
                    val body = LiveClient.postBypass(
                        "$origin/wp-admin/admin-ajax.php",
                        form,
                        referer = referer,
                        origin = origin,
                        isValid = { "iframe" in it.lowercase() },
                    )
                    val iframe = body?.let { iframeFrom(Jsoup.parse(it, origin), origin) }
                    iframe?.let { "Server ${index + 1}" to it }
                }
            }.awaitAll().filterNotNull()
        }

    private fun parseItems(document: Document): List<LiveItem> =
        document.select("article.item, article.item-infinite").mapNotNull { article ->
            val link = article.selectFirst("h2.entry-title > a[href]") ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { return@mapNotNull null }
            val href = link.absUrl("href").ifBlank { absolute(document.baseUri(), link.attr("href")) }
            val cover = article.selectFirst(".content-thumbnail img, a > img")?.imageUrl()
            val episode = article.selectFirst(".gmr-numbeps span")?.text()?.trim()?.toIntOrNull()
            val quality = article.selectFirst(".gmr-quality-item, .gmr-qual")?.text()?.trim()?.replace("-", "")?.ifBlank { null }
            val series = "/tv/" in href || episode != null
            LiveItem(
                title = title,
                url = href,
                cover = cover,
                type = if (series) "Series" else "Movie",
                status = quality ?: episode?.let { "Episode $it" },
            )
        }.distinctBy { it.url }

    private fun Element.toEpisode(fallback: Int): LiveEpisode? {
        val href = absUrl("href").ifBlank { attr("href") }.takeIf { it.startsWith("http") } ?: return null
        val raw = attr("title").ifBlank { text() }
            .replace(Regex("(?i)Permalink (?:ke|to):?\\s*"), "").trim()
        val season = Regex("(?i)(?:Season|S)\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("(?i)(?:Episode|Eps?)\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull()
            ?: raw.split(Regex("\\s+")).lastOrNull()?.filter(Char::isDigit)?.toIntOrNull()
        return LiveEpisode(
            num = episode ?: fallback,
            title = episode?.let { "Episode $it" } ?: raw.ifBlank { "Episode $fallback" },
            url = href,
            season = season,
            epInSeason = episode,
        )
    }

    private fun iframeFrom(document: Document, base: String): String? {
        val iframe = document.selectFirst("div.gmr-embed-responsive iframe, iframe") ?: return null
        val raw = iframe.attr("data-litespeed-src").ifBlank { iframe.attr("src") }.trim()
        return raw.takeIf { it.isNotBlank() }?.let { absolute(base, it) }
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").ifBlank { attr("data-lazy-src") }.ifBlank {
            attr("srcset").substringBefore(' ').ifBlank { attr("src") }
        }.trim()
        if (raw.isBlank()) return null
        val resolved = absolute(baseUri(), raw)
        return resolved.replace(Regex("-\\d+x\\d+(?=\\.(?:jpe?g|png|webp)(?:$|\\?))", RegexOption.IGNORE_CASE), "")
    }

    private fun metadataValue(document: Document, label: String): String? =
        document.select("div.gmr-moviedata").firstOrNull { it.text().trim().startsWith(label, ignoreCase = true) }
            ?.let { row -> row.select("strong").remove(); row.text().trim().trimStart(':').trim().ifBlank { null } }

    private fun countryCode(document: Document): String? =
        document.selectFirst(".gmr-moviedata a[href*=/country/]")?.text()?.trim()?.lowercase()?.let {
            when {
                "indonesia" in it -> "ID"
                "korea" in it -> "KR"
                "china" in it -> "CN"
                "japan" in it -> "JP"
                "thailand" in it -> "TH"
                "philippines" in it -> "PH"
                else -> null
            }
        }

    private fun usableBase(url: String): String = baseOf(url).takeIf { it.startsWith("http") } ?: DEFAULT_BASE

    private fun baseOf(url: String): String = runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(DEFAULT_BASE)

    private fun samePage(left: String, right: String): Boolean =
        left.substringBefore('?').trimEnd('/') == right.substringBefore('?').trimEnd('/')

    private fun absolute(base: String, value: String): String = when {
        value.startsWith("http") -> value
        value.startsWith("//") -> "https:$value"
        else -> runCatching { URI(base).resolve(value).toString() }.getOrDefault(value)
    }
}
