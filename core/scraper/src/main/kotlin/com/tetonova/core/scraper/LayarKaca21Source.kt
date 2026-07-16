package com.tetonova.core.scraper

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

object LayarKaca21Source {

    fun isLayarKaca21(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault(url.lowercase())
        return "lk21official" in host || "nontondrama" in host
    }

    suspend fun servers(html: String, pageUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html, pageUrl)
        return doc.select("a[href*=playeriframe.sbs]")
            .mapNotNull { a ->
                val embed = a.absUrl("href").ifBlank { a.attr("href") }.ifBlank { return@mapNotNull null }
                val name = a.text().trim().ifBlank { StreamExtractor.playableHostLabel(embed) }
                VideoServer(name, if (shouldUnwrap(pageUrl, embed)) unwrap(embed) else embed)
            }
            .distinctBy { it.embedUrl }
    }

    private fun shouldUnwrap(pageUrl: String, embed: String): Boolean {
        val host = runCatching { URI(pageUrl).host.orEmpty().lowercase() }.getOrDefault("")
        val lower = embed.lowercase()
        return "/p2p/" in lower || ("nontondrama" !in host && "/turbovip/" in lower)
    }

    private suspend fun unwrap(embed: String): String {
        val html = LiveClient.getHtml(embed) ?: return embed
        val doc = Jsoup.parse(html, embed)
        return doc.select("iframe[src]")
            .mapNotNull { it.absUrl("src").ifBlank { it.attr("src") }.takeIf(String::isNotBlank) }
            .firstOrNull { url -> "yellowishgather" !in url.lowercase() }
            ?: embed
    }

    fun detail(html: String, pageUrl: String): LiveDetail {
        val parsed = LiveParser.parseDetail(html, pageUrl)
        val doc = Jsoup.parse(html, pageUrl)
        val episodes = seasonEpisodes(doc, html, pageUrl)
        val seriesUrl = seriesUrl(doc, pageUrl) ?: parsed.seriesUrl
        return parsed.copy(
            episodes = episodes.ifEmpty { parsed.episodes },
            type = if (episodes.isNotEmpty()) "Series" else parsed.type,
            seriesUrl = seriesUrl,
        )
    }

    private fun seasonEpisodes(doc: Document, html: String, pageUrl: String): List<LiveEpisode> {
        val json = seasonData(doc, html) ?: return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val seasons = ArrayList<String>()
        val keys = root.keys()
        while (keys.hasNext()) seasons += keys.next()

        val multiSeason = seasons.size > 1
        var global = 1
        return seasons
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .flatMap { key ->
                val season = key.toIntOrNull() ?: 1
                val rows = root.optJSONArray(key) ?: return@flatMap emptyList()
                (0 until rows.length()).mapNotNull { i ->
                    val row = rows.optJSONObject(i) ?: return@mapNotNull null
                    val ep = row.optInt("episode_no").takeIf { it > 0 } ?: return@mapNotNull null
                    val slug = row.optString("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    LiveEpisode(
                        num = global++,
                        title = if (multiSeason) "S$season E$ep" else "Episode $ep",
                        url = "${originOf(pageUrl)}/$slug",
                        season = season,
                        epInSeason = ep,
                    )
                }
            }
    }

    private fun seasonData(doc: Document, html: String): String? =
        doc.selectFirst("script#season-data")?.data()?.trim()?.takeIf { it.startsWith("{") }
            ?: Regex(
                """<script[^>]+id=["']season-data["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(html)?.groupValues?.get(1)?.trim()

    private fun seriesUrl(doc: Document, pageUrl: String): String? {
        val data = doc.selectFirst("script#watch-history-data")?.data()?.trim() ?: return null
        val slug = runCatching { JSONObject(data).optString("slug") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return "${originOf(pageUrl)}/$slug"
    }

    private fun originOf(url: String): String =
        runCatching {
            URI(url).let { uri ->
                buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port > 0) append(':').append(uri.port)
                }
            }
        }.getOrDefault(url.substringBefore("/", ""))
}
