package com.tetonova.core.scraper

import org.jsoup.Jsoup

/** Ngefilm's Muvipro tabs are separate `?player=N` pages; only parse the actual player box so ads never enter our player. */
object NgefilmSource {
    fun isNgefilm(url: String): Boolean = "ngefilm.site" in url.lowercase()

    suspend fun servers(html: String, pageUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html, pageUrl)
        val pages = doc.select(".muvipro-player-tabs a[href]")
            .map { it.text().trim() to it.absUrl("href") }
            .filter { it.second.isNotBlank() }
            .ifEmpty { listOf("Server 1" to pageUrl) }

        return pages.mapNotNull { (name, url) ->
            val page = if (samePage(url, pageUrl)) html else LiveClient.getHtml(url) ?: return@mapNotNull null
            parseServer(page, url, name)
        }.distinctBy { it.embedUrl }
    }

    fun parseServer(html: String, pageUrl: String, name: String): VideoServer? {
        val frame = Jsoup.parse(html, pageUrl)
            .selectFirst(".gmr-embed-responsive iframe[data-litespeed-src], .gmr-embed-responsive iframe[src]")
            ?: return null
        val url = frame.absUrl("data-litespeed-src").ifBlank { frame.absUrl("src") }.ifBlank {
            frame.attr("data-litespeed-src").ifBlank { frame.attr("src") }
        }
        return url.takeIf { it.startsWith("http") }?.let { VideoServer(name.ifBlank { "Server" }, it) }
    }

    private fun samePage(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')
}
