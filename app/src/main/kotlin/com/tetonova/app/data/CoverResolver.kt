package com.tetonova.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Anime metadata resolved from MyAnimeList (via Jikan) — used for covers + Detail enrichment. */
data class AnimeInfo(
    val malId: Int,
    val coverUrl: String?,
    val episodes: Int?,
    val status: String?,   // raw Jikan status ("Currently Airing" / "Finished Airing" / ...)
    val score: Double?,
    val year: Int?,
    val studios: List<String>,
    val genres: List<String>,
    val synopsis: String?,
)

data class CharacterInfo(val name: String, val imageUrl: String?, val role: String?)

/**
 * MyAnimeList client (public Jikan API, no key). Resolves anime info by title and characters by
 * MAL id. Everything is cached (hits + misses), de-duplicated per key, and throttled to stay under
 * Jikan's ~3 req/s. Failures and no-matches return null/empty so the UI keeps its fallbacks —
 * donghua titles simply won't be on MAL.
 */
object CoverResolver {

    private val infoCache = ConcurrentHashMap<String, AnimeInfo>()
    private val infoMiss = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val infoInflight = ConcurrentHashMap<String, Deferred<AnimeInfo?>>()
    private val charCache = ConcurrentHashMap<Int, List<CharacterInfo>>()
    private val charInflight = ConcurrentHashMap<Int, Deferred<List<CharacterInfo>>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var lastRequestAt = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Cover URL for a title (kept for the design system's CoverProvider hook). */
    suspend fun resolve(title: String): String? = info(title)?.coverUrl

    suspend fun info(title: String): AnimeInfo? {
        val base = normalize(title)
        if (base.isBlank()) return null
        val season = seasonOf(title)
        val key = "$base|s$season"   // season-aware: "...|s1" and "...|s4" cache (and resolve) separately
        infoCache[key]?.let { return it }
        if (infoMiss.contains(key)) return null
        val deferred = infoInflight.getOrPut(key) {
            scope.async {
                var r = fetchSeasonAware(base, season)
                // Retry without the subtitle ("Mashle: Magic and Muscles" -> "Mashle").
                if (r == null && base.contains(':')) r = fetchSeasonAware(base.substringBefore(':').trim(), season)
                if (r != null) infoCache[key] = r else infoMiss.add(key)
                infoInflight.remove(key)
                r
            }
        }
        return deferred.await()
    }

    /** Match the base (season-1) title, then walk MAL "Sequel" relations to the requested season so
     *  e.g. "...S4" resolves to the S4 entry (its own malId/metadata), not season 1. Best-effort: if
     *  the sequel chain ends early, the furthest season reached is used. */
    private suspend fun fetchSeasonAware(query: String, season: Int): AnimeInfo? {
        var info = fetchInfo(query) ?: return null
        var hops = season - 1
        while (hops > 0) {
            val nextId = fetchSequelId(info.malId) ?: break
            val next = fetchAnimeById(nextId) ?: break
            info = next
            hops--
        }
        return info
    }

    suspend fun characters(malId: Int): List<CharacterInfo> {
        if (malId <= 0) return emptyList()
        charCache[malId]?.let { return it }
        val deferred = charInflight.getOrPut(malId) {
            scope.async {
                val list = fetchCharacters(malId)
                charCache[malId] = list
                charInflight.remove(malId)
                list
            }
        }
        return deferred.await()
    }

    private suspend fun fetchInfo(query: String): AnimeInfo? = runCatching {
        throttle()
        withContext(Dispatchers.IO) {
            val url = "https://api.jikan.moe/v4/anime?limit=1&sfw=true&q=" + URLEncoder.encode(query, "UTF-8")
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@use null
                val data = JSONObject(body).optJSONArray("data")
                if (data == null || data.length() == 0) return@use null
                parseAnime(data.getJSONObject(0))
            }
        }
    }.getOrNull()

    /** First "Sequel" anime entry's MAL id for [malId] (the next season), or null. */
    private suspend fun fetchSequelId(malId: Int): Int? = runCatching {
        throttle()
        withContext(Dispatchers.IO) {
            val url = "https://api.jikan.moe/v4/anime/$malId/relations"
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@use null
                val data = JSONObject(body).optJSONArray("data") ?: return@use null
                for (i in 0 until data.length()) {
                    val rel = data.optJSONObject(i) ?: continue
                    if (!rel.optString("relation").equals("Sequel", true)) continue
                    val entries = rel.optJSONArray("entry") ?: continue
                    for (j in 0 until entries.length()) {
                        val e = entries.optJSONObject(j) ?: continue
                        if (e.optString("type").equals("anime", true)) {
                            return@use e.optInt("mal_id").takeIf { it > 0 }
                        }
                    }
                }
                null
            }
        }
    }.getOrNull()

    /** Full anime record by MAL id (the `/anime/{id}` data object parses like a search result). */
    private suspend fun fetchAnimeById(malId: Int): AnimeInfo? = runCatching {
        throttle()
        withContext(Dispatchers.IO) {
            val url = "https://api.jikan.moe/v4/anime/$malId"
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@use null
                val data = JSONObject(body).optJSONObject("data") ?: return@use null
                parseAnime(data)
            }
        }
    }.getOrNull()

    private suspend fun fetchCharacters(malId: Int): List<CharacterInfo> = runCatching {
        throttle()
        withContext(Dispatchers.IO) {
            val url = "https://api.jikan.moe/v4/anime/$malId/characters"
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@use emptyList()
                val data = JSONObject(body).optJSONArray("data") ?: return@use emptyList()
                (0 until data.length()).mapNotNull { i ->
                    val item = data.optJSONObject(i) ?: return@mapNotNull null
                    val ch = item.optJSONObject("character") ?: return@mapNotNull null
                    val name = ch.optString("name").ifBlank { return@mapNotNull null }
                    val img = ch.optJSONObject("images")?.optJSONObject("jpg")?.optString("image_url")?.takeIf { it.isNotBlank() }
                    CharacterInfo(name = name, imageUrl = img, role = item.optString("role").ifBlank { null })
                }.take(12)
            }
        }
    }.getOrDefault(emptyList())

    private fun parseAnime(o: JSONObject): AnimeInfo {
        val jpg = o.optJSONObject("images")?.optJSONObject("jpg")
        val cover = (jpg?.optString("large_image_url").orEmpty()
            .ifBlank { jpg?.optString("image_url").orEmpty() }).ifBlank { null }
        val year = o.optInt("year").takeIf { it > 0 }
            ?: o.optJSONObject("aired")?.optJSONObject("prop")?.optJSONObject("from")?.optInt("year")?.takeIf { it > 0 }
        return AnimeInfo(
            malId = o.optInt("mal_id"),
            coverUrl = cover,
            episodes = o.optInt("episodes").takeIf { it > 0 },
            status = o.optString("status").ifBlank { null },
            score = o.optDouble("score").takeIf { !it.isNaN() && it > 0 },
            year = year,
            studios = namesOf(o, "studios"),
            genres = namesOf(o, "genres"),
            synopsis = o.optString("synopsis").ifBlank { null },
        )
    }

    private fun namesOf(o: JSONObject, key: String): List<String> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
    }

    /** Serialize requests with a ≥350ms gap so we stay under Jikan's rate limit. */
    private suspend fun throttle() = mutex.withLock {
        val wait = 350 - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun normalize(title: String): String = title
        .replace(Regex("\\b\\d+(?:st|nd|rd|th)\\s+Season\\b", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\b(Season|S)\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\b(Episode|Ep)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[\\(\\[].*?[\\)\\]]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Season number from a title ("...S4" / "Season 4" / "4th Season"); default 1. */
    private fun seasonOf(title: String): Int {
        Regex("\\b(\\d+)(?:st|nd|rd|th)\\s+Season\\b", RegexOption.IGNORE_CASE).find(title)?.let {
            return it.groupValues[1].toIntOrNull() ?: 1
        }
        Regex("\\b(?:Season|S)\\s*(\\d+)\\b", RegexOption.IGNORE_CASE).find(title)?.let {
            return it.groupValues[1].toIntOrNull() ?: 1
        }
        return 1
    }
}
