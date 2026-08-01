package com.tetonova.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Movie/series facts from OMDb (IMDB data) — used for Movie & Drama detail (the MAL of non-anime). */
data class MovieInfo(
    val plot: String?,
    val year: String?,
    val rating: String?,
    val genres: List<String>,
    val poster: String?,
    val director: String?,
    val actors: List<String>,
    val type: String?,
)

/**
 * OMDb (omdbapi.com) lookup by title — the movie/drama counterpart to [CoverResolver]/MAL. Best
 * effort: a blank key, a miss, or any error yields null so detail just falls back to the web/seed.
 * Results are cached per title; off the main thread.
 */
object OmdbResolver {

    @Volatile var apiKey: String = ""

    private val client = TnHttp.client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, MovieInfo>()
    private val missed = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun info(rawTitle: String): MovieInfo? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val title = clean(rawTitle)
        if (title.length < 2) return@withContext null
        cache[title]?.let { return@withContext it }
        if (title in missed) return@withContext null

        val result = runCatching {
            val url = "https://www.omdbapi.com/?apikey=$apiKey&plot=full&t=" +
                URLEncoder.encode(title, "UTF-8")
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val j = JSONObject(body)
                if (!j.optString("Response").equals("True", true)) return@use null
                MovieInfo(
                    plot = j.na("Plot"),
                    year = j.na("Year")?.take(4),
                    rating = j.na("imdbRating"),
                    genres = j.na("Genre")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                    poster = j.na("Poster"),
                    director = j.na("Director"),
                    actors = j.na("Actors")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                    type = j.na("Type"),
                )
            }
        }.getOrNull()

        if (result != null) cache[title] = result else missed.add(title)
        result
    }

    /** OMDb's `t=` wants the bare title — drop the trailing "(2026)" year and season/part noise. */
    private fun clean(t: String): String = t
        .replace(Regex("\\(\\d{4}\\)\\s*$"), "")
        .replace(Regex("\\s+(Season|Part)\\s*\\d+.*$", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun JSONObject.na(key: String): String? =
        optString(key).trim().takeIf { it.isNotBlank() && !it.equals("N/A", true) }
}
