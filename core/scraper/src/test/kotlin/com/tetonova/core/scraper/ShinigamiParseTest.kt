package com.tetonova.core.scraper

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Shinigami mappers: the page-URL construction (base_url + chapter.path + filename) is the piece that
 *  breaks silently if the JSON shape shifts, so pin it plus the card/chapter field mapping. */
class ShinigamiParseTest {

    @Test
    fun pageUrlsJoinBasePathAndFilename() {
        val data = JSONObject(
            """
            {
              "chapter_number": 158,
              "base_url": "https://assets.shngm.id",
              "prev_chapter_id": "7ac48e20-6232-4ea2-9cb3-c760e5ebf206",
              "next_chapter_id": null,
              "chapter": {
                "path": "/chapter/manga_11adc193/chapter_bffccdc9/",
                "data": ["00-0ecd8a.jpg", "01-12e6a6.jpg", "", "02-12e3b9.jpg"]
              }
            }
            """.trimIndent(),
        )
        val ch = ShinigamiSource.readerChapterOf(data)!!
        assertEquals("158", ch.number)
        assertEquals(
            listOf(
                "https://assets.shngm.id/chapter/manga_11adc193/chapter_bffccdc9/00-0ecd8a.jpg",
                "https://assets.shngm.id/chapter/manga_11adc193/chapter_bffccdc9/01-12e6a6.jpg",
                "https://assets.shngm.id/chapter/manga_11adc193/chapter_bffccdc9/02-12e3b9.jpg",
            ),
            ch.pages,
            "blank filenames dropped; base+path+file joined without a double slash",
        )
        assertEquals("7ac48e20-6232-4ea2-9cb3-c760e5ebf206", ch.prevId)
        assertNull(ch.nextId, "JSON null prev/next must map to Kotlin null (not the string \"null\")")
    }

    @Test
    fun cardPrefersPortraitCoverAndLabelsLatestChapter() {
        val card = ShinigamiSource.cardOf(
            JSONObject(
                """
                {"manga_id":"c0f1d049","title":"Demonic Emperor",
                 "cover_image_url":"https://x/land.jpg","cover_portrait_url":"https://x/port.jpg",
                 "latest_chapter_number":884}
                """.trimIndent(),
            ),
        )!!
        assertEquals("https://x/port.jpg", card.cover)
        assertEquals("Ch. 884", card.latestChapter)
    }

    @Test
    fun chapterKeepsDecimalNumbersButTrimsWholeOnes() {
        assertEquals("158", ShinigamiSource.fmtNum(158.0))
        assertEquals("158.5", ShinigamiSource.fmtNum(158.5))
        val half = ShinigamiSource.chapterOf(JSONObject("""{"chapter_id":"a","chapter_number":158.5,"chapter_title":""}"""))!!
        assertEquals(158.5, half.number)
    }
}
