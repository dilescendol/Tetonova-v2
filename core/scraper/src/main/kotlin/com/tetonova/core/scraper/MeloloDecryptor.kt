package com.tetonova.core.scraper

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts Melolo's CENC-style AES-CTR MP4s in place, so ExoPlayer can play them natively (its
 * Mp4Extractor otherwise throws "Invalid NAL length" on the encrypted samples, and a WebView `<video>`
 * fails to decode the garbled AAC). Ported 1:1 from the site's `melolo-decrypt.js`:
 *
 *  1. Parse `moov` → each `vide`/`soun` `trak`'s sample table (stsz/stsc/stco|co64) + `senc` per-sample IVs.
 *  2. AES-CTR-decrypt every sample with the fetched key (16-byte IV counter = 8-byte IV || zeros).
 *  3. Rename the encryption boxes (encv→hvc1, enca→mp4a, senc/sinf/tenc/pssh…→free) → a clean MP4.
 *
 * The key comes from `https://melolo-api.dramabos.fun/api/melolo/key?vid={kid}` (kid is in the
 * `/api/video` qualityList). Episodes are small (~10-20 MB) so we decrypt the whole buffer in memory.
 */
object MeloloDecryptor {
    private data class Box(val offset: Int, val size: Int, val tag: String, val bodyOff: Int)
    private class Track(val sampleOffs: IntArray, val sizes: IntArray, val ivs: List<ByteArray>)

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun u32L(b: ByteArray, o: Int): Long = u32(b, o).toLong() and 0xffffffffL
    private fun u64(b: ByteArray, o: Int): Long = u32L(b, o) * 0x100000000L + u32L(b, o + 4)

    private fun tag(b: ByteArray, o: Int): String =
        String(charArrayOf(b[o].toInt().toChar(), b[o + 1].toInt().toChar(), b[o + 2].toInt().toChar(), b[o + 3].toInt().toChar()))

    private fun writeTag(b: ByteArray, o: Int, t: String) { for (i in 0 until 4) b[o + i] = t[i].code.toByte() }

    private fun boxes(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var off = start
        while (off + 8 <= end) {
            val size = u32(b, off)
            if (size < 8 || off + size > end) break
            out.add(Box(off, size, tag(b, off + 4), off + 8))
            off += size
        }
        return out
    }

    private fun findBox(b: ByteArray, start: Int, end: Int, t: String): Box? =
        boxes(b, start, end).firstOrNull { it.tag == t }

    /** Descend a box path, each step searching only inside the previously matched box's body. */
    private fun walk(b: ByteArray, start: Int, end: Int, vararg path: String): Box? {
        var s = start; var e = end; var found: Box? = null
        for (t in path) {
            found = null
            for (bx in boxes(b, s, e)) if (bx.tag == t) { found = bx; s = bx.bodyOff; e = bx.offset + bx.size; break }
            if (found == null) return null
        }
        return found
    }

    private fun parseSampleTable(b: ByteArray, stblStart: Int, stblEnd: Int): Track? {
        val find = { t: String -> findBox(b, stblStart, stblEnd, t) }
        val stsz = find("stsz"); val stsc = find("stsc"); val stco = find("stco"); val co64 = find("co64"); val senc = find("senc")
        if (stsz == null || stsc == null || (stco == null && co64 == null)) return null

        val defaultSz = u32(b, stsz.bodyOff + 4)
        val n = u32(b, stsz.bodyOff + 8)
        val sizes = IntArray(n)
        if (defaultSz == 0) for (i in 0 until n) sizes[i] = u32(b, stsz.bodyOff + 12 + i * 4)
        else sizes.fill(defaultSz)

        val chunkOffs: LongArray
        if (stco != null) {
            val cnt = u32(b, stco.bodyOff + 4); chunkOffs = LongArray(cnt) { u32L(b, stco.bodyOff + 8 + it * 4) }
        } else {
            val cnt = u32(b, co64!!.bodyOff + 4); chunkOffs = LongArray(cnt) { u64(b, co64.bodyOff + 8 + it * 8) }
        }

        val ne = u32(b, stsc.bodyOff + 4)
        data class Entry(val firstChunk: Int, val spc: Int)
        val entries = ArrayList<Entry>(ne)
        for (i in 0 until ne) entries.add(Entry(u32(b, stsc.bodyOff + 8 + i * 12), u32(b, stsc.bodyOff + 12 + i * 12)))

        val sampleOffs = ArrayList<Int>(n); var si = 0
        var ci = 0
        while (ci < chunkOffs.size && si < n) {
            var spc = 1
            for (e in entries) { if (ci + 1 >= e.firstChunk) spc = e.spc else break }
            var cur = chunkOffs[ci]
            var k = 0
            while (k < spc && si < n) { sampleOffs.add(cur.toInt()); cur += sizes[si]; si++; k++ }
            ci++
        }

        val ivs = ArrayList<ByteArray>()
        if (senc != null) {
            val flags = ((b[senc.bodyOff + 1].toInt() and 0xff) shl 16) or ((b[senc.bodyOff + 2].toInt() and 0xff) shl 8) or (b[senc.bodyOff + 3].toInt() and 0xff)
            val cnt = u32(b, senc.bodyOff + 4)
            var off = senc.bodyOff + 8
            for (i in 0 until cnt) {
                ivs.add(b.copyOfRange(off, off + 8)); off += 8
                if (flags and 0x02 != 0) off += 2 + (((b[off].toInt() and 0xff) shl 8) or (b[off + 1].toInt() and 0xff)) * 6
            }
        }
        return Track(sampleOffs.toIntArray(), sizes, ivs)
    }

    private fun parseTracks(b: ByteArray, moov: Box): List<Track> {
        val tracks = ArrayList<Track>()
        for (trak in boxes(b, moov.bodyOff, moov.offset + moov.size)) {
            if (trak.tag != "trak") continue
            val mdia = walk(b, trak.bodyOff, trak.offset + trak.size, "mdia") ?: continue
            val hdlr = walk(b, mdia.bodyOff, mdia.offset + mdia.size, "hdlr") ?: continue
            val handler = tag(b, hdlr.offset + 16)
            if (handler != "vide" && handler != "soun") continue
            val stbl = walk(b, mdia.bodyOff, mdia.offset + mdia.size, "minf", "stbl") ?: continue
            val track = parseSampleTable(b, stbl.bodyOff, stbl.offset + stbl.size)
            if (track != null && track.ivs.isNotEmpty()) tracks.add(track)
        }
        return tracks
    }

    private fun neutralizeDrm(b: ByteArray, moovStart: Int, moovEnd: Int) {
        val rename = mapOf(
            "encv" to "hvc1", "enca" to "mp4a", "sinf" to "free", "schm" to "free", "schi" to "free",
            "senc" to "free", "saio" to "free", "saiz" to "free", "tenc" to "free", "frma" to "free", "pssh" to "free",
        )
        var i = moovStart + 4
        while (i + 4 <= moovEnd) {
            val t = tag(b, i)
            val to = rename[t]
            if (to != null) {
                val sz = u32(b, i - 4)
                if (sz in 8..(moovEnd - i + 4)) writeTag(b, i, to)
            }
            i++
        }
        var off = 0
        while (off + 8 <= b.size) {
            val sz = u32(b, off)
            if (sz < 8 || off + sz > b.size) break
            if (tag(b, off + 4) == "pssh") writeTag(b, off + 4, "free")
            off += sz
        }
    }

    /** Decrypts [data] in place and returns it. Throws if there's no moov or no encrypted tracks. */
    fun decrypt(data: ByteArray, keyHex: String): ByteArray {
        val key = SecretKeySpec(ByteArray(keyHex.length / 2) { ((keyHex[it * 2].digitToInt(16) shl 4) or keyHex[it * 2 + 1].digitToInt(16)).toByte() }, "AES")
        val moov = walk(data, 0, data.size, "moov") ?: throw IllegalStateException("No moov")
        val tracks = parseTracks(data, moov)
        if (tracks.isEmpty()) throw IllegalStateException("No encrypted tracks")

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val counter = ByteArray(16)
        for (t in tracks) {
            val cnt = minOf(t.sampleOffs.size, t.ivs.size)
            for (i in 0 until cnt) {
                counter.fill(0)
                t.ivs[i].copyInto(counter, 0)
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(counter))
                val off = t.sampleOffs[i]; val sz = t.sizes[i]
                val dec = cipher.doFinal(data, off, sz)
                dec.copyInto(data, off)
            }
        }
        neutralizeDrm(data, moov.offset, moov.offset + moov.size)
        return data
    }
}
