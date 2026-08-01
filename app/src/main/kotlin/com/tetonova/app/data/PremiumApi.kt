package com.tetonova.app.data

import android.util.Log
import com.tetonova.core.scraper.LiveDetail
import com.tetonova.core.scraper.LiveEpisode
import com.tetonova.core.scraper.LiveItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** A premium content URL the app routes back through the proxy (not a scrapeable web page). Shape:
 *  `tnpremium://<platform>/detail?bookId=<id>`. Detected by [TnData] to call the proxy for detail. */
const val PREMIUM_SCHEME = "tnpremium"

/**
 * Client for the panel's authed DramaBos premium proxy (`GET /api/v1/me/premium/{platform}/{endpoint}`).
 * Auth is a Firebase ID token as `Authorization: Bearer <idToken>` (from [AuthManager]); the panel holds
 * the DramaBos access code server-side, so it is never shipped in the app. A `402` (not entitled) or any
 * error degrades to empty/null so the UI quietly shows nothing rather than crashing.
 *
 * The proxy returns DramaBos's RAW JSON; this client maps it to the app's [LiveItem]/[LiveDetail]. The
 * exact upstream field names below are best-effort against the documented `{recommendList:{records:[…]}}`
 * shape (only `bookId` is confirmed by the public docs) — adjust [parseRecords]/[parseDetail] once a real
 * response sample is captured with a valid access code.
 */
class PremiumApi(baseUrl: String) {

    private val base = baseUrl.trim().trimEnd('/')
    private val client = TnHttp.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** A catalog/home/latest list for [platform]. [endpoint] ∈ homepage/latest/foryou/dubbed. */
    suspend fun list(idToken: String, platform: String, endpoint: String = "homepage", page: Int = 1, lang: String = "in"): List<LiveItem> =
        withContext(Dispatchers.IO) {
            val json = getJson(idToken, platform, endpoint, mapOf("page" to page.toString(), "lang" to lang))
            json?.let { parseRecords(it, platform) } ?: emptyList()
        }

    /** Search [platform] for [query]. */
    suspend fun search(idToken: String, platform: String, query: String, lang: String = "in"): List<LiveItem> =
        withContext(Dispatchers.IO) {
            val json = getJson(idToken, platform, "search", mapOf("query" to query, "lang" to lang))
            json?.let { parseRecords(it, platform) } ?: emptyList()
        }

    /** Detail + episode list for a premium item identified by [bookId]. */
    suspend fun detail(idToken: String, platform: String, bookId: String, lang: String = "in"): LiveDetail? =
        withContext(Dispatchers.IO) {
            val d = getJson(idToken, platform, "detail", mapOf("bookId" to bookId, "lang" to lang)) ?: return@withContext null
            val epsJson = getJson(idToken, platform, "allepisode", mapOf("bookId" to bookId, "lang" to lang))
            parseDetail(d, epsJson, platform, bookId)
        }

    private fun getJson(idToken: String, platform: String, endpoint: String, query: Map<String, String>): JSONObject? {
        if (base.isEmpty() || idToken.isBlank()) return null
        return runCatching {
            val qs = query.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val url = "$base/api/v1/me/premium/$platform/$endpoint" + if (qs.isNotEmpty()) "?$qs" else ""
            val req = Request.Builder().url(url).header("Authorization", "Bearer $idToken").get().build()
            client.newCall(req).execute().use { resp ->
                // 402 = not entitled (the real server gate); treat as "no content" for the UI.
                if (resp.code == 402 || !resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) null else JSONObject(body)
            }
        }.onFailure { Log.w("TnPremium", "getJson $platform/$endpoint failed: ${it.message}") }.getOrNull()
    }

    /** Map a DramaBos list response → [LiveItem]s. Items carry a `tnpremium://` url so detail re-routes
     *  through the proxy. Tries the documented `recommendList.records` plus common fallbacks. */
    private fun parseRecords(json: JSONObject, platform: String): List<LiveItem> {
        val records: JSONArray = json.optJSONObject("recommendList")?.optJSONArray("records")
            ?: json.optJSONArray("records")
            ?: json.optJSONObject("data")?.optJSONArray("records")
            ?: json.optJSONArray("list")
            ?: json.optJSONArray("data")
            ?: return emptyList()
        val out = ArrayList<LiveItem>(records.length())
        for (i in 0 until records.length()) {
            val r = records.optJSONObject(i) ?: continue
            val bookId = firstNonBlank(r, "bookId", "id", "book_id") ?: continue
            val title = firstNonBlank(r, "bookName", "title", "name", "book_name") ?: "Drama $bookId"
            val cover = firstNonBlank(r, "cover", "coverWap", "coverUrl", "image", "pic")
            out += LiveItem(
                title = title,
                url = "$PREMIUM_SCHEME://$platform/detail?bookId=${URLEncoder.encode(bookId, "UTF-8")}",
                cover = cover,
                type = "Drama",
                status = firstNonBlank(r, "statusName", "status"),
            )
        }
        return out
    }

    private fun parseDetail(d: JSONObject, eps: JSONObject?, platform: String, bookId: String): LiveDetail {
        // The detail body may wrap the book under "data"/"book"/"detail" or be flat.
        val book = d.optJSONObject("data") ?: d.optJSONObject("book") ?: d.optJSONObject("detail") ?: d
        val title = firstNonBlank(book, "bookName", "title", "name") ?: "Drama $bookId"
        val cover = firstNonBlank(book, "cover", "coverWap", "coverUrl", "image")
        val synopsis = firstNonBlank(book, "introduction", "synopsis", "description", "desc")
        val episodes = parseEpisodes(eps ?: d, platform, bookId)
        return LiveDetail(
            title = title,
            cover = cover,
            synopsis = synopsis,
            status = firstNonBlank(book, "statusName", "status"),
            type = "Drama",
            studio = null,
            released = firstNonBlank(book, "year", "releaseTime"),
            genres = parseGenres(book),
            episodes = episodes,
            url = "$PREMIUM_SCHEME://$platform/detail?bookId=${URLEncoder.encode(bookId, "UTF-8")}",
        )
    }

    private fun parseEpisodes(json: JSONObject, platform: String, bookId: String): List<LiveEpisode> {
        val arr: JSONArray = json.optJSONObject("data")?.optJSONArray("chapterList")
            ?: json.optJSONArray("chapterList")
            ?: json.optJSONArray("episodeList")
            ?: json.optJSONArray("episodes")
            ?: json.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<LiveEpisode>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val num = firstNonBlank(e, "chapterIndex", "index", "episode", "num")?.toIntOrNull() ?: (i + 1)
            // A direct stream URL when present; else a tnpremium:// marker the player resolves via proxy.
            val stream = firstNonBlank(e, "videoUrl", "url", "playUrl", "stream")
                ?: "$PREMIUM_SCHEME://$platform/episode?bookId=${URLEncoder.encode(bookId, "UTF-8")}&ep=$num"
            out += LiveEpisode(
                num = num,
                title = firstNonBlank(e, "chapterName", "title", "name") ?: "Episode $num",
                url = stream,
                thumb = firstNonBlank(e, "cover", "thumb", "image"),
            )
        }
        return out
    }

    private fun parseGenres(book: JSONObject): List<String> {
        val arr = book.optJSONArray("tags") ?: book.optJSONArray("genres") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }
}

/** Parsed `tnpremium://<platform>/detail?bookId=<id>` → (platform, bookId), or null if not a premium url. */
fun parsePremiumUrl(url: String?): Pair<String, String>? {
    if (url == null || !url.startsWith("$PREMIUM_SCHEME://")) return null
    return runCatching {
        val uri = java.net.URI(url)
        val platform = uri.host ?: return null
        val bookId = uri.query?.split('&')
            ?.firstOrNull { it.startsWith("bookId=") }
            ?.substringAfter('=')
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: return null
        platform to bookId
    }.getOrNull()
}
