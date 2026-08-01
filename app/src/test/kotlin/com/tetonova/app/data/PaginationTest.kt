package com.tetonova.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaginationTest {
    @Test
    fun advancesNumberedPagesWithoutTouchingOtherParameters() {
        assertEquals(
            "https://api.example/list?lang=id&page=10&page_size=20",
            nextNumberedPageUrl("https://api.example/list?lang=id&page=9&page_size=20", true),
        )
        assertNull(nextNumberedPageUrl("https://api.example/list?page=9", false))
        assertNull(nextNumberedPageUrl("https://api.example/list?cursor=abc", true))
    }

    @Test
    fun advancesPathPagesUsedByLk21AndNontonDrama() {
        assertEquals(
            "https://tv12.lk21official.cc/release/page/2",
            nextKnownCatalogPageUrl(
                "https://tv12.lk21official.cc/release/page/1",
                "lk21-compat",
                true,
            ),
        )
        assertEquals(
            "https://tv5.nontondrama.my/release/page/10",
            nextKnownCatalogPageUrl(
                "https://tv5.nontondrama.my/release/page/9",
                "nontondrama-compat",
                true,
            ),
        )
    }

    @Test
    fun startsKnownQueryAndWordpressPaginationWhenNextMetadataIsMissing() {
        assertEquals(
            "https://v19.kuramanime.ing/quick/ongoing?order_by=updated&page=2",
            nextKnownCatalogPageUrl(
                "https://v19.kuramanime.ing/quick/ongoing?order_by=updated",
                "kuramanime-compat",
                true,
            ),
        )
        assertEquals(
            "https://plus.oploverz.ltd/?page=2",
            nextKnownCatalogPageUrl("https://plus.oploverz.ltd/", "oploverz-compat", true),
        )
        assertEquals(
            "https://v3.pusatfilm21info.net/film-terbaru/page/2/",
            nextKnownCatalogPageUrl(
                "https://v3.pusatfilm21info.net/film-terbaru",
                "pusatfilm-compat",
                true,
            ),
        )
        assertEquals(
            "https://v2.samehadaku.how/page/2/",
            nextKnownCatalogPageUrl("https://v2.samehadaku.how", "samehadaku-compat", true),
        )
    }

    @Test
    fun doesNotInventPaginationForUnknownOrEmptyCatalogs() {
        assertNull(nextKnownCatalogPageUrl("https://example.com/catalog", "other", true))
        assertNull(nextKnownCatalogPageUrl("https://v2.samehadaku.how", "samehadaku-compat", false))
    }
}
