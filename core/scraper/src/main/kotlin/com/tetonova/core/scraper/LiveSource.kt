package com.tetonova.core.scraper

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/**
 * High-level live-content fetch: HTML via [LiveClient], structure via [LiveParser]. Every call is
 * best-effort and returns empty/null on any failure so callers can fall back to the bundled seed.
 */
object LiveSource {

    /** Match displayed titles while tolerating common Indonesian me-/pe- noun/verb variants. */
    fun matchesSearchQuery(title: String, query: String): Boolean {
        fun words(value: String) = value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
        fun stem(word: String): String {
            var out = word
            listOf("meng", "peng").firstOrNull { out.startsWith(it) && out.length - it.length >= 4 }
                ?.let { out = out.removePrefix(it) }
            listOf("kan", "an").firstOrNull { out.endsWith(it) && out.length - it.length >= 4 }
                ?.let { out = out.removeSuffix(it) }
            return out
        }

        val titleWords = words(title)
        val queryWords = words(query)
        return queryWords.isNotEmpty() && queryWords.all { queryWord ->
            titleWords.any { titleWord ->
                queryWord in titleWord || titleWord in queryWord || stem(queryWord) == stem(titleWord)
            }
        }
    }

    fun configureAccessCodes(codesByBaseUrl: Map<String, String>) {
        LiveRuntimeConfig.setAccessCodes(codesByBaseUrl)
    }

    fun configurePremiumProxy(panelBase: String, bearer: String) {
        LiveRuntimeConfig.setPremiumProxy(panelBase, bearer)
    }

    /** True when a watch/detail URL belongs to a vertical short-drama source (portrait micro-episodes:
     *  DramaBox / FreeReels / FlexTv / ReelShort / GoodBos short-drama APIs). The player uses this to lock portrait orientation;
     *  the real video aspect ratio confirms/corrects it once the first frame's dimensions are known. */
    fun isShortDrama(url: String): Boolean =
        DramaBoxSource.isDramaBox(url) || FreeReelsSource.isFreeReels(url) ||
            FlexTvSource.isFlexTv(url) || ReelShortSource.isReelShort(url) ||
            BiliTvSource.isBiliTv(url) || DotDramaSource.isDotDrama(url) ||
            DramaWaveSource.isDramaWave(url) || DramaBiteSource.isDramaBite(url) ||
            MeloloSource.isMelolo(url) || FlickReelsSource.isFlickReels(url) ||
            GoodShortSource.isGoodShort(url) || FunDramaSource.isFunDrama(url) ||
            MicroDramaSource.isMicroDrama(url) || NetShortSource.isNetShort(url) ||
            ReelifeSource.isReelife(url) || IDramaSource.isIDrama(url) ||
            ShortMaxSource.isShortMax(url) || StardustTvSource.isStardustTv(url) ||
            VeloloSource.isVelolo(url) || HappyShortSource.isHappyShort(url) ||
            DramaNovaSource.isDramaNova(url) || CubeTvSource.isCubeTv(url)

    /** True when [baseUrl] has a dedicated `search()` endpoint (JSON API) — i.e. any host that
     *  [search] dispatches to *before* the generic WordPress `/?s=` fallback. Such sources search
     *  server-side, so their hits are already relevant and callers must NOT re-filter them by title
     *  (short-drama titles are often localized and won't literally contain the typed query). */
    fun hasNativeSearch(baseUrl: String): Boolean {
        val b = baseUrl.trim().trimEnd('/')
        return DramaWaveSource.isDramaWave(b) || DramaBiteSource.isDramaBite(b) ||
            BiliTvSource.isBiliTv(b) || DotDramaSource.isDotDrama(b) ||
            DramaBoxSource.isDramaBox(b) || FreeReelsSource.isFreeReels(b) ||
            FlexTvSource.isFlexTv(b) || ReelShortSource.isReelShort(b) ||
            MeloloSource.isMelolo(b) || FlickReelsSource.isFlickReels(b) ||
            GoodShortSource.isGoodShort(b) || FunDramaSource.isFunDrama(b) ||
            MicroDramaSource.isMicroDrama(b) || NetShortSource.isNetShort(b) ||
            ReelifeSource.isReelife(b) || IDramaSource.isIDrama(b) ||
            ShortMaxSource.isShortMax(b) || StardustTvSource.isStardustTv(b) ||
            VeloloSource.isVelolo(b) || HappyShortSource.isHappyShort(b) ||
            DramaNovaSource.isDramaNova(b) || CubeTvSource.isCubeTv(b) ||
            OploverzSource.isOploverz(b) || JavHeySource.isJavHey(b) || IndoMax21Source.isIndoMax21(b) ||
            isKuramanimeBase(b)
    }

    /** URL-only kuramanime check (its base host, e.g. `v19.kuramanime.ing`). */
    private fun isKuramanimeBase(url: String): Boolean =
        "kuramanime" in url.lowercase() || "kuramadrive" in url.lowercase()

    suspend fun list(url: String): List<LiveItem> {
        if (JavHeySource.isJavHey(url)) return runCatching { JavHeySource.listPage(url).items }.getOrDefault(emptyList())
        if (IndoMax21Source.isIndoMax21(url)) return runCatching { IndoMax21Source.listPage(url).items }.getOrDefault(emptyList())
        if (DutamovieSource.isDutamovie(url)) return runCatching { DutamovieSource.listPage(url).items }.getOrDefault(emptyList())
        if (IdlixSource.isIdlix(url)) return runCatching { IdlixSource.listPage(url).items }.getOrDefault(emptyList())
        if (MeloloSource.isMelolo(url)) return runCatching { MeloloSource.list(url) }.getOrDefault(emptyList())
        if (FlickReelsSource.isFlickReels(url)) return runCatching { FlickReelsSource.list(url) }.getOrDefault(emptyList())
        if (DramaWaveSource.isDramaWave(url)) return runCatching { DramaWaveSource.list(url) }.getOrDefault(emptyList())
        if (DramaBiteSource.isDramaBite(url)) return runCatching { DramaBiteSource.list(url) }.getOrDefault(emptyList())
        if (GoodShortSource.isGoodShort(url)) return runCatching { GoodShortSource.list(url) }.getOrDefault(emptyList())
        if (FunDramaSource.isFunDrama(url)) return runCatching { FunDramaSource.list(url) }.getOrDefault(emptyList())
        if (MicroDramaSource.isMicroDrama(url)) return runCatching { MicroDramaSource.list(url) }.getOrDefault(emptyList())
        if (NetShortSource.isNetShort(url)) return runCatching { NetShortSource.list(url) }.getOrDefault(emptyList())
        if (ReelifeSource.isReelife(url)) return runCatching { ReelifeSource.list(url) }.getOrDefault(emptyList())
        if (IDramaSource.isIDrama(url)) return runCatching { IDramaSource.list(url) }.getOrDefault(emptyList())
        if (ShortMaxSource.isShortMax(url)) return runCatching { ShortMaxSource.list(url) }.getOrDefault(emptyList())
        if (StardustTvSource.isStardustTv(url)) return runCatching { StardustTvSource.list(url) }.getOrDefault(emptyList())
        if (VeloloSource.isVelolo(url)) return runCatching { VeloloSource.list(url) }.getOrDefault(emptyList())
        if (HappyShortSource.isHappyShort(url)) return runCatching { HappyShortSource.list(url) }.getOrDefault(emptyList())
        if (DramaNovaSource.isDramaNova(url)) return runCatching { DramaNovaSource.list(url) }.getOrDefault(emptyList())
        if (CubeTvSource.isCubeTv(url)) return runCatching { CubeTvSource.list(url) }.getOrDefault(emptyList())
        if (BiliTvSource.isBiliTv(url)) return runCatching { BiliTvSource.list(url) }.getOrDefault(emptyList())
        if (DotDramaSource.isDotDrama(url)) return runCatching { DotDramaSource.list(url) }.getOrDefault(emptyList())
        if (DramaBoxSource.isDramaBox(url)) return runCatching { DramaBoxSource.list(url) }.getOrDefault(emptyList())
        if (FreeReelsSource.isFreeReels(url)) return runCatching { FreeReelsSource.list(url) }.getOrDefault(emptyList())
        if (FlexTvSource.isFlexTv(url)) return runCatching { FlexTvSource.list(url) }.getOrDefault(emptyList())
        if (ReelShortSource.isReelShort(url)) return runCatching { ReelShortSource.list(url) }.getOrDefault(emptyList())
        // Oploverz is a bespoke Next.js site the generic parser can't read — serve its "Rilis Terbaru"
        // rail from its JSON API instead (see [OploverzSource]).
        if (NontonAnimeIDSource.isCatalogUrl(url)) return runCatching { NontonAnimeIDSource.listPage(url).items }.getOrDefault(emptyList())
        if (OploverzSource.isOploverz(url)) return runCatching { OploverzSource.latest() }.getOrDefault(emptyList())
        val html = LiveClient.getHtml(url) ?: return emptyList()
        return normalizeAnoboyList(url, LiveParser.parseList(html, url))
    }

    suspend fun listPage(url: String): LivePage {
        if (JavHeySource.isJavHey(url)) return runCatching { JavHeySource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (IndoMax21Source.isIndoMax21(url)) return runCatching { IndoMax21Source.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (DutamovieSource.isDutamovie(url)) return runCatching { DutamovieSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (IdlixSource.isIdlix(url)) return runCatching { IdlixSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (MeloloSource.isMelolo(url)) return runCatching { MeloloSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (FlickReelsSource.isFlickReels(url)) return runCatching { FlickReelsSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (DramaWaveSource.isDramaWave(url)) return runCatching { DramaWaveSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (DramaBiteSource.isDramaBite(url)) return runCatching { DramaBiteSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (GoodShortSource.isGoodShort(url)) return runCatching { GoodShortSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (FunDramaSource.isFunDrama(url)) return runCatching { FunDramaSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (MicroDramaSource.isMicroDrama(url)) return runCatching { MicroDramaSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (NetShortSource.isNetShort(url)) return runCatching { NetShortSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (ReelifeSource.isReelife(url)) return runCatching { ReelifeSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (IDramaSource.isIDrama(url)) return runCatching { IDramaSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (ShortMaxSource.isShortMax(url)) return runCatching { ShortMaxSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (StardustTvSource.isStardustTv(url)) return runCatching { StardustTvSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (VeloloSource.isVelolo(url)) return runCatching { VeloloSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (HappyShortSource.isHappyShort(url)) return runCatching { HappyShortSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (DramaNovaSource.isDramaNova(url)) return runCatching { DramaNovaSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (CubeTvSource.isCubeTv(url)) return runCatching { CubeTvSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (BiliTvSource.isBiliTv(url)) return runCatching { BiliTvSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (DotDramaSource.isDotDrama(url)) return runCatching { DotDramaSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (DramaBoxSource.isDramaBox(url)) return runCatching { DramaBoxSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (FreeReelsSource.isFreeReels(url)) return runCatching { FreeReelsSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (FlexTvSource.isFlexTv(url)) return runCatching { FlexTvSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (ReelShortSource.isReelShort(url)) return runCatching { ReelShortSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (NontonAnimeIDSource.isCatalogUrl(url)) return runCatching { NontonAnimeIDSource.listPage(url) }.getOrDefault(LivePage(emptyList()))
        if (OploverzSource.isOploverz(url)) return runCatching { OploverzSource.latestPage(url) }.getOrDefault(LivePage(emptyList()))
        val html = LiveClient.getHtml(url) ?: return LivePage(emptyList())
        return LivePage(
            items = normalizeAnoboyList(url, LiveParser.parseList(html, url)),
            nextUrl = LiveParser.parseNextPage(html, url),
        )
    }

    /** Scrape the playable server/mirror list from an episode watch page (for the player's
     *  "Source video" picker). Empty on any failure. */
    suspend fun servers(url: String): List<VideoServer> {
        System.out.println("[LiveSource.servers] called for URL: $url")
        if (JavHeySource.isJavHey(url)) return runCatching { JavHeySource.servers(url) }.getOrDefault(emptyList())
        if (IndoMax21Source.isIndoMax21(url)) return runCatching { IndoMax21Source.servers(url) }.getOrDefault(emptyList())
        if (DutamovieSource.isDutamovie(url)) return runCatching { DutamovieSource.servers(url) }.getOrDefault(emptyList())
        if (IdlixSource.isIdlix(url)) return runCatching { IdlixSource.servers(url) }.getOrDefault(emptyList())
        if (MeloloSource.isMelolo(url)) return runCatching { MeloloSource.servers(url) }.getOrDefault(emptyList())
        if (FlickReelsSource.isFlickReels(url)) return runCatching { FlickReelsSource.servers(url) }.getOrDefault(emptyList())
        if (DramaWaveSource.isDramaWave(url)) return runCatching { DramaWaveSource.servers(url) }.getOrDefault(emptyList())
        if (DramaBiteSource.isDramaBite(url)) return runCatching { DramaBiteSource.servers(url) }.getOrDefault(emptyList())
        if (GoodShortSource.isGoodShort(url)) return runCatching { GoodShortSource.servers(url) }.getOrDefault(emptyList())
        if (FunDramaSource.isFunDrama(url)) return runCatching { FunDramaSource.servers(url) }.getOrDefault(emptyList())
        if (MicroDramaSource.isMicroDrama(url)) return runCatching { MicroDramaSource.servers(url) }.getOrDefault(emptyList())
        if (NetShortSource.isNetShort(url)) return runCatching { NetShortSource.servers(url) }.getOrDefault(emptyList())
        if (ReelifeSource.isReelife(url)) return runCatching { ReelifeSource.servers(url) }.getOrDefault(emptyList())
        if (IDramaSource.isIDrama(url)) return runCatching { IDramaSource.servers(url) }.getOrDefault(emptyList())
        if (ShortMaxSource.isShortMax(url)) return runCatching { ShortMaxSource.servers(url) }.getOrDefault(emptyList())
        if (StardustTvSource.isStardustTv(url)) return runCatching { StardustTvSource.servers(url) }.getOrDefault(emptyList())
        if (VeloloSource.isVelolo(url)) return runCatching { VeloloSource.servers(url) }.getOrDefault(emptyList())
        if (HappyShortSource.isHappyShort(url)) return runCatching { HappyShortSource.servers(url) }.getOrDefault(emptyList())
        if (DramaNovaSource.isDramaNova(url)) return runCatching { DramaNovaSource.servers(url) }.getOrDefault(emptyList())
        if (CubeTvSource.isCubeTv(url)) return runCatching { CubeTvSource.servers(url) }.getOrDefault(emptyList())
        if (BiliTvSource.isBiliTv(url)) return runCatching { BiliTvSource.servers(url) }.getOrDefault(emptyList())
        if (DotDramaSource.isDotDrama(url)) return runCatching { DotDramaSource.servers(url) }.getOrDefault(emptyList())
        if (DramaBoxSource.isDramaBox(url)) return runCatching { DramaBoxSource.servers(url) }.getOrDefault(emptyList())
        if (FreeReelsSource.isFreeReels(url)) return runCatching { FreeReelsSource.servers(url) }.getOrDefault(emptyList())
        if (FlexTvSource.isFlexTv(url)) return runCatching { FlexTvSource.servers(url) }.getOrDefault(emptyList())
        if (ReelShortSource.isReelShort(url)) return runCatching { ReelShortSource.servers(url) }.getOrDefault(emptyList())
        // Oploverz exposes an episode's watch embeds (filedon/dailymotion/4meplayer/blogger) via its
        // JSON API, keyed by series slug + episode number — no page scraping needed.
        if (OploverzSource.isOploverz(url)) return runCatching { OploverzSource.servers(url) }.getOrDefault(emptyList())
        // Kuramanime injects its player <source> tags via JS *after* the CF challenge clears, so the
        // WebView must keep polling until they appear (flare/byparr hand back the bare page too early).
        // Every other source just needs a non-challenge page.
        // Kuramanime: kuramadrive's <source> tags are JS-injected after a per-load token flow that the
        // host RATE-LIMITS — so do exactly ONE load here (a retry would just burn another token and make
        // the throttle worse). Quick re-opens may briefly come back empty; the NoSource screen offers a
        // manual retry, and 2b enumeration is lazy so it doesn't add competing kuramadrive loads.
        if ("kuramanime" in url.lowercase() || "kuramadrive" in url.lowercase()) {
            val h = LiveClient.getHtml(url) { KuramanimeSource.hasSources(it) }
            return if (h != null) KuramanimeSource.servers(h) else emptyList()
        }
        val html = LiveClient.getHtml(url) ?: return emptyList()
        // Anoboy batch/streaming episode (url = "<batch page>#tnep=N"): the whole series lives on one page
        // under per-server tabs, so resolve THIS episode's button from every tab into Blogger + yourupload
        // servers (yourupload plays in our ExoPlayer). Falls through when it's not actually a batch grid.
        Regex("#tnep=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?.let { epNum ->
            LiveParser.anoboyBatchServers(html, url, epNum).let { if (it.isNotEmpty()) return it }
        }
        if (LayarKaca21Source.isLayarKaca21(url)) {
            LayarKaca21Source.servers(html, url).let { if (it.isNotEmpty()) return it }
        }
        // Otakudesu hides its mirrors behind admin-ajax and groups them by resolution — resolve them
        // into host→[reso] servers. Falls through to the generic parser (which still finds the default
        // #pembed iframe) when otakudesu's AJAX flow yields nothing.
        if (OtakudesuSource.isOtakudesu(html)) {
            OtakudesuSource.servers(html, url).let { if (it.isNotEmpty()) return it }
        }
        // NontonAnimeID (kotakanime2) gates its mirrors behind a player_ajax POST that needs an Origin
        // header; resolve them into native-host servers our extractor can crack. Falls through to the
        // generic parser (which still finds the inline default iframe) when that yields nothing.
        if (NontonAnimeIDSource.isNontonAnimeID(html)) {
            NontonAnimeIDSource.servers(html, url).let { if (it.isNotEmpty()) return it }
        }
        // PusatFilm (muvipro) embeds a single kotakajaib.me/embed hub whose `button.server-item[data-frame]`
        // list holds the real per-host embeds (Base64). Expand it into the full server picker; falls
        // through to the generic parser (which still finds the bare kotakajaib iframe) when it yields nothing.
        if (PusatFilmSource.isPusatFilm(html)) {
            PusatFilmSource.servers(html, url).let { if (it.isNotEmpty()) return it }
        }
        if (NgefilmSource.isNgefilm(url)) {
            NgefilmSource.servers(html, url).let { if (it.isNotEmpty()) return it }
        }
        // Samehadaku: check if this is an index page and derive the watch URL
        if (url.contains("samehadaku", ignoreCase = true)) {
            // A `/anime/{slug}/` URL is ALWAYS a detail page (series OR movie) — its player options live on
            // a root-level watch page linked from its episode list, never inline here. The watch slug is
            // NOT the detail slug for movies (…/the-movie-4/ → /…-youre-next/), and a slug containing
            // "movie"/"episode" (e.g. "…-movie-2-…") used to fool the old suffix heuristic into scraping the
            // detail page directly (0 servers). So route every `/anime/` URL through the episode-list links.
            if (url.contains("/anime/")) {
                System.out.println("[LiveSource.servers] Samehadaku detail page detected: $url")
                val candidates = SamehadakuSource.episodeWatchLinks(html).ifEmpty {
                    SamehadakuSource.deriveWatchUrl(url)?.let { listOf(it) } ?: emptyList()
                }
                System.out.println("[LiveSource.servers] Watch link candidates: $candidates")
                for (watchUrl in candidates) {
                    val watchHtml = LiveClient.getHtml(watchUrl) ?: continue
                    if (SamehadakuSource.isSamehadaku(watchHtml, watchUrl)) {
                        val servers = SamehadakuSource.servers(watchHtml, watchUrl)
                        if (servers.isNotEmpty()) {
                            System.out.println("[LiveSource.servers] Found ${servers.size} servers from watch URL: $watchUrl")
                            return servers
                        }
                    }
                }
            } else if (SamehadakuSource.isSamehadaku(html, url)) {
                System.out.println("[LiveSource.servers] Detected Samehadaku watch page: $url")
                SamehadakuSource.servers(html, url).let { if (it.isNotEmpty()) return it }
            }
        }
        // Anoboy groups players per server (Btube/YUp/KrakenFiles/…) with resolution buttons; parse them
        // into the Source→Resolusi picker model instead of the flat generic list.
        if (isAnoboy(url)) {
            LiveParser.parseAnoboyServers(html, url).let { if (it.isNotEmpty()) return it }
        }
        System.out.println("[LiveSource.servers] Falling through to LiveParser.parseServers")
        return LiveParser.parseServers(html).let { servers ->
            if (isAnimeXin(url)) servers.animeXinIndoOnly() else servers
        }
    }

    private fun isAnimeXin(url: String): Boolean =
        runCatching { URI(url).host.orEmpty().lowercase().contains("animexin") }
            .getOrDefault(false) || "animexin" in url.lowercase()

    private fun List<VideoServer>.animeXinIndoOnly(): List<VideoServer> {
        val filtered = filter { it.name.contains("indo", ignoreCase = true) }
        return filtered.ifEmpty { this }
    }

    suspend fun detail(url: String): LiveDetail? {
        if (JavHeySource.isJavHey(url)) return runCatching { JavHeySource.detail(url) }.getOrNull()
        if (IndoMax21Source.isIndoMax21(url)) return runCatching { IndoMax21Source.detail(url) }.getOrNull()
        if (DutamovieSource.isDutamovie(url)) return runCatching { DutamovieSource.detail(url) }.getOrNull()
        if (IdlixSource.isIdlix(url)) return runCatching { IdlixSource.detail(url) }.getOrNull()
        if (MeloloSource.isMelolo(url)) return runCatching { MeloloSource.detail(url) }.getOrNull()
        if (FlickReelsSource.isFlickReels(url)) return runCatching { FlickReelsSource.detail(url) }.getOrNull()
        if (DramaWaveSource.isDramaWave(url)) return runCatching { DramaWaveSource.detail(url) }.getOrNull()
        if (DramaBiteSource.isDramaBite(url)) return runCatching { DramaBiteSource.detail(url) }.getOrNull()
        if (GoodShortSource.isGoodShort(url)) return runCatching { GoodShortSource.detail(url) }.getOrNull()
        if (FunDramaSource.isFunDrama(url)) return runCatching { FunDramaSource.detail(url) }.getOrNull()
        if (MicroDramaSource.isMicroDrama(url)) return runCatching { MicroDramaSource.detail(url) }.getOrNull()
        if (NetShortSource.isNetShort(url)) return runCatching { NetShortSource.detail(url) }.getOrNull()
        if (ReelifeSource.isReelife(url)) return runCatching { ReelifeSource.detail(url) }.getOrNull()
        if (IDramaSource.isIDrama(url)) return runCatching { IDramaSource.detail(url) }.getOrNull()
        if (ShortMaxSource.isShortMax(url)) return runCatching { ShortMaxSource.detail(url) }.getOrNull()
        if (StardustTvSource.isStardustTv(url)) return runCatching { StardustTvSource.detail(url) }.getOrNull()
        if (VeloloSource.isVelolo(url)) return runCatching { VeloloSource.detail(url) }.getOrNull()
        if (HappyShortSource.isHappyShort(url)) return runCatching { HappyShortSource.detail(url) }.getOrNull()
        if (DramaNovaSource.isDramaNova(url)) return runCatching { DramaNovaSource.detail(url) }.getOrNull()
        if (CubeTvSource.isCubeTv(url)) return runCatching { CubeTvSource.detail(url) }.getOrNull()
        if (BiliTvSource.isBiliTv(url)) return runCatching { BiliTvSource.detail(url) }.getOrNull()
        if (DotDramaSource.isDotDrama(url)) return runCatching { DotDramaSource.detail(url) }.getOrNull()
        if (DramaBoxSource.isDramaBox(url)) return runCatching { DramaBoxSource.detail(url) }.getOrNull()
        if (FreeReelsSource.isFreeReels(url)) return runCatching { FreeReelsSource.detail(url) }.getOrNull()
        if (FlexTvSource.isFlexTv(url)) return runCatching { FlexTvSource.detail(url) }.getOrNull()
        if (ReelShortSource.isReelShort(url)) return runCatching { ReelShortSource.detail(url) }.getOrNull()
        // Oploverz: series metadata + the full episode list come straight from its JSON API (the
        // synopsis/episodes the detail screen needs), addressed by slug — bypass the HTML parser.
        if (OploverzSource.isOploverz(url)) return runCatching { OploverzSource.detail(url) }.getOrNull()
        // A page is "usable" if it yielded a synopsis OR an episode list — NOT title, which many
        // themes (winbu/samehadaku/…) bury where our selectors miss it; the hero title comes from
        // the list card anyway (withLive keeps it when live.title is blank).
        val usable = { x: LiveDetail -> !x.synopsis.isNullOrBlank() || x.episodes.isNotEmpty() }

        // Kuramanime (and similar) host the full detail — synopsis + the COMPLETE episode list — on
        // the series/anime page; a trailing `/episode/N` is just a single-episode watch page that
        // shows only a slice. When handed such a watch URL, resolve to its series page first so we
        // pull every episode, not the few the watch page renders. Fall back to the watch URL below.
        val seriesFromPath = url.replace(Regex("/episode/\\d+/?$", RegexOption.IGNORE_CASE), "").takeIf { it != url }
        if (seriesFromPath != null) {
            LiveClient.getHtml(seriesFromPath)?.let { sHtml ->
                // Only short-circuit to the series page when it actually yielded the episode list —
                // its whole purpose. A synopsis-only page (episodes empty) must NOT win here, or the
                // UI would synthesize placeholder episodes instead of falling back to the watch page.
                parsePaged(seriesFromPath, sHtml).takeIf { it.episodes.isNotEmpty() }?.let { return it }
            }
        }

        val html = LiveClient.getHtml(url) ?: return null
        if (LayarKaca21Source.isLayarKaca21(url)) {
            return LayarKaca21Source.detail(html, url).takeIf(usable)
        }
        val d = parsePaged(url, html)
        // Old Anoboy title hubs expose separate [Download] and [Streaming] cards. Follow the
        // streaming post before returning the hub/download parse, otherwise Detail shows 1 episode.
        if (isAnoboy(url) && !url.contains("streaming", ignoreCase = true)) {
            anoboyStreamingUrl(html, url)?.let { streamUrl ->
                LiveClient.getHtml(streamUrl)?.let { sHtml ->
                    val sd = parsePaged(streamUrl, sHtml)
                    if (sd.episodes.size > d.episodes.size || usable(sd)) return sd
                }
            }
        }
        // Cards on episode-based sites (AnimeSail/Anoboy) land on an episode page whose synopsis + full
        // episode list live on the series page — follow the breadcrumb there. Gate it on being an ACTUAL
        // episode page (or a genuinely empty parse): a normal series/movie page also has a breadcrumb,
        // but its top crumb is a genre/category ("Action", "Anime-Movie") — following that overwrites the
        // real title + episode with junk (the reason a movie showed a phantom E2 and titles read "Action").
        val series = d.seriesUrl
        val onEpisodePage = Regex("-episode-\\d+|/episode/\\d+", RegexOption.IGNORE_CASE).containsMatchIn(url)
        val needsSeries = onEpisodePage || d.synopsis.isNullOrBlank() || d.episodes.isEmpty() ||
            (isNekopoi(url) && !url.contains("/hentai/", ignoreCase = true) && d.episodes.size <= 1)
        if (series != null && series != url && needsSeries) {
            LiveClient.getHtml(series)?.let { sHtml ->
                val sd = parsePaged(series, sHtml)
                if (usable(sd)) {
                    // The series grid can lag the newest episode the user actually arrived on — nekopoi
                    // lists it under a "preview-"/"new-release-" slug that isn't in the grid yet — so
                    // union the current page's episode(s) in (series URLs win on overlap) instead of
                    // replacing, so the latest episode never disappears after hydration.
                    val merged = (sd.episodes + d.episodes).distinctBy { it.num }.sortedBy { it.num }
                    val hydrated = if (merged.size > sd.episodes.size) sd.copy(episodes = merged) else sd
                    return hydrateNekopoiSearchEpisodes(hydrated)
                }
            }
        }
        return hydrateNekopoiSearchEpisodes(d).takeIf(usable)
    }

    private suspend fun hydrateNekopoiSearchEpisodes(detail: LiveDetail): LiveDetail {
        if (!isNekopoi(detail.url) || detail.episodes.size > 1) return detail
        val slug = nekopoiSeriesSlug(detail.url) ?: return detail
        val base = runCatching { URI(detail.url).let { "${it.scheme}://${it.host}" } }.getOrNull() ?: return detail
        val query = detail.title.ifBlank { slug.replace('-', ' ') }
        val searchUrl = "$base/search/${URLEncoder.encode(query, "UTF-8")}"
        val html = LiveClient.getHtml(searchUrl) ?: return detail
        val eps = Jsoup.parse(html, searchUrl).select("a[href*=-episode-]").mapNotNull { a ->
            val epUrl = a.absUrl("href").ifBlank { return@mapNotNull null }
            val epSlug = nekopoiSeriesSlug(epUrl) ?: return@mapNotNull null
            if (epSlug != slug) return@mapNotNull null
            val n = Regex("-episode-(\\d+)", RegexOption.IGNORE_CASE)
                .find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            LiveEpisode(n, "Episode $n", epUrl)
        }.distinctBy { it.num }.sortedBy { it.num }
        val generated = nekopoiContiguousEpisodes(detail, slug)
        if (eps.size <= detail.episodes.size && generated.size <= detail.episodes.size) return detail
        val merged = (eps + generated + detail.episodes).distinctBy { it.num }.sortedBy { it.num }
        return detail.copy(episodes = merged)
    }

    private fun nekopoiContiguousEpisodes(detail: LiveDetail, slug: String): List<LiveEpisode> {
        val latest = detail.episodes.maxByOrNull { it.num } ?: return emptyList()
        if (latest.num <= 1) return emptyList()
        val uri = runCatching { URI(latest.url) }.getOrNull() ?: return emptyList()
        val path = uri.path.orEmpty()
        val epSlug = path.trim('/').substringAfterLast('/')
        val suffix = Regex("-episode-\\d+(.*)$", RegexOption.IGNORE_CASE)
            .find(epSlug)?.groupValues?.getOrNull(1).orEmpty()
        val dir = path.substringBeforeLast('/', "")
        val prefix = "${uri.scheme}://${uri.host}${if (dir.isBlank()) "" else dir}/"
        return (1..latest.num).map { n ->
            LiveEpisode(n, "Episode $n", "$prefix$slug-episode-$n$suffix/")
        }
    }

    /**
     * Parse a detail page and complete kuramanime's paginated episode list (it shows only a
     * ~13-episode slice per `?page=N`).
     *
     * Primary path — extend, don't crawl: the "(Terbaru)" quick-pick gives the newest episode number
     * and kuramanime's episodes are contiguous with deterministic watch URLs (`{anime}/episode/{n}`),
     * so we fill the first page's grid up to that newest episode in ONE shot. Crawling every page of a
     * long donghua (e.g. Wushen Zhuzai, 654 eps ≈ 50 pages) is far too slow and leaves the UI sitting
     * on synthetic placeholders meanwhile. Falls back to following the pager (bounded) when there's no
     * "(Terbaru)" shortcut, and returns the page unchanged when it isn't paginated.
     */
    private suspend fun parsePaged(url: String, firstHtml: String): LiveDetail {
        val first = LiveParser.parseDetail(firstHtml, url)
        val gridMax = first.episodes.maxOfOrNull { it.num } ?: 0
        val gridMin = first.episodes.minOfOrNull { it.num } ?: 1
        val (oldest, latest) = LiveParser.episodeRange(firstHtml, url)

        // The Terlama/Terbaru quick-picks bound the WHOLE range no matter which slice we fetched, so
        // fill it in one shot. `oldest` guards the rare case where page 1 isn't the oldest slice.
        if (first.episodes.isNotEmpty() && latest != null && latest > gridMax) {
            val have = first.episodes.associateBy { it.num }
            val start = minOf(oldest ?: gridMin, gridMin)
            val base = first.episodes.first().url.replace(Regex("/episode/\\d+.*$", RegexOption.IGNORE_CASE), "")
            val top = minOf(latest, start + 3000) // sanity bound against a mislabeled "newest"
            val full = (start..top).map { n -> have[n] ?: LiveEpisode(n, "Episode $n", "$base/episode/$n") }
            return first.copy(episodes = full)
        }

        // Anoboy/WordPress LCP episode lists paginate via `?lcp_page0=N`, newest page first — so page 1
        // shows the latest slice and E1 lives on a later page. Follow the extra pages and merge so the
        // list starts at episode 1 (Tokusatsu: Gotchard/Zero-One showed E3+ only before this).
        val lcpPages = LiveParser.lcpPageNumbers(firstHtml).filter { it >= 2 }
        if (lcpPages.isNotEmpty()) {
            val episodes = LinkedHashMap<Int, LiveEpisode>()
            first.episodes.forEach { episodes[it.num] = it }
            for (p in lcpPages.sorted().take(10)) {
                val pageUrl = if ('?' in url) "$url&lcp_page0=$p" else "$url?lcp_page0=$p"
                val html = LiveClient.getHtml(pageUrl) ?: continue
                LiveParser.parseDetail(html, url).episodes.forEach { episodes.putIfAbsent(it.num, it) }
            }
            return first.copy(episodes = episodes.values.sortedBy { it.num })
        }

        val queue = ArrayDeque(LiveParser.episodePageNumbers(firstHtml, url).filter { it != pageOf(url) })
        if (queue.isEmpty()) return first
        val episodes = LinkedHashMap<Int, LiveEpisode>()
        first.episodes.forEach { episodes.putIfAbsent(it.num, it) }
        val visited = mutableSetOf(pageOf(url))
        var guard = 0
        while (queue.isNotEmpty() && guard++ < 12) {
            val p = queue.removeFirst()
            if (!visited.add(p)) continue
            val pageUrl = withPage(url, p)
            val html = LiveClient.getHtml(pageUrl) ?: continue
            LiveParser.parseDetail(html, pageUrl).episodes.forEach { episodes.putIfAbsent(it.num, it) }
            LiveParser.episodePageNumbers(html, pageUrl).forEach { if (it !in visited) queue.addLast(it) }
        }
        return first.copy(episodes = episodes.values.sortedBy { it.num })
    }

    /** Current `?page=` of a kuramanime episode-pager URL (default 1). */
    private fun pageOf(url: String): Int =
        Regex("[?&]page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** [url] with its `page` query set to [n], replacing any existing one. */
    private fun withPage(url: String, n: Int): String {
        val stripped = url.replace(Regex("([?&])page=\\d+&?"), "$1").trimEnd('?', '&')
        val sep = if (stripped.contains('?')) '&' else '?'
        return "$stripped${sep}page=$n"
    }

    /** WordPress site-search (`/?s=query`) against the source's base URL. */
    suspend fun search(baseUrl: String, query: String): List<LiveItem> {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank() || query.isBlank()) return emptyList()
        if (JavHeySource.isJavHey(base)) return runCatching { JavHeySource.search(base, query) }.getOrDefault(emptyList())
        if (IndoMax21Source.isIndoMax21(base)) return runCatching { IndoMax21Source.search(base, query) }.getOrDefault(emptyList())
        if (DutamovieSource.isDutamovie(base)) return runCatching { DutamovieSource.search(base, query) }.getOrDefault(emptyList())
        if (IdlixSource.isIdlix(base)) return runCatching { IdlixSource.search(base, query) }.getOrDefault(emptyList())
        if (MeloloSource.isMelolo(base)) return runCatching { MeloloSource.search(base, query) }.getOrDefault(emptyList())
        if (FlickReelsSource.isFlickReels(base)) return runCatching { FlickReelsSource.search(base, query) }.getOrDefault(emptyList())
        if (DramaWaveSource.isDramaWave(base)) return runCatching { DramaWaveSource.search(base, query) }.getOrDefault(emptyList())
        if (DramaBiteSource.isDramaBite(base)) return runCatching { DramaBiteSource.search(base, query) }.getOrDefault(emptyList())
        if (GoodShortSource.isGoodShort(base)) return runCatching { GoodShortSource.search(base, query) }.getOrDefault(emptyList())
        if (FunDramaSource.isFunDrama(base)) return runCatching { FunDramaSource.search(base, query) }.getOrDefault(emptyList())
        if (MicroDramaSource.isMicroDrama(base)) return runCatching { MicroDramaSource.search(base, query) }.getOrDefault(emptyList())
        if (NetShortSource.isNetShort(base)) return runCatching { NetShortSource.search(base, query) }.getOrDefault(emptyList())
        if (ReelifeSource.isReelife(base)) return runCatching { ReelifeSource.search(base, query) }.getOrDefault(emptyList())
        if (IDramaSource.isIDrama(base)) return runCatching { IDramaSource.search(base, query) }.getOrDefault(emptyList())
        if (ShortMaxSource.isShortMax(base)) return runCatching { ShortMaxSource.search(base, query) }.getOrDefault(emptyList())
        if (StardustTvSource.isStardustTv(base)) return runCatching { StardustTvSource.search(base, query) }.getOrDefault(emptyList())
        if (VeloloSource.isVelolo(base)) return runCatching { VeloloSource.search(base, query) }.getOrDefault(emptyList())
        if (HappyShortSource.isHappyShort(base)) return runCatching { HappyShortSource.search(base, query) }.getOrDefault(emptyList())
        if (DramaNovaSource.isDramaNova(base)) return runCatching { DramaNovaSource.search(base, query) }.getOrDefault(emptyList())
        if (CubeTvSource.isCubeTv(base)) return runCatching { CubeTvSource.search(base, query) }.getOrDefault(emptyList())
        if (BiliTvSource.isBiliTv(base)) return runCatching { BiliTvSource.search(base, query) }.getOrDefault(emptyList())
        if (DotDramaSource.isDotDrama(base)) return runCatching { DotDramaSource.search(base, query) }.getOrDefault(emptyList())
        if (DramaBoxSource.isDramaBox(base)) return runCatching { DramaBoxSource.search(base, query) }.getOrDefault(emptyList())
        if (FreeReelsSource.isFreeReels(base)) return runCatching { FreeReelsSource.search(base, query) }.getOrDefault(emptyList())
        if (FlexTvSource.isFlexTv(base)) return runCatching { FlexTvSource.search(base, query) }.getOrDefault(emptyList())
        if (ReelShortSource.isReelShort(base)) return runCatching { ReelShortSource.search(base, query) }.getOrDefault(emptyList())
        // Oploverz search runs against its JSON API (`/api/series?q=`), not WordPress `/?s=`.
        if (OploverzSource.isOploverz(base)) return runCatching { OploverzSource.search(query) }.getOrDefault(emptyList())
        // Kuramanime is a Laravel app, not WordPress — its search lives at `/anime?search=` (the generic
        // `/?s=` just returns the homepage). Cards are server-rendered `.product__item`, so the generic
        // parser reads them directly once CF is solved by getHtml.
        if (isKuramanimeBase(base)) {
            val url = "$base/anime?search=" + URLEncoder.encode(query, "UTF-8") + "&order_by=oldest"
            val html = LiveClient.getHtml(url) ?: return emptyList()
            return LiveParser.parseList(html, url)
        }
        // Otakudesu's bare `/?s=` mixes in episode-posts + pages (only 1 series card survives); its
        // canonical search scopes to the anime CPT (`&post_type=anime`) and returns the full clean
        // `.chivsrc > li` series grid the site's own UI shows.
        val url = if ("otakudesu" in base.lowercase()) {
            "$base/?s=" + URLEncoder.encode(query, "UTF-8") + "&post_type=anime"
        } else {
            "$base/?s=" + URLEncoder.encode(query, "UTF-8")
        }
        val html = LiveClient.getHtml(url) ?: return emptyList()
        return normalizeNekopoiList(base, normalizeAnoboyList(base, LiveParser.parseList(html, url)))
    }

    /**
     * Collapse nekopoi search results to ONE card per series. The site returns both a clean
     * `/hentai/{slug}/` series card and several per-episode cards for the same title, so a search for
     * "Enjo Kouhai" yields ~8 rows for one show and `resolveLiveUrl` lands on whichever episode came
     * first. We keep the LATEST episode card as the representative URL — [detail]'s series hydration
     * derives the series page from it AND merges that newest episode in, which the series card alone
     * can't (its grid lags the newest "preview-"/"new-release-" episode) — then borrow the series
     * card's clean title/cover for display. Single videos (JAV/3D/L2D — no `/hentai/` series and no
     * `-episode-N`) are left untouched.
     */
    private fun normalizeNekopoiList(baseUrl: String, items: List<LiveItem>): List<LiveItem> {
        if (!isNekopoi(baseUrl) || items.size < 2) return items
        fun pathOf(url: String) = runCatching { java.net.URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        fun isSeriesCard(url: String) = Regex("^/hentai/[^/?#]+/?$").containsMatchIn(pathOf(url))
        fun episodeNum(url: String) = Regex("-episode-(\\d+)").find(pathOf(url))?.groupValues?.get(1)?.toIntOrNull() ?: -1
        fun seriesSlugOf(url: String): String? {
            val path = pathOf(url)
            Regex("^/hentai/([^/?#]+)/?$").find(path)?.let { return it.groupValues[1] }
            val slug = path.trim('/').substringAfterLast('/')
            if (!slug.contains("-episode-")) return null // single video, not a series episode
            return slug.replace(Regex("-episode-\\d+.*$"), "")
                .replace(Regex("^(?:preview|new-release|uncensored|premium|batch)-"), "")
                .takeIf { it.isNotBlank() }
        }
        val rep = LinkedHashMap<String, LiveItem>()  // key -> representative card, first-seen order
        val seriesCard = HashMap<String, LiveItem>() // key -> the clean /hentai/ card, when present
        for (item in items) {
            val slug = seriesSlugOf(item.url)
            val key = slug ?: "single:${item.url}" // singles never merge
            if (slug != null && isSeriesCard(item.url)) seriesCard[key] = item
            val existing = rep[key]
            // Prefer a real episode as the representative URL, and among episodes the newest (highest N).
            if (existing == null || episodeNum(item.url) > episodeNum(existing.url)) rep[key] = item
        }
        return rep.map { (key, chosen) ->
            val card = seriesCard[key] ?: return@map chosen
            if (isSeriesCard(chosen.url)) chosen // only the series card existed — keep it as-is
            else chosen.copy( // episode URL for full hydration, but the series card's clean identity
                title = card.title.ifBlank { chosen.title },
                cover = card.cover?.takeIf { it.isNotBlank() } ?: chosen.cover,
            )
        }
    }

    private fun normalizeAnoboyList(baseUrl: String, items: List<LiveItem>): List<LiveItem> {
        if (!isAnoboy(baseUrl) || items.size < 2) return items
        val out = LinkedHashMap<String, LiveItem>()
        items.sortedByDescending(::anoboyStreamingRank).forEach { item ->
            val key = anoboyTitleKey(item.title).ifBlank { item.url }
            out.putIfAbsent(key, item.copy(title = cleanAnoboyTitle(item.title)))
        }
        return out.values.toList()
    }

    private fun anoboyStreamingRank(item: LiveItem): Int {
        val hay = "${item.title} ${item.url}".lowercase()
        return when {
            "streaming" in hay -> 2
            "download" in hay -> 0
            else -> 1
        }
    }

    private fun cleanAnoboyTitle(title: String): String = title
        .replace(Regex("\\[(streaming|download)\\]", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\bUP\\b.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { title.trim() }

    private fun anoboyTitleKey(title: String): String = cleanAnoboyTitle(title)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun anoboyStreamingUrl(html: String, baseUrl: String): String? {
        val current = baseUrl.trimEnd('/')
        return Jsoup.parse(html, baseUrl).select("a[href]").firstNotNullOfOrNull { a ->
            val href = a.absUrl("href").trim().trimEnd('/')
            val hay = "${a.text()} $href".lowercase()
            href.takeIf {
                it.startsWith("http") &&
                    it != current &&
                    "streaming" in hay &&
                    "download" !in hay
            }
        }
    }

    private fun isAnoboy(url: String): Boolean = "anoboy" in url.lowercase()

    private fun isNekopoi(url: String): Boolean = "nekopoi" in url.lowercase()

    private fun nekopoiSeriesSlug(url: String): String? {
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        Regex("^/hentai/([^/?#]+)/?$").find(path)?.let { return it.groupValues[1] }
        val slug = path.trim('/').substringAfterLast('/')
        if (!slug.contains("-episode-")) return null
        return slug.replace(Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(?:preview|new-release|uncensored|premium|batch)-", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() }
    }
}
