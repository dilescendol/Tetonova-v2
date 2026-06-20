package com.tetonova.app.data

import org.json.JSONObject

/**
 * "Lanjut tonton" — the last playback position per episode, so reopening an episode resumes instead
 * of restarting. Keyed by the episode's source URL ([com.tetonova.app.ui.PlayerArg.url]); works the
 * same for online streaming and downloaded/offline playback. Backed by [SettingsStore] (one JSON
 * blob) following the same pattern as [ForumStore] — there is no backend, this is the source of truth.
 *
 * Bounded to [MAX] most-recent entries (LRU by `updatedAt`) so it can't grow without bound.
 */
object WatchProgressStore {
    private const val KEY = "watch_progress_v1"
    private const val MAX = 300
    /** ≥ this fraction watched counts as "finished" → no resume, full progress bar. */
    private const val FINISHED_FRAC = 0.92
    /** Don't bother resuming the first few seconds. */
    private const val MIN_RESUME_MS = 5_000L

    data class Progress(val positionMs: Long, val durationMs: Long, val updatedAt: Long)

    private var cache: LinkedHashMap<String, Progress>? = null

    private fun map(): LinkedHashMap<String, Progress> {
        cache?.let { return it }
        val m = LinkedHashMap<String, Progress>()
        runCatching {
            val raw = SettingsStore.getStr(KEY, "")
            if (raw.isNotBlank()) {
                val o = JSONObject(raw)
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    m[k] = Progress(e.optLong("p"), e.optLong("d"), e.optLong("t"))
                }
            }
        }
        return m.also { cache = it }
    }

    private fun persist(m: LinkedHashMap<String, Progress>) {
        // Trim oldest first so the JSON stays small.
        while (m.size > MAX) {
            val oldest = m.minByOrNull { it.value.updatedAt }?.key ?: break
            m.remove(oldest)
        }
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONObject().put("p", v.positionMs).put("d", v.durationMs).put("t", v.updatedAt)) }
        SettingsStore.setStr(KEY, o.toString())
    }

    fun get(url: String?): Progress? = url?.let { map()[it] }

    /** Record the current position. No-op for blank urls / unknown duration. */
    fun save(url: String?, positionMs: Long, durationMs: Long) {
        if (url.isNullOrBlank() || durationMs <= 0L || positionMs < 0L) return
        val m = map()
        m[url] = Progress(positionMs.coerceAtMost(durationMs), durationMs, System.currentTimeMillis())
        persist(m)
    }

    /** Mark an episode fully watched (so it won't resume near the end and shows a full bar). */
    fun markFinished(url: String?, durationMs: Long) {
        if (url.isNullOrBlank() || durationMs <= 0L) return
        val m = map()
        m[url] = Progress(durationMs, durationMs, System.currentTimeMillis())
        persist(m)
    }

    fun isFinished(url: String?): Boolean {
        val p = get(url) ?: return false
        return p.durationMs > 0L && p.positionMs >= p.durationMs * FINISHED_FRAC
    }

    /** Position to seek to on open, or 0 when there's nothing useful to resume (none / finished / start). */
    fun resumePositionMs(url: String?): Long {
        val p = get(url) ?: return 0L
        if (p.durationMs <= 0L) return 0L
        if (p.positionMs < MIN_RESUME_MS) return 0L
        if (p.positionMs >= p.durationMs * FINISHED_FRAC) return 0L
        return p.positionMs
    }

    /** Watched percent (0..100) for the episode-card progress bar. */
    fun percent(url: String?): Int {
        val p = get(url) ?: return 0
        if (p.durationMs <= 0L) return 0
        return ((p.positionMs * 100f) / p.durationMs).toInt().coerceIn(0, 100)
    }
}
