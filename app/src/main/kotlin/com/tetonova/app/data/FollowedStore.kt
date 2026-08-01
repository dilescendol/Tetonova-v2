package com.tetonova.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.tetonova.core.model.PosterItem
import org.json.JSONObject

/**
 * The user's "Followed" / "daftar" list — titles followed via Detail's ➕ button. Local-first: one
 * JSON blob in [SettingsStore] (same pattern as [WatchProgressStore]); the source of truth on-device.
 * When signed in, [LibrarySync] mirrors it to the account so it carries across devices.
 *
 * Each entry carries `active` + `updatedAt`: an unfollow is a **tombstone** (`active=false`) rather
 * than a delete, so a removal on one device propagates through the last-write-wins sync. [items] shows
 * only active entries (snapshot-backed → live UI). Keyed by the title's source URL. Bounded to [MAX].
 */
object FollowedStore {
    private const val KEY = "followed_v1"
    private const val MAX = 500

    private data class Entry(
        val title: String,
        val cover: String?,
        val badge: String?,
        val sub: String,
        val active: Boolean,
        val updatedAt: Long,
    )

    private var cache: LinkedHashMap<String, Entry>? = null

    /** Followed titles as posters, newest-first. Read this from Compose for live updates. */
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
                    // Back-compat: pre-sync entries had only "a" (addedAt) and no active flag.
                    val updatedAt = if (e.has("u")) e.optLong("u") else e.optLong("a")
                    val active = if (e.has("ac")) e.optInt("ac") == 1 else true
                    m[k] = Entry(e.optString("t"), e.optString("c").ifBlank { null }, e.optString("b").ifBlank { null }, e.optString("s"), active, updatedAt)
                }
            }
        }
        cache = m
        rebuild(m)
        return m
    }

    private fun rebuild(m: LinkedHashMap<String, Entry>) {
        val posters = m.entries.filter { it.value.active }.sortedByDescending { it.value.updatedAt }.map { (url, e) ->
            PosterItem(title = e.title, sub = e.sub, art = artOf(url), badge = e.badge, cover = e.cover, url = url)
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
        m.forEach { (k, v) ->
            o.put(k, JSONObject().put("t", v.title).put("c", v.cover ?: "").put("b", v.badge ?: "").put("s", v.sub).put("ac", if (v.active) 1 else 0).put("u", v.updatedAt))
        }
        SettingsStore.setStr(KEY, o.toString())
        rebuild(m)
    }

    fun isFollowed(url: String?): Boolean = !url.isNullOrBlank() && load()[url]?.active == true

    fun add(title: String, url: String?, cover: String?, badge: String?, sub: String = "") {
        if (url.isNullOrBlank()) return
        val m = load()
        m[url] = Entry(title, cover?.ifBlank { null }, badge?.ifBlank { null }, sub, active = true, updatedAt = System.currentTimeMillis())
        persist(m)
        LibrarySync.onLocalChange()
    }

    /** Unfollow = tombstone (active=false) so the removal syncs to other devices. */
    fun remove(url: String?) {
        if (url.isNullOrBlank()) return
        val m = load()
        val cur = m[url] ?: return
        if (!cur.active) return
        m[url] = cur.copy(active = false, updatedAt = System.currentTimeMillis())
        persist(m)
        LibrarySync.onLocalChange()
    }

    // ---- sync ----

    /** All entries (incl. tombstones) for an upload to the account. */
    fun exportForSync(): List<FollowedRow> = load().map { (url, e) ->
        FollowedRow(key = url, title = e.title, cover = e.cover, badge = e.badge, sub = e.sub, active = e.active, updatedAt = e.updatedAt)
    }

    /** Apply the account's set, last-write-wins by `updatedAt`. Returns true if anything changed. */
    fun mergeFromSync(rows: List<FollowedRow>): Boolean {
        val m = load()
        var changed = false
        rows.forEach { r ->
            if (r.key.isBlank()) return@forEach
            val cur = m[r.key]
            if (cur == null || r.updatedAt > cur.updatedAt) {
                m[r.key] = Entry(r.title, r.cover?.ifBlank { null }, r.badge?.ifBlank { null }, r.sub, r.active, r.updatedAt)
                changed = true
            }
        }
        if (changed) persist(m)
        return changed
    }

    /** Stable 0..7 art-bucket fallback (same hash as TnData.artOf) for the placeholder gradient. */
    private fun artOf(s: String): Int { var h = 0; for (c in s) h = h * 31 + c.code; return ((h % 8) + 8) % 8 }
}
