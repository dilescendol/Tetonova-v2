package com.tetonova.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.tetonova.core.model.PosterItem
import org.json.JSONObject

/**
 * Watch history ("Riwayat") — titles the user opened, newest-first. Recorded from
 * [com.tetonova.app.ui.AppState.openDetail] (where the real cover/title/badge metadata exists), and
 * the per-card progress bar is read live from [WatchProgressStore]. Local-first: one JSON blob in
 * [SettingsStore], same pattern as [FollowedStore]. Keyed by the title's source URL.
 *
 * Bounded to [MAX] most-recent entries (LRU by `updatedAt`).
 */
object HistoryStore {
    private const val KEY = "history_v1"
    private const val MAX = 300

    private data class Entry(val title: String, val cover: String?, val badge: String?, val sub: String, val epUrl: String?, val updatedAt: Long)

    private var cache: LinkedHashMap<String, Entry>? = null

    /** History titles as posters, newest-first, with the real watch-progress bar folded in. */
    val items: SnapshotStateList<PosterItem> = mutableStateListOf()

    init { runCatching { load() } }

    private fun load(): LinkedHashMap<String, Entry> {
        cache?.let { return it }
        val m = LinkedHashMap<String, Entry>()
        runCatching {
            val raw = SettingsStore.getStr(KEY, "")
            if (raw.isNotBlank()) {
                val o = JSONObject(raw)
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    m[k] = Entry(e.optString("t"), e.optString("c").ifBlank { null }, e.optString("b").ifBlank { null }, e.optString("s"), e.optString("e").ifBlank { null }, e.optLong("u"))
                }
            }
        }
        cache = m
        rebuild(m)
        return m
    }

    private fun rebuild(m: LinkedHashMap<String, Entry>) {
        val posters = m.entries.sortedByDescending { it.value.updatedAt }.map { (url, e) ->
            // Progress = the last-played episode's watched percent (WatchProgressStore keys by episode).
            PosterItem(
                title = e.title, sub = e.sub, art = artOf(url), badge = e.badge,
                progress = WatchProgressStore.percent(e.epUrl ?: url).takeIf { it in 1..100 },
                cover = e.cover, url = url,
            )
        }
        items.clear()
        items.addAll(posters)
    }

    private fun persist(m: LinkedHashMap<String, Entry>) {
        while (m.size > MAX) {
            val oldest = m.minByOrNull { it.value.updatedAt }?.key ?: break
            m.remove(oldest)
        }
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, JSONObject().put("t", v.title).put("c", v.cover ?: "").put("b", v.badge ?: "").put("s", v.sub).put("e", v.epUrl ?: "").put("u", v.updatedAt)) }
        SettingsStore.setStr(KEY, o.toString())
        rebuild(m)
    }

    /**
     * Upsert a title as the most-recent history entry (no-op for blank urls). [url] keys the series
     * (so all its episodes share one entry); [epUrl] is the episode actually played — its watched
     * percent drives the card's progress bar.
     */
    fun record(title: String, url: String?, cover: String?, badge: String?, sub: String = "", epUrl: String? = null) {
        if (url.isNullOrBlank()) return
        val m = load()
        m[url] = Entry(title, cover?.ifBlank { null }, badge?.ifBlank { null }, sub, epUrl?.ifBlank { null }, System.currentTimeMillis())
        persist(m)
        LibrarySync.onLocalChange()
    }

    // ---- sync ----

    /** All entries for an upload to the account. */
    fun exportForSync(): List<HistoryRow> = load().map { (url, e) ->
        HistoryRow(key = url, title = e.title, cover = e.cover, badge = e.badge, sub = e.sub, epUrl = e.epUrl, updatedAt = e.updatedAt)
    }

    /** Apply the account's set, last-write-wins by `updatedAt`. Returns true if anything changed. */
    fun mergeFromSync(rows: List<HistoryRow>): Boolean {
        val m = load()
        var changed = false
        rows.forEach { r ->
            if (r.key.isBlank()) return@forEach
            val cur = m[r.key]
            if (cur == null || r.updatedAt > cur.updatedAt) {
                m[r.key] = Entry(r.title, r.cover?.ifBlank { null }, r.badge?.ifBlank { null }, r.sub, r.epUrl?.ifBlank { null }, r.updatedAt)
                changed = true
            }
        }
        if (changed) persist(m)
        return changed
    }

    /** Re-derive the progress bars from the latest [WatchProgressStore] (call when showing the library,
     *  e.g. after returning from the player, so cards reflect how far the user actually got). */
    fun refresh() { rebuild(load()) }

    /** Stable 0..7 art-bucket fallback (same hash as TnData.artOf) for the placeholder gradient. */
    private fun artOf(s: String): Int { var h = 0; for (c in s) h = h * 31 + c.code; return ((h % 8) + 8) % 8 }
}
