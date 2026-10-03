package com.rainax.ytdownloader

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * How the player opens each queue entry:
 * - rainax://av?v=<picture>&a=<sound>  YouTube keeps picture and sound apart: both are streamed and played together
 * - rainax://hls?h=<playlist>          live streams
 * - rainax://play?u=<video page>       sound only, looked up when reached ("Up next" in the background)
 * - anything else                      a normal stream address
 * The entry keeps its title, picture and id (the notification and the app use them).
 */
@OptIn(UnstableApi::class)
class RxMediaSourceFactory : MediaSource.Factory {

    private val chunked = YtChunkDataSource.Factory()
    private val default = DefaultMediaSourceFactory(chunked)
    private val progressive = ProgressiveMediaSource.Factory(chunked)
    private val hls = HlsMediaSource.Factory(
        DefaultHttpDataSource.Factory()
            .setUserAgent(FastExtractor.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
    )

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: return default.createMediaSource(mediaItem)
        if (uri.scheme == "rainax") {
            when (uri.authority) {
                "av" -> {
                    val v = uri.getQueryParameter("v")
                    val a = uri.getQueryParameter("a")
                    if (v != null && a != null) {
                        val picture = progressive.createMediaSource(mediaItem.buildUpon().setUri(Uri.parse(v)).build())
                        val sound = progressive.createMediaSource(MediaItem.fromUri(Uri.parse(a)))
                        return MergingMediaSource(picture, sound)
                    }
                }
                "hls" -> uri.getQueryParameter("h")?.let { h ->
                    return hls.createMediaSource(
                        mediaItem.buildUpon().setUri(Uri.parse(h)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                    )
                }
            }
        }
        return default.createMediaSource(mediaItem)
    }
}
