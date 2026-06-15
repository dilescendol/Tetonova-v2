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
        val key = normalize(title)
        if (key.isBlank()) return null
        infoCache[key]?.let { return it }
        if (infoMiss.contains(key)) return null
        val deferred = infoInflight.getOrPut(key) {
            scope.async {
                var r = fetchInfo(key)
                // Retry without the subtitle ("Mashle: Magic and Muscles" -> "Mashle").
                if (r == null && key.contains(':')) r = fetchInfo(key.substringBefore(':').trim())
                if (r != null) infoCache[key] = r else infoMiss.add(key)
                infoInflight.remove(key)
                r
            }
        }
        return deferred.await()
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
        .replace(Regex("\\b(Season|S)\\s*\\d+\\b", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\b(Episode|Ep)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[\\(\\[].*?[\\)\\]]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
