package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/** NontonAnimeID's Home "Load More" is an admin-ajax POST, not a normal WordPress page. */
object NontonAnimeIDSource {
    private const val CURSOR_FLAG = "tn_nai"
    private const val CURSOR_NONCE = "tn_nonce"
    private const val CURSOR_IDS = "tn_ids"

    fun isCatalogUrl(url: String): Boolean = "nontonanimeid" in url.lowercase()

    suspend fun listPage(url: String): LivePage {
        val params = queryParams(url)
        return if (params[CURSOR_FLAG] == "1") loadMore(url, params) else firstPage(url)
    }

    private suspend fun firstPage(url: String): LivePage {
        val pageUrl = url.substringBefore('#')
        val html = LiveClient.getHtml(pageUrl) ?: return LivePage(emptyList())
        val doc = Jsoup.parse(html, pageUrl)
        val cards = doc.select("#postbaru .misha_posts_wrap article.animeseries")
        val items = parseCards(cards.joinToString("\n") { it.outerHtml() }, pageUrl)
        val ids = postIds(cards.joinToString(" ") { it.className() })
        val nonce = loadMoreConfig(doc.selectFirst("#misha_scripts-js-extra")?.attr("src").orEmpty())
            ?.let { Regex("""[\"']nonce[\"']\s*:\s*[\"']([^\"']+)""").find(it)?.groupValues?.getOrNull(1) }
        val next = if (items.isNotEmpty() && ids.isNotEmpty() && !nonce.isNullOrBlank()) {
            cursorUrl(pageUrl, nonce, ids)
        } else null
        return LivePage(items, next)
    }

    private suspend fun loadMore(url: String, params: Map<String, String>): LivePage {
        val nonce = params[CURSOR_NONCE]?.takeIf { it.isNotBlank() } ?: return LivePage(emptyList())
        val displayed = params[CURSOR_IDS].orEmpty().split(',').filter { it.all(Char::isDigit) }
        if (displayed.isEmpty()) return LivePage(emptyList())
        val origin = originOf(url) ?: return LivePage(emptyList())
        val form = buildString {
            append("action=loadmore&nonce=").append(enc(nonce))
            displayed.forEach { append("&displayed_posts%5B%5D=").append(it) }
            append("&offset=").append(displayed.size)
        }
        val response = LiveClient.postBypass(
            url = "$origin/wp-admin/admin-ajax.php",
            form = form,
            referer = "$origin/",
            origin = origin,
            isValid = { body -> body.trim() == "0" || "animeseries" in body },
        ) ?: return LivePage(emptyList())
        if (response.trim() == "0") return LivePage(emptyList())

        val items = parseCards(response, "$origin/")
        val newIds = postIds(response)
        val allIds = (displayed + newIds).distinct()
        val next = if (items.isNotEmpty() && newIds.isNotEmpty()) cursorUrl("$origin/", nonce, allIds) else null
        return LivePage(items, next)
    }

    private fun parseCards(fragment: String, baseUrl: String): List<LiveItem> =
        LiveParser.parseList("<div>$fragment</div>", baseUrl)

    private fun postIds(htmlOrClasses: String): List<String> =
        Regex("""\bpost-(\d+)\b""").findAll(htmlOrClasses).map { it.groupValues[1] }.distinct().toList()

    private fun loadMoreConfig(src: String): String? {
        val encoded = src.substringAfter("base64,", "").takeIf { it.isNotBlank() } ?: return null
        return runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
    }

    private fun cursorUrl(pageUrl: String, nonce: String, ids: List<String>): String {
        val origin = originOf(pageUrl) ?: pageUrl.substringBefore('?').trimEnd('/')
        return "$origin/?$CURSOR_FLAG=1&$CURSOR_NONCE=${enc(nonce)}&$CURSOR_IDS=${ids.joinToString(",")}"
    }

    private fun queryParams(url: String): Map<String, String> = runCatching {
        URI(url).rawQuery.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        "${uri.scheme}://${uri.host}$port".takeIf { uri.scheme != null && uri.host != null }
    }.getOrNull()

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ---- Episode server resolver (the same site's separate player_ajax flow) ----

    /** Cheap signal that an episode page is kotakanime2-shaped. */
    fun isNontonAnimeID(html: String): Boolean = "kotak_player_option" in html

    private data class Tab(val name: String, val nume: String)

    suspend fun servers(html: String, episodeUrl: String): List<VideoServer> {
        val doc = Jsoup.parse(html)
        val tabs = doc.select("li.kotak_player_option")
        if (tabs.isEmpty()) return emptyList()

        val post = tabs.firstNotNullOfOrNull { it.attr("data-post").takeIf(String::isNotBlank) } ?: return emptyList()
        val nonce = findPlayerNonce(html) ?: return emptyList()
        val ajaxUrl = deriveAjaxUrl(episodeUrl) ?: return emptyList()
        val origin = originOf(episodeUrl) ?: return emptyList()
        val wanted = tabs.mapNotNull { tab ->
            val name = tab.attr("data-type").trim()
            val nume = tab.attr("data-nume").trim()
            if (name.isBlank() || nume.isBlank()) null else Tab(name, nume)
        }
        if (wanted.isEmpty()) return emptyList()

        val resolved = coroutineScope {
            wanted.map { tab ->
                async {
                    val form = "action=player_ajax&post=$post&nume=${tab.nume}&serverName=${tab.name}&nonce=$nonce"
                    tab to ajaxIframe(ajaxUrl, form, episodeUrl, origin)
                }
            }.awaitAll()
        }
        return resolved
            .mapNotNull { (tab, src) -> if (src != null && isSupportedEmbed(src)) VideoServer(tab.name, src) else null }
            .distinctBy { it.embedUrl }
            .sortedByDescending { preference(it.name, it.embedUrl) }
    }

    private suspend fun ajaxIframe(url: String, form: String, referer: String, origin: String): String? {
        val body = LiveClient.postBypass(url, form, referer, origin) { "src=" in it && "iframe" in it } ?: return null
        return iframeSrc(body)
    }

    private fun iframeSrc(html: String): String? {
        val raw = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.trim()
            ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> null
        }
    }

    private fun findPlayerNonce(html: String): String? {
        for (match in Regex("""data:text/javascript;base64,([A-Za-z0-9+/=]+)""").findAll(html)) {
            val js = runCatching { String(Base64.getMimeDecoder().decode(match.groupValues[1])) }.getOrNull() ?: continue
            Regex("""kotakajax\s*=[\s\S]*?"nonce":"(\w+)"""").find(js)?.let { return it.groupValues[1] }
        }
        return Regex("""kotakajax\s*=[\s\S]*?"nonce":"(\w+)"""").find(html)?.groupValues?.get(1)
    }

    private fun deriveAjaxUrl(episodeUrl: String): String? =
        originOf(episodeUrl)?.let { "$it/wp-admin/admin-ajax.php" }

    private fun isSupportedEmbed(url: String): Boolean {
        val normalized = url.lowercase()
        return "kotakanimeid.link/video-embed" in normalized || "ok.ru" in normalized || "odnoklassniki" in normalized
    }

    private fun preference(name: String, embed: String): Int {
        val hint = name.lowercase()
        val native = "kotakanimeid.link" in embed.lowercase()
        return when {
            native && ("1080" in hint || "uhd" in hint) -> 6
            native && "kotakvideo" in hint -> 5
            native && ("hd" in hint || "lokal" in hint) -> 4
            native -> 3
            else -> 1
        }
    }
}
