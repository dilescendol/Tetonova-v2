package com.tetonova.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** An opening/ending span within an episode, in milliseconds (player units). */
data class SkipInterval(val startMs: Long, val endMs: Long)

/** Resolved opening/ending skip points for one episode (either may be absent). */
data class SkipTimes(val op: SkipInterval?, val ed: SkipInterval?)

/**
 * Opening/ending timestamps from AniSkip (https://api.aniskip.com), keyed by MyAnimeList id +
 * episode number — the same MAL id [CoverResolver] already resolves for anime. Donghua have no MAL
 * match (`malId <= 0`) so they never reach here and stay on the player's manual skip button.
 *
 * Direct call like [CoverResolver] (no proxy). Everything is cached (hits + misses) and de-duplicated
 * per key; any failure / `found:false` returns null so the player falls back to the manual button.
 */
object SkipResolver {

    private val cache = ConcurrentHashMap<String, SkipTimes>()
    private val miss = ConcurrentHashMap.newKeySet<String>()
    private val inflight = ConcurrentHashMap<String, Deferred<SkipTimes?>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** [episodeLengthSec] 0 = unknown (AniSkip then skips its length-ratio validation). */
    suspend fun fetch(malId: Int, episode: Int, episodeLengthSec: Int): SkipTimes? {
        if (malId <= 0 || episode <= 0) return null
        val key = "$malId:$episode"
        cache[key]?.let { return it }
        if (miss.contains(key)) return null
        val deferred = inflight.getOrPut(key) {
            scope.async {
                val r = fetchTimes(malId, episode, episodeLengthSec)
                if (r != null) cache[key] = r else miss.add(key)
                inflight.remove(key)
                r
            }
        }
        return deferred.await()
    }

    private suspend fun fetchTimes(malId: Int, episode: Int, lengthSec: Int): SkipTimes? = runCatching {
        withContext(Dispatchers.IO) {
            // types[]=op&types[]=ed — keep the brackets literal (AniSkip's documented array form).
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episode".toHttpUrl().newBuilder()
                .addEncodedQueryParameter("types[]", "op")
                .addEncodedQueryParameter("types[]", "ed")
                .addQueryParameter("episodeLength", lengthSec.coerceAtLeast(0).toString())
                .build()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string()
                if (body.isNullOrBlank()) return@use null
                val json = JSONObject(body)
                if (!json.optBoolean("found", false)) return@use null
                val results = json.optJSONArray("results") ?: return@use null
                var op: SkipInterval? = null
                var ed: SkipInterval? = null
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val iv = item.optJSONObject("interval") ?: continue
                    val start = (iv.optDouble("startTime", -1.0) * 1000).toLong()
                    val end = (iv.optDouble("endTime", -1.0) * 1000).toLong()
                    if (start < 0 || end <= start) continue
                    val span = SkipInterval(start, end)
                    when (item.optString("skipType")) {
                        "op" -> if (op == null) op = span
                        "ed" -> if (ed == null) ed = span
                    }
                }
                if (op == null && ed == null) null else SkipTimes(op, ed)
            }
        }
    }.getOrNull()
}
