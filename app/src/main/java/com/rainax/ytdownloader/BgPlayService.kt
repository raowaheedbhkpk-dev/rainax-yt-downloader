package com.rainax.ytdownloader

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Background play (like YouTube Premium): the video's sound keeps playing with the screen off or in other apps.
 * Android's standard media session gives the notification, lock-screen and headphone-button controls.
 */
@OptIn(UnstableApi::class)
class BgPlayService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(YtChunkDataSource.Factory()))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true                                   // pause for calls and other apps' sound
            )
            .setHandleAudioBecomingNoisy(true)         // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)          // keep playing with the screen off
            .build()
        player.addListener(object : Player.Listener {
            private var retriedFor: String? = null

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != retriedFor) retriedFor = null
            }

            override fun onPlayerError(error: PlaybackException) {
                val page = player.currentMediaItem?.mediaId
                // 1) try once more with a fresh sound address (they can expire)
                if (page != null && retriedFor != page) {
                    retriedFor = page
                    resolved.remove(page)
                    player.prepare()
                    player.play()
                    return
                }
                // 2) still failing: move on to the next track
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                    return
                }
                // 3) nothing left: say why, so the problem is visible
                Toast.makeText(
                    this@BgPlayService,
                    "Background play stopped (" + error.errorCodeName.removePrefix("ERROR_CODE_").lowercase() + ")",
                    Toast.LENGTH_LONG
                ).show()
            }
        })

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

        /** The app already found this video's sound: no need to look it up again. */
        fun remember(page: String, audio: String) {
            resolved[page] = audio
        }

        /** Blocking (player thread). The sound address of a video page; [fresh] skips the saved one. */
        fun audioFor(page: String, fresh: Boolean): String {
            if (!fresh) resolved[page]?.let { return it }
            return try {
                FastExtractor.audioUrl(page).also { resolved[page] = it }
            } catch (e: Exception) {
                throw java.io.IOException(e.message ?: "Can't find the sound of this video", e)
            }
        }

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
