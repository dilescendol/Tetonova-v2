package com.tetonova.app.feature.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.tetonova.core.scraper.MeloloDecryptor
import java.io.ByteArrayOutputStream

/**
 * Plays Melolo's AES-CTR-encrypted MP4s in ExoPlayer. Melolo streams carry the AES key as a `#tnk=<hex>`
 * URL fragment (added in `MeloloSource.servers`). This DataSource downloads the whole (small, ~10-20 MB)
 * episode via [upstream], decrypts it in memory with [MeloloDecryptor] on `open()`, then serves the
 * plain MP4 bytes — so ExoPlayer's Mp4Extractor sees a clean file instead of "Invalid NAL length".
 *
 * The decrypted buffer is cached (1 entry) by clean URL so a seek (re-open at a new position) reuses it
 * instead of re-downloading and re-decrypting.
 */
@UnstableApi
class MeloloDataSourceFactory(private val http: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = MeloloDataSource(http.createDataSource())
}

@UnstableApi
private class MeloloDataSource(private val upstream: DataSource) : DataSource {
    private var bytes: ByteArray? = null
    private var pos = 0
    private var remaining = 0
    private var uri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val full = dataSpec.uri
        uri = full
        val keyHex = Regex("(?:^|&)tnk=([0-9a-fA-F]+)").find(full.fragment.orEmpty())?.groupValues?.get(1)
            ?: throw java.io.IOException("melolo: missing #tnk key")
        val cleanUri = full.buildUpon().fragment(null).build()
        val decrypted = cached(cleanUri.toString()) {
            val len = upstream.open(DataSpec.Builder().setUri(cleanUri).build())
            val out = ByteArrayOutputStream(if (len > 0) len.toInt() else 1 shl 20)
            val tmp = ByteArray(1 shl 16)
            try {
                while (true) {
                    val r = upstream.read(tmp, 0, tmp.size)
                    if (r == C.RESULT_END_OF_INPUT) break
                    out.write(tmp, 0, r)
                }
            } finally { upstream.close() }
            MeloloDecryptor.decrypt(out.toByteArray(), keyHex)
        }
        bytes = decrypted
        pos = dataSpec.position.toInt()
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length.toInt() else decrypted.size - pos
        return remaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0) return C.RESULT_END_OF_INPUT
        val b = bytes ?: return C.RESULT_END_OF_INPUT
        val n = minOf(length, remaining, b.size - pos)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        System.arraycopy(b, pos, buffer, offset, n)
        pos += n; remaining -= n
        return n
    }

    override fun getUri(): Uri? = uri
    override fun close() {}

    companion object {
        private var cacheUrl: String? = null
        private var cacheBytes: ByteArray? = null

        @Synchronized
        private fun cached(url: String, produce: () -> ByteArray): ByteArray {
            if (cacheUrl == url) cacheBytes?.let { return it }
            val b = produce()
            cacheUrl = url; cacheBytes = b
            return b
        }
    }
}
