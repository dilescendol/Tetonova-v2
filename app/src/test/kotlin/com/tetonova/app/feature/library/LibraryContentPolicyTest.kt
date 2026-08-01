package com.tetonova.app.feature.library

import com.tetonova.app.data.LibraryContentPolicy
import com.tetonova.app.data.SourceOverride
import com.tetonova.core.model.PosterItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryContentPolicyTest {
    private fun poster(
        title: String = "Regular Show",
        url: String = "https://unknown-extension.example/detail/regular-show",
        badge: String? = "Anime",
        sub: String = "Unknown Extension",
        cover: String? = null,
    ) = PosterItem(title = title, sub = sub, art = 2, badge = badge, cover = cover, url = url)

    @Test
    fun uninstalledOrUnknownExtensionPlaceholderStaysVisible() {
        val placeholder = poster()

        val visible = filterLibraryItems(listOf(placeholder), showMature = false) {
            LibraryContentPolicy.isMature(it)
        }

        assertEquals(listOf(placeholder), visible)
        assertEquals(null, visible.single().cover)
    }

    @Test
    fun matureRowsAreHiddenWhenToggleIsOff() {
        val matureItems = listOf(
            poster(url = "https://nekopoi.care/hentai/example/"),
            poster(url = "https://javhey.com/video/fc2-ppv-123456"),
            poster(url = "https://example.com/category/jav/fc2-ppv-123456"),
            poster(title = "FC2-PPV 123456", url = "https://unknown.example/watch/123456"),
        )

        assertEquals(
            emptyList(),
            filterLibraryItems(matureItems, showMature = false) { LibraryContentPolicy.isMature(it) },
        )
    }

    @Test
    fun matureRowsReturnWhenToggleIsOn() {
        val matureItems = listOf(
            poster(url = "https://nekopoi.care/hentai/example/"),
            poster(url = "https://javhey.com/video/fc2-ppv-123456"),
        )

        assertEquals(
            matureItems,
            filterLibraryItems(matureItems, showMature = true) { LibraryContentPolicy.isMature(it) },
        )
    }

    @Test
    fun panelAdultMetadataAlsoMarksAStoredRowAsMature() {
        val source = SourceOverride(
            sourceId = "rotating-source",
            displayName = "Private Catalog",
            apiBaseUrl = "https://rotating.example",
            category = "Adult",
        )

        assertTrue(LibraryContentPolicy.isMature(poster(), source))
        assertFalse(LibraryContentPolicy.isMature(poster()))
    }
}
