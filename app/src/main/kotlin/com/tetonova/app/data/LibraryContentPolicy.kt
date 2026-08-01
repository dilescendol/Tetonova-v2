package com.tetonova.app.data

import com.tetonova.core.model.PosterItem
import java.util.Locale

/** Visibility rules for saved library rows. Extension install state is intentionally irrelevant. */
internal object LibraryContentPolicy {
    private val matureTag = Regex("(^|[^a-z0-9])(18\\+|adult|mature|hentai|jav|uncensored)([^a-z0-9]|$)")
    private val matureTitle = Regex("(^|[^a-z0-9])fc2[-_ ]?ppv([^a-z0-9]|$)")

    private val matureHosts = listOf(
        "nekopoi",
        "javhey",
        "indomax21",
        "onperfect.com",
        "homecookingrocks.com",
        "lomaresort.com",
        "topnetseo.com",
        "otrarevista.com",
    )

    private val maturePaths = listOf(
        "/hentai/",
        "/category/hentai",
        "/category/jav",
        "/jav/",
        "/jav-cosplay/",
        "/uncensored/",
    )

    fun isMature(item: PosterItem, source: SourceOverride? = null): Boolean {
        if (sourceAllowsMature(source, item.badge)) return true

        val url = item.url.orEmpty().lowercase(Locale.ROOT)
        if (matureHosts.any(url::contains) || maturePaths.any(url::contains)) return true

        val savedMetadata = listOf(item.badge, item.sub).joinToString(" ").lowercase(Locale.ROOT)
        return matureTag.containsMatchIn(savedMetadata) ||
            matureTitle.containsMatchIn(item.title.lowercase(Locale.ROOT))
    }

    fun sourceAllowsMature(source: SourceOverride?, badge: String?): Boolean {
        val metadata = listOf(
            source?.sourceId,
            source?.displayName,
            source?.category,
            badge,
        ).joinToString(" ").lowercase(Locale.ROOT)
        return matureHosts.take(3).any(metadata::contains) || matureTag.containsMatchIn(metadata)
    }
}
