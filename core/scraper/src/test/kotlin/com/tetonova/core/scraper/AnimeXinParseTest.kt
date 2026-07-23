package com.tetonova.core.scraper

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AnimeXin lists ~16 servers as Base64 `<option value>` blobs, several wrapped in a schema.org
 * VideoObject `<div>` (its Dailymotion mirrors). Two bugs used to drop most of them — long Base64 with
 * `+` corrupted by URL-decoding, and the `itemtype="…schema.org…"` URL being grabbed as the stream —
 * so only 2 of 5 Indonesian servers survived. Guard both.
 */
class AnimeXinParseTest {
    private val servers by lazy {
        LiveParser.parseServers(javaClass.classLoader.getResource("animexin_ep.html")!!.readText())
    }

    @Test fun allMirrorsParse() {
        assertTrue(servers.size >= 14, "expected the full AnimeXin mirror list, got ${servers.size}: ${servers.map { it.name }}")
    }

    @Test fun dailymotionMirrorsResolveToRealEmbeds() {
        val dm = servers.filter { "dailymotion" in it.embedUrl.lowercase() }
        assertTrue(dm.size >= 3, "all 3 Dailymotion mirrors should parse, got ${dm.map { it.name }}")
        assertTrue(dm.all { "geo.dailymotion.com/player" in it.embedUrl && "video=" in it.embedUrl }, "Dailymotion embeds malformed: ${dm.map { it.embedUrl }}")
        // The indo Dailymotion mirror (the one the user actually needs) must be present + named.
        assertTrue(servers.any { it.name.contains("indonesia", true) && "dailymotion" in it.embedUrl.lowercase() }, "missing Hardsub Indonesia Dailymotion")
    }

    @Test fun noSchemaOrgGarbage() {
        assertTrue(servers.none { "schema.org" in it.embedUrl || "w3.org" in it.embedUrl }, "namespace URL leaked as a stream: ${servers.map { it.embedUrl }}")
    }
}
