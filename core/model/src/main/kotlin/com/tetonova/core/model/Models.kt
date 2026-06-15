package com.tetonova.core.model

/**
 * Pure UI/domain models for the TetoNova app. No Android or Compose dependencies —
 * these mirror the sample data shapes from the design prototype (app/data.jsx) and
 * will later be populated from the repo-tn registry + control-panel API.
 */

/** Primary navigation destinations (design: NAV in data.jsx). */
enum class NavDest(val id: String, val label: String, val icon: String) {
    HOME("home", "Home", "home"),
    SEARCH("search", "Search", "search"),
    FORUM("forum", "Forum", "forum"),
    DOWNLOADS("downloads", "Downloads", "download"),
    EXTENSIONS("ext", "Extensions", "ext"),
    PROFILE("profile", "Profile", "user"),
}

/**
 * A poster/catalog card. `art` indexes into the gradient palette for the placeholder; [cover] is
 * the real artwork URL scraped from the source site (used directly when present), and [url] is the
 * upstream detail-page link for live enrichment.
 */
data class PosterItem(
    val title: String,
    val sub: String,
    val art: Int,
    val badge: String? = null,
    val ep: String? = null,
    val progress: Int? = null,
    val cover: String? = null,
    val url: String? = null,
)

/** A featured spotlight slide on Home. `cover`/`url` are set for live (scraped) spotlights. */
data class SpotItem(
    val title: String,
    val sub: String,
    val syn: String,
    val tags: List<SpotTag>,
    val rating: String,
    val eps: String,
    val status: String,
    val art: Int,
    val cover: String? = null,
    val url: String? = null,
)

data class SpotTag(val icon: String, val label: String)

/** Home quick-status chip. */
data class QuickChip(val title: String, val value: String, val icon: String, val grad: Int)

/** Extensions screen. */
data class ExtCategory(val id: String, val label: String, val count: Int)
data class ExtItem(val name: String, val source: String, val live: Boolean)

/** Forum. */
data class ForumCategory(val id: String, val label: String, val icon: String? = null)
data class TagChip(val label: String, val color: String = "")
data class ForumThread(
    val id: Int,
    val pinned: Boolean,
    val cat: String,
    val title: String,
    val excerpt: String,
    val user: String,
    val role: String?,
    val time: String,
    val votes: Int,
    val replies: Int,
    val views: String,
    val tags: List<TagChip>,
    val art: Int,
    val thumb: Boolean = false,
    /** Real comments posted on this thread (the forum has no backend; this is the source of truth). */
    val comments: List<ForumReply> = emptyList(),
)
data class ForumReply(
    val id: Int,
    val user: String,
    val role: String?,
    val time: String,
    val votes: Int,
    val text: String,
    val nested: Boolean,
)

/** Profile. */
data class StatItem(val label: String, val value: String, val delta: String?, val icon: String, val grad: Int)
data class BadgeItem(val name: String, val desc: String, val icon: String, val grad: Int, val locked: Boolean)
enum class RewardState { CLAIMED, NEXT, LOCKED }
data class RewardItem(val level: String, val name: String, val icon: String, val grad: Int, val state: RewardState)

/** Title Detail. */
data class Episode(
    val id: String,
    val num: Int,
    val name: String,
    val dur: String,
    val desc: String,
    val progress: Int,
    val art: Int,
    /** Upstream watch page for this episode (live sources) — opened by "Tonton". */
    val url: String? = null,
)
data class CastMember(val name: String, val role: String, val grad: Int)
data class InfoRow(val key: String, val value: String)
