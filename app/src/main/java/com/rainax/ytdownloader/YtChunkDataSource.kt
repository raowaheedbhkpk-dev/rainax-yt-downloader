package com.rainax.ytdownloader

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams YouTube sound the same way the downloader fetches it: small byte ranges (1 MB) with the
 * right User-Agent. YouTube refuses one long open request for these links, which made the
 * background player stop right after it appeared.
 *
 * Accepts "rainax://play?u=<video page>" (sound address looked up when needed, and looked up
 * again if YouTube says the old one has expired) or a direct https address.
 */
@OptIn(UnstableApi::class)
class YtChunkDataSource : BaseDataSource(true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = YtChunkDataSource()
    }

    private var spec: DataSpec? = null
    private var uri: Uri? = null
    private var page: String? = null
    private var url = ""
    private var pos = 0L                 // next byte to read (absolute)
    private var end = -1L                // stop before this byte (absolute), -1 = not known yet
    private var conn: HttpURLConnection? = null
    private var input: InputStream? = null
    private var chunkLeft = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        spec = dataSpec
        uri = dataSpec.uri
        page = if (dataSpec.uri.scheme == "rainax") dataSpec.uri.getQueryParameter("u") else null
        url = page?.let { BgPlayService.audioFor(it, fresh = false) } ?: dataSpec.uri.toString()
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) pos + dataSpec.length else -1L
        val total = openChunkWithRefresh()
        if (end < 0 && total > 0) end = total
        if (end in 0..pos && dataSpec.position > 0) {
            closeConnection()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        opened = true
        transferStarted(dataSpec)
        return if (end >= 0) end - pos else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var reopened = false
        while (true) {
            if (end in 0..pos) return C.RESULT_END_OF_INPUT
            if (chunkLeft <= 0L || input == null) {
                if (reopened) {
                    // the server stopped before the known end: an error (the player retries), not "finished"
                    if (end >= 0 && pos < end) throw java.io.IOException("Stream ended early")
                    return C.RESULT_END_OF_INPUT                 // server had nothing more
                }
                closeConnection()
                openChunkWithRefresh()
                reopened = true
                if (chunkLeft <= 0L) return C.RESULT_END_OF_INPUT
            }
            var want = minOf(length.toLong(), chunkLeft)
            if (end >= 0) want = minOf(want, end - pos)
            val n = input!!.read(buffer, offset, want.toInt())
            if (n < 0) {
                if (end < 0) end = pos                         // size unknown: this is the end
                chunkLeft = 0L
                continue
            }
            pos += n
            chunkLeft -= n
            bytesTransferred(n)
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeConnection()
        if (opened) {
            opened = false
            transferEnded()
        }
        spec = null
    }

    /** Opens the next piece; if YouTube refuses an old address, asks for a fresh one once. */
    private fun openChunkWithRefresh(): Long = try {
        openChunk()
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        val p = page
        if (p == null || e.responseCode !in 400..499 || e.responseCode == 416) throw e
        url = BgPlayService.audioFor(p, fresh = true)
        openChunk()
    }

    /** Requests bytes [pos, pos + 1 MB). Returns the full size of the sound, or -1. */
    private fun openChunk(): Long {
        val dataSpec = spec ?: throw IOException("Not open")
        var last = pos + CHUNK - 1
        if (end >= 0) last = minOf(last, end - 1)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null)
        else FastExtractor.UA
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept", "*/*")
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("Range", "bytes=$pos-$last")
        val code = try {
            c.responseCode
        } catch (e: IOException) {
            c.disconnect()
            throw HttpDataSource.HttpDataSourceException(
                e, dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN
            )
        }
        if (code == 416) {
            // asked past the end: nothing more to read
            c.disconnect()
            if (end < 0) end = pos
            chunkLeft = 0L
            return end
        }
        if (code !in 200..299) {
            val headers = c.headerFields.filterKeys { it != null }
            val msg = runCatching { c.responseMessage }.getOrNull()
            c.disconnect()
            throw HttpDataSource.InvalidResponseCodeException(code, msg, null, headers, dataSpec, ByteArray(0))
        }
        conn = c
        val stream = c.inputStream
        input = stream
        var total: Long
        if (code == 206) {
            // "bytes 0-1048575/3891234"
            val range = c.getHeaderField("Content-Range").orEmpty()
            total = range.substringAfter('/', "").trim().toLongOrNull() ?: -1L
            val from = range.substringAfter("bytes ", "").substringBefore('-').trim().toLongOrNull() ?: pos
            val to = range.substringAfter('-', "").substringBefore('/').trim().toLongOrNull()
            if (from != pos) skipFully(stream, pos - from)
            chunkLeft = when {
                to != null -> to - pos + 1
                c.contentLengthLong > 0 -> c.contentLengthLong
                else -> last - pos + 1
            }
            if (total < 0 && to != null && to < last) total = to + 1     // got less than asked: that was the end
        } else {
            // the server ignored the range and is sending everything
            val len = c.contentLengthLong
            total = len
            skipFully(stream, pos)
            chunkLeft = if (len > 0) len - pos else Long.MAX_VALUE
        }
        return total
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var left = count
        val tmp = ByteArray(8192)
        while (left > 0) {
            val n = stream.read(tmp, 0, minOf(left, tmp.size.toLong()).toInt())
            if (n < 0) throw IOException("Stream ended early")
            left -= n
        }
    }

    private fun closeConnection() {
        val fullyRead = chunkLeft == 0L
        try { input?.close() } catch (_: Exception) { }
        input = null
        // a piece read to its end: closing the stream lets Android reuse the connection for the next piece
        // (no new handshake per MB = faster start and seeking); cut it only when stopping halfway
        if (!fullyRead) conn?.disconnect()
        conn = null
        chunkLeft = 0L
    }

    private companion object {
        const val CHUNK = 1L shl 20      // 1 MB, the same piece size the downloader uses
    }
}
