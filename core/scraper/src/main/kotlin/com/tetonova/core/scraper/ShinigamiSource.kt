package com.tetonova.core.scraper

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Manga models — image-based content (the reader shows pages, NOT a video player), so these are a
 * separate shape from [LiveItem]/[LiveDetail]/[LiveEpisode]. A "chapter" carries a list of page image
 * URLs; there is no server/embed/stream layer.
 */
data class MangaCard(val id: String, val title: String, val cover: String?, val latestChapter: String?)

data class MangaChapter(val id: String, val number: Double, val title: String)

data class MangaInfo(
    val id: String,
    val title: String,
    val altTitle: String?,
    val cover: String?,
    val synopsis: String?,
    val status: String?,
    val year: String?,
    val genres: List<String>,
    /** Newest-first (as the API returns them). */
    val chapters: List<MangaChapter>,
)

/** One chapter resolved for reading: ordered page image URLs + neighbour ids for prev/next. */
data class ReaderChapter(val number: String, val pages: List<String>, val prevId: String?, val nextId: String?)

/** A slice of catalog/search results + the next page to request (null = last page). Powers load-more. */
data class MangaPage(val cards: List<MangaCard>, val nextPage: Int?)

/**
 * Shinigami (g.shinigami.asia) — Indonesian manga scanlation. Backed by a clean, public, header-less
 * JSON API at api.shngm.io, so the whole flow is direct JSON — NO HTML scraping, no Cloudflare, no
 * auth. Page images are plain files on assets.shngm.id with no referer/token/signature gate:
 *
 *   GET /v1/manga/list?page=N&page_size=&q={query}  → catalog / search (one card per manga)
 *   GET /v1/manga/detail/{manga_id}                 → series metadata (+ genres in taxonomy.Genre)
 *   GET /v1/chapter/{manga_id}/list?page_size=      → chapter list (one big page_size grabs all)
 *   GET /v1/chapter/detail/{chapter_id}             → page image filenames + prev/next chapter
 *
 * Page URL = data.base_url + data.chapter.path + each filename in data.chapter.data[].
 * Everything is best-effort: any failure returns empty/null so callers show an error/empty state.
 */
object ShinigamiSource {

    private const val API = "https://api.shngm.io/v1"

    fun isShinigami(url: String): Boolean = "shinigami" in url.lowercase() || "shngm" in url.lowercase()

    /**
     * The merged "Update" catalog: the site's two tabs — **Project** (Shinigami's own scanlations) and
     * **Mirror** (other groups) — combined into one list, project first. Each tab is a separate paginated
     * feed (`type=project|mirror`, project ≈ 26 pages, mirror ≈ 167), so page N pulls page N of BOTH in
     * parallel and concatenates. [MangaPage.nextPage] is non-null until both feeds are exhausted, driving
     * the UI's load-more.
     */
    suspend fun catalogPage(page: Int = 1): MangaPage = coroutineScope {
        val proj = async { updateFeed("project", page) }
        val mir = async { updateFeed("mirror", page) }
        val (pc, pt) = proj.await()
        val (mc, mt) = mir.await()
        MangaPage(
            cards = (pc + mc).distinctBy { it.id },
            nextPage = (page + 1).takeIf { page < maxOf(pt, mt) },
        )
    }

    /** Search spans everything (no project/mirror split) via the WordPress-less `q=` filter; paginated. */
    suspend fun searchPage(query: String, page: Int = 1): MangaPage {
        if (query.isBlank()) return MangaPage(emptyList(), null)
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$API/manga/list?page=$page&page_size=30&q=$q") ?: return MangaPage(emptyList(), null)
        val cards = json.optJSONArray("data").objects().mapNotNull(::cardOf)
        val total = json.optJSONObject("meta")?.optInt("total_page", page) ?: page
        return MangaPage(cards, (page + 1).takeIf { page < total })
    }

    /** One "Update" feed (`type=project|mirror`), newest-first; returns its cards + total_page (for the
     *  load-more stop condition). On failure returns `[] to page` so the other feed still governs paging. */
    private suspend fun updateFeed(type: String, page: Int): Pair<List<MangaCard>, Int> {
        val json = getJson("$API/manga/list?type=$type&page=$page&page_size=30&is_update=true&sort=latest&sort_order=desc")
            ?: return emptyList<MangaCard>() to page
        val cards = json.optJSONArray("data").objects().mapNotNull(::cardOf)
        val total = json.optJSONObject("meta")?.optInt("total_page", page) ?: page
        return cards to total
    }

    suspend fun detail(mangaId: String): MangaInfo? {
        val d = getJson("$API/manga/detail/$mangaId")?.optJSONObject("data") ?: return null
        val title = d.str("title") ?: return null
        val genres = d.optJSONObject("taxonomy")?.optJSONArray("Genre").objects().mapNotNull { it.str("name") }
        // The chapter list is a separate call; one large page_size returns every chapter in a single
        // request (159 chapters → total_page 1), so no pagination loop is needed.
        val chapters = getJson("$API/chapter/$mangaId/list?page=1&page_size=3000")
            ?.optJSONArray("data").objects().mapNotNull(::chapterOf)
        return MangaInfo(
            id = mangaId,
            title = title,
            altTitle = d.str("alternative_title"),
            cover = d.str("cover_portrait_url") ?: d.str("cover_image_url"),
            synopsis = d.str("description"),
            status = statusLabel(d.optInt("status", 0)),
            year = d.str("release_year"),
            genres = genres,
            chapters = chapters,
        )
    }

    suspend fun pages(chapterId: String): ReaderChapter? {
        val data = getJson("$API/chapter/detail/$chapterId")?.optJSONObject("data") ?: return null
        return readerChapterOf(data)
    }

    // ---- mappers (internal so the parse test can exercise them without hitting the network) ----

    internal fun cardOf(o: JSONObject): MangaCard? {
        val id = o.str("manga_id") ?: return null
        val title = o.str("title") ?: return null
        val latest = o.opt("latest_chapter_number")?.let { fmtNum(o.optDouble("latest_chapter_number")) }
        return MangaCard(
            id = id,
            title = title,
            cover = o.str("cover_portrait_url") ?: o.str("cover_image_url"),
            latestChapter = latest?.let { "Ch. $it" },
        )
    }

    internal fun chapterOf(o: JSONObject): MangaChapter? {
        val id = o.str("chapter_id") ?: return null
        val num = o.optDouble("chapter_number", Double.NaN)
        if (num.isNaN()) return null
        return MangaChapter(id = id, number = num, title = o.str("chapter_title").orEmpty())
    }

    /** Build absolute page URLs from a `/chapter/detail` `data` node (base_url + chapter.path + files). */
    internal fun readerChapterOf(d: JSONObject): ReaderChapter? {
        val ch = d.optJSONObject("chapter") ?: return null
        val base = d.str("base_url")?.trimEnd('/') ?: return null
        val path = ch.str("path").orEmpty()
        val files = ch.optJSONArray("data") ?: return null
        val pages = (0 until files.length())
            .mapNotNull { files.optString(it).takeIf { f -> f.isNotBlank() } }
            .map { "$base$path$it" }
        if (pages.isEmpty()) return null
        return ReaderChapter(
            number = fmtNum(d.optDouble("chapter_number")),
            pages = pages,
            prevId = d.str("prev_chapter_id"),
            nextId = d.str("next_chapter_id"),
        )
    }

    // ponytail: status int → label is a best-guess mapping (1=ongoing, 2=completed per the common
    // convention); unknown codes just show no status. Correct the labels if the site proves otherwise.
    private fun statusLabel(s: Int): String? = when (s) {
        1 -> "Berlangsung"
        2 -> "Tamat"
        else -> null
    }

    /** Drop a trailing ".0" so whole chapter numbers read "158", decimals stay "158.5". */
    fun fmtNum(n: Double): String =
        if (n.isNaN()) "" else if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()

    private suspend fun getJson(url: String): JSONObject? {
        // A JSON body is ready the moment it parses — skip [LiveClient]'s challenge tiers and parse
        // defensively so an HTML error page just yields null (same pattern as [OploverzSource]).
        val body = LiveClient.getHtml(url) { it.trimStart().startsWith("{") } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).trim().ifBlank { null }
}
