package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertEquals

class PaginationParserTest {
    @Test
    fun numberedPagerUsesNextNumberInsteadOfLastPageChevron() {
        val html = """
            <ul class="pagination">
              <li class="active"><a href="https://tv.example/release/page/1">1</a></li>
              <li><a href="https://tv.example/release/page/2">2</a></li>
              <li><a href="https://tv.example/release/page/3">3</a></li>
              <li><a href="https://tv.example/release/page/1177">&raquo;</a></li>
            </ul>
        """.trimIndent()

        assertEquals(
            "https://tv.example/release/page/2",
            LiveParser.parseNextPage(html, "https://tv.example/release/page/1"),
        )
    }
}
