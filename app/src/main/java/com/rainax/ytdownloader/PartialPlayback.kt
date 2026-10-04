package com.rainax.ytdownloader

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Watch while downloading. A download in progress plays from the pieces already on the phone:
 * - rxpart://<task id>          the whole download (one file, or picture + sound played together)
 * - rxpart://<task id>/<role>   one part ("media", "video" or "audio")
 * When the player reaches a piece that isn't here yet it waits for it, and the downloader fetches that piece next.
 */
object PartialPlayback {
    const val SCHEME = "rxpart"

    fun uri(taskId: String): String = "$SCHEME://$taskId"

    /** True when the download has found its streams, so playing can start. */
    fun ready(taskId: String) = PartialFiles.layout(taskId) != null
}

/** Reads a part of a running download, waiting for pieces that are still on their way. */
@OptIn(UnstableApi::class)
class PartialDataSource : BaseDataSource(false) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = PartialDataSource()
    }

    private var uri: Uri? = null
    private var taskId = ""
    private var entry: PartialFiles.Entry? = null
    private var role = "media"
    private var file: File? = null
    private var raf: RandomAccessFile? = null
    private var pos = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        taskId = dataSpec.uri.host ?: throw IOException("No download")
        role = dataSpec.uri.lastPathSegment ?: "media"
        transferInitializing(dataSpec)
        // the part's file and its piece list appear when that part starts downloading
        // (picture + sound: the picture starts after the sound, so this can take a while)
        var found: Pair<File, PartialFiles.Entry>? = null
        while (found == null) {
            checkStillDownloading()
            found = PartialFiles.roleFile(taskId, role)?.let { f -> PartialFiles.entry(f)?.let { f to it } }
            if (found == null) pause()
        }
        val (f, e) = found ?: throw IOException("No download")
        if (!f.exists()) throw IOException(RESTARTED)
        entry = e
        file = f
        raf = RandomAccessFile(f, "r")
        pos = dataSpec.position
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            e.total > 0 -> e.total - pos
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val e = entry ?: throw IOException("Not open")
        var avail = e.available(pos)
        while (avail <= 0) {
            if (e.complete) return C.RESULT_END_OF_INPUT          // one-connection download reached its end
            checkStillDownloading()
            // the download restarted or switched streams: open again to follow the new pieces
            val now = PartialFiles.roleFile(taskId, role)
            if (now?.path != file?.path || now?.let { PartialFiles.entry(it) } !== e) throw IOException(RESTARTED)
            e.pieceOf(pos).takeIf { it >= 0 }?.let { e.want = it }  // fetch this piece next
            pause()
            avail = e.available(pos)
        }
        var n = minOf(length.toLong(), avail)
        if (remaining != C.LENGTH_UNSET.toLong()) n = minOf(n, remaining)
        val r = raf ?: throw IOException("Not open")
        r.seek(pos)
        val got = r.read(buffer, offset, n.toInt())
        if (got < 0) return C.RESULT_END_OF_INPUT
        pos += got
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= got
        bytesTransferred(got)
        return got
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { raf?.close() }
        raf = null
        entry = null
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** Paused, failed or cancelled downloads can't feed the player any more. */
    private fun checkStillDownloading() {
        val t = TaskRepository.get(taskId) ?: throw IOException("This download was removed")
        when (t.status) {
            Status.PAUSED -> throw IOException("The download is paused. Resume it to keep watching")
            Status.FAILED -> throw IOException("The download stopped. Retry it to keep watching")
            // saved: its working files may be gone, the player switches to the saved file
            Status.DONE -> if (PartialFiles.layout(taskId) == null) throw IOException(FINISHED)
            else -> {}
        }
    }

    private fun pause() {
        try {
            Thread.sleep(WAIT_MS.toLong())
        } catch (ie: InterruptedException) {
            throw InterruptedIOException("Stopped")
        }
    }

    companion object {
        private const val WAIT_MS = 60
        /** Error texts the player reacts to. */
        const val RESTARTED = "rx:restarted"
        const val FINISHED = "rx:finished"
    }
}

/** The player's media factory: rxpart:// downloads in progress, everything else as usual. */
@OptIn(UnstableApi::class)
class PartialMediaSourceFactory(context: Context) : MediaSource.Factory {

    private val normal = DefaultMediaSourceFactory(context)
    private val partial = ProgressiveMediaSource.Factory(PartialDataSource.Factory())

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        normal.setDrmSessionManagerProvider(provider)
        partial.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        normal.setLoadErrorHandlingPolicy(policy)
        partial.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = normal.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: return normal.createMediaSource(mediaItem)
        if (uri.scheme != PartialPlayback.SCHEME) return normal.createMediaSource(mediaItem)
        val id = uri.host.orEmpty()
        fun part(role: String) = partial.createMediaSource(
            mediaItem.buildUpon().setUri("${PartialPlayback.SCHEME}://$id/$role").build()
        )
        return if (PartialFiles.layout(id) == "av") MergingMediaSource(part("video"), part("audio"))
        else part("media")
    }
}
