package com.tetonova.app.data

import com.tetonova.core.model.ForumReply
import com.tetonova.core.model.ForumThread
import com.tetonova.core.model.TagChip
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local persistence for the threads a user actually creates — backed by [SettingsStore]
 * (SharedPreferences) so real posts survive app restarts. The forum has no backend, so this IS the
 * source of truth: there is no synthetic/sample content, the list starts empty and only ever holds
 * what was genuinely posted.
 */
object ForumStore {
    private const val KEY = "forum_threads_v1"

    fun load(): List<ForumThread> = runCatching {
        val raw = SettingsStore.getStr(KEY, "")
        if (raw.isBlank()) return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
    }.getOrDefault(emptyList())

    fun save(threads: List<ForumThread>) {
        val arr = JSONArray()
        threads.forEach { arr.put(toJson(it)) }
        SettingsStore.setStr(KEY, arr.toString())
    }

    private fun toJson(t: ForumThread) = JSONObject().apply {
        put("id", t.id); put("pinned", t.pinned); put("cat", t.cat); put("title", t.title)
        put("excerpt", t.excerpt); put("user", t.user); put("role", t.role ?: JSONObject.NULL)
        put("time", t.time); put("votes", t.votes); put("replies", t.replies); put("views", t.views)
        put("art", t.art); put("thumb", t.thumb)
        put("tags", JSONArray().apply { t.tags.forEach { put(JSONObject().put("label", it.label).put("color", it.color)) } })
        put("comments", JSONArray().apply {
            t.comments.forEach {
                put(
                    JSONObject().put("id", it.id).put("user", it.user).put("role", it.role ?: JSONObject.NULL)
                        .put("time", it.time).put("votes", it.votes).put("text", it.text).put("nested", it.nested),
                )
            }
        })
    }

    private fun fromJson(o: JSONObject): ForumThread {
        val tagsArr = o.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArr.length()).map { tagsArr.getJSONObject(it) }
            .map { TagChip(it.optString("label"), it.optString("color")) }
        val cArr = o.optJSONArray("comments") ?: JSONArray()
        val comments = (0 until cArr.length()).map { cArr.getJSONObject(it) }.map {
            ForumReply(
                id = it.optInt("id"), user = it.optString("user"),
                role = it.optString("role").takeIf { r -> r.isNotBlank() && r != "null" },
                time = it.optString("time"), votes = it.optInt("votes"),
                text = it.optString("text"), nested = it.optBoolean("nested"),
            )
        }
        return ForumThread(
            id = o.getInt("id"), pinned = o.optBoolean("pinned"), cat = o.optString("cat"),
            title = o.optString("title"), excerpt = o.optString("excerpt"), user = o.optString("user"),
            role = o.optString("role").takeIf { it.isNotBlank() && it != "null" },
            time = o.optString("time"), votes = o.optInt("votes"), replies = o.optInt("replies"),
            views = o.optString("views"), tags = tags, art = o.optInt("art"), thumb = o.optBoolean("thumb"),
            comments = comments,
        )
    }
}
