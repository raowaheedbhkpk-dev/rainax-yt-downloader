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
 * - rainax://dash?u=<video page>       Auto quality: the player moves between qualities with the network
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

    private val dash = androidx.media3.exoplayer.dash.DashMediaSource.Factory(chunked)

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        default.setDrmSessionManagerProvider(drmSessionManagerProvider)
        dash.setDrmSessionManagerProvider(drmSessionManagerProvider)
        progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
        hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        default.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        dash.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH)

    companion object {
        /** Auto-quality manifests by video page (rainax://dash?u=<page>). */
        private val manifests = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun putManifest(page: String, mpd: String) {
            if (manifests.size > 30) manifests.clear()
            manifests[page] = mpd
        }
    }

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
                "dash" -> {
                    val page = uri.getQueryParameter("u")
                    val mpd = page?.let { manifests[it] }
                    val manifest = mpd?.let {
                        runCatching {
                            androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(Uri.parse(page), it.byteInputStream())
                        }.getOrNull()
                    }
                    if (manifest != null) return dash.createMediaSource(manifest, mediaItem)
                    // manifest gone (app restarted): play the sound of this video
                    if (page != null) return default.createMediaSource(mediaItem.buildUpon().setUri(BgPlayService.lazyUri(page)).build())
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
