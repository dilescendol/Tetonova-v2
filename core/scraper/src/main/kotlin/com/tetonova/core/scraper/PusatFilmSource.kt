package com.tetonova.core.scraper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Base64

/**
 * PusatFilm (WordPress "muvipro" theme) routes every movie/episode's player through a single central
 * hub — `kotakajaib.me/embed/<id>` — rendered as the page's default `<iframe>`. That hub page holds the
 * real multi-server switcher as `button.server-item[data-frame]`, where each `data-frame` is a
 * Base64-encoded embed URL for the actual host (emturbovid / playhydrax / rapidplay(filelion) /
 * streamwish / streamtape / vectorx(xstream) / gdriveplayer). The site's own "Multi Server" button just
 * reveals that hub, so the source list is fully deterministic: two hops, no admin-ajax, no guessing —
 * which is why old titles never need to "search" for a source.
 *
 * We fetch the hub, decode each `data-frame`, and hand the hosts to [StreamExtractor] (rapidplay/vectorx
 * resolve to a direct stream; hydrax/gdriveplayer play via WebView). The `.server-name` label ("HYDRAX",
 * "TURBOVIP", …) becomes the player's "Source" pick. Best-effort throughout: any failure returns empty
 * and [LiveSource] falls back to the generic [LiveParser.parseServers] (which still finds the bare
 * kotakajaib iframe as a single WebView server).
 */
object PusatFilmSource {

    private val HUB = Regex("""https?://kotakajaib\.me/embed/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)

    /** Cheap signal (used by [LiveSource]) that a watch page routes its player through the kotakajaib hub. */
    fun isPusatFilm(html: String): Boolean = HUB.containsMatchIn(html)

    /** Two-hop: watch page → kotakajaib hub → decoded per-host server list. */
    suspend fun servers(html: String, pageUrl: String): List<VideoServer> {
        val hub = HUB.find(html)?.value ?: return emptyList()
        val hubHtml = LiveClient.getHtml(hub) { "server-item" in it || "server-name" in it } ?: return emptyList()
        return parseHub(hubHtml)
    }

    /** Parse the kotakajaib hub's `button.server-item[data-frame]` grid. Pure — unit-testable offline. */
    fun parseHub(hubHtml: String): List<VideoServer> {
        val doc = Jsoup.parse(hubHtml)
        return doc.select(".server-item[data-frame]")
            .mapNotNull { el ->
                val embed = decodeFrame(el.attr("data-frame")) ?: return@mapNotNull null
                val name = el.selectFirst(".server-name")?.text()?.trim()?.ifBlank { null }
                    ?: StreamExtractor.playableHostLabel(embed)
                VideoServer(name, embed)
            }
            .distinctBy { it.embedUrl }
            .sortedByDescending { rank(it.embedUrl) }
    }

    /**
     * Episode list for a `/tv/` series page. PusatFilm stacks every season into one `.gmr-listseries`
     * accordion where each episode is an `a.s-eps` linking to `/eps/{slug}-season-N-episode-M/`. The
     * flat [LiveEpisode] model has no season field and the app re-sorts by [LiveEpisode.num], so we
     * order by (season, episode) and assign a monotonic global `num` (1..N) — otherwise every season's
     * "episode 1" would collide and interleave. The real "S{n} E{m}" goes in the title so the season
     * stays visible; single-season shows keep natural "Episode N" numbering. Empty for non-`/tv/` pages.
     */
    fun episodes(doc: Document): List<LiveEpisode> {
        data class Ep(val season: Int, val ep: Int, val url: String)
        val raw = doc.select("a.s-eps[href]").mapNotNull { a ->
            val url = a.absUrl("href").ifBlank { return@mapNotNull null }
            val sm = Regex("""-season-(\d+)-episode-(\d+)""", RegexOption.IGNORE_CASE).find(url)
            if (sm != null) return@mapNotNull Ep(sm.groupValues[1].toInt(), sm.groupValues[2].toInt(), url)
            val ep = Regex("""-episode-(\d+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\d+""").find(a.text().trim())?.value?.toIntOrNull()
                ?: return@mapNotNull null
            Ep(1, ep, url)
        }.distinctBy { it.url }
        if (raw.isEmpty()) return emptyList()

        // Always carry season/epInSeason (even single-season) so a fresh parse is distinguishable from a
        // stale pre-accordion cache (see TnData.shouldRetryLiveDetail). The UI only switches to a season
        // picker when there's more than one season — single-season shows keep clean "Episode N" numbering.
        val multiSeason = raw.map { it.season }.distinct().size > 1
        return raw.sortedWith(compareBy({ it.season }, { it.ep })).mapIndexed { i, e ->
            if (multiSeason) LiveEpisode(i + 1, "S${e.season} E${e.ep}", e.url, season = e.season, epInSeason = e.ep)
            else LiveEpisode(e.ep, "Episode ${e.ep}", e.url, season = e.season, epInSeason = e.ep)
        }
    }

    /** `data-frame` is Base64 of the host embed URL; some are protocol-relative (`//host/…`). */
    private fun decodeFrame(raw: String): String? {
        val b64 = raw.trim().ifBlank { return null }
        val decoded = runCatching { String(Base64.getMimeDecoder().decode(b64)) }.getOrNull()?.trim() ?: return null
        return when {
            decoded.startsWith("http", ignoreCase = true) -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            else -> null
        }
    }

    /** Default-pick order: hosts [StreamExtractor] resolves to a direct ExoPlayer stream first, then
     *  Filemoon-family, then WebView-only players (hydrax / gdriveplayer) last — so the initial "Source"
     *  lands on the smoothest one. emturbovid is the one host verified on-device to yield a direct
     *  stream; rapidplay/vectorx currently fall through to the WebView sniff. */
    private fun rank(embed: String): Int {
        val u = embed.lowercase()
        return when {
            "emturbovid" in u || "turbovid" in u -> 5 // direct stream → ExoPlayer (verified on-device)
            "rapidplay" in u || "filelion" in u || "filemoon" in u -> 4 // Filemoon-family
            "vectorx" in u -> 3
            "streamwish" in u || "streamtape" in u -> 2
            else -> 1 // hydrax / gdriveplayer → WebView
        }
    }
}
