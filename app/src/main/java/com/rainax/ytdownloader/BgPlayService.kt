package com.rainax.ytdownloader

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Background play (like YouTube Premium): the video's sound keeps playing with the screen off or in other apps.
 * Android's standard media session gives the notification, lock-screen and headphone-button controls.
 */
@OptIn(UnstableApi::class)
class BgPlayService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // YouTube links want the User-Agent of the client they were made for (as NewPipe does)
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val withUa = ResolvingDataSource.Factory(http) { original ->
            // Next tracks are queued as "rainax://play?u=<video page>": find their sound only when needed
            var spec = original
            if (spec.uri.scheme == "rainax") {
                val page = spec.uri.getQueryParameter("u").orEmpty()
                val audio = resolved[page] ?: try {
                    FastExtractor.audioUrl(page).also { resolved[page] = it }
                } catch (e: Exception) {
                    throw java.io.IOException(e.message, e)     // the player skips/report it instead of crashing
                }
                spec = spec.withUri(android.net.Uri.parse(audio))
            }
            val url = spec.uri.toString()
            val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null)
            else FastExtractor.UA
            spec.withAdditionalHeaders(mapOf("User-Agent" to ua))
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(withUa))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true                                   // pause for calls and other apps' sound
            )
            .setHandleAudioBecomingNoisy(true)         // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)          // keep playing with the screen off
            .build()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // Tracks arrive from the app's screen: make sure each keeps its sound address
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<androidx.media3.common.MediaItem>
                ): com.google.common.util.concurrent.ListenableFuture<MutableList<androidx.media3.common.MediaItem>> {
                    val fixed = mediaItems.map { item ->
                        if (item.localConfiguration != null) item
                        else {
                            val uri = item.requestMetadata.mediaUri ?: lazyUri(item.mediaId)
                            item.buildUpon().setUri(uri).build()
                        }
                    }.toMutableList()
                    return com.google.common.util.concurrent.Futures.immediateFuture(fixed)
                }
            })
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_download) }
        )
    }

    companion object {
        /** Video page -> sound address, so a track is looked up once (addresses stay valid for hours). */
        private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** A queue entry whose sound is found when the player reaches it. */
        fun lazyUri(page: String): android.net.Uri =
            android.net.Uri.parse("rainax://play?u=" + android.net.Uri.encode(page))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recent apps while paused: stop completely. While playing it keeps going. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
