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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticking = false
    private var ticks = 0
    private val fetching = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Every half second while playing: skip SponsorBlock parts, and remember where you are (Continue watching). */
    private val tick = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            if (!p.isPlaying) { ticking = false; return }
            // Music player's sleep timer
            val sleepAt = MusicQueue.sleepAt
            val now = System.currentTimeMillis()
            if (sleepAt in 1..now) {
                MusicQueue.sleepAt = 0
                // (the time passed while paused: playing again was asked for, so keep playing)
                if (now - sleepAt > 3000) { onTick(p); handler.postDelayed(this, 500); return }
                p.pause()
                Toast.makeText(this@BgPlayService, "Sleep timer: music paused", Toast.LENGTH_SHORT).show()
                ticking = false
                return
            }
            onTick(p)
            handler.postDelayed(this, 500)
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tick, 500)
    }

    private fun saveProgress(p: Player, force: Boolean) {
        val item = p.currentMediaItem ?: return
        val page = item.mediaId
        if (youtubeId(page) == null || p.isCurrentMediaItemLive) return
        if (MusicPlayer.isMusic(item)) return                       // songs don't go to Continue watching
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val md = item.mediaMetadata
        WatchHistory.save(
            this, page, md.title?.toString().orEmpty(), md.artist?.toString().orEmpty(),
            md.artworkUri?.toString(), p.currentPosition.coerceAtLeast(0), dur, force
        )
    }

    private fun onTick(p: Player) {
        val page = p.currentMediaItem?.mediaId ?: return
        val id = youtubeId(page) ?: return
        if (++ticks % 10 == 0) saveProgress(p, false)                 // every 5 seconds
        if (!AppPrefs.sponsorBlock(this) || p.isCurrentMediaItemLive) return
        val segs = SponsorBlock.cached(id)
        if (segs == null) {
            if (fetching.add(id)) Thread { SponsorBlock.segments(id); fetching.remove(id) }.start()
            return
        }
        val pos = p.currentPosition
        val s = segs.firstOrNull { pos >= it.startMs && pos < it.endMs - 400 } ?: return
        val dur = p.duration
        p.seekTo(if (dur > 0 && dur != C.TIME_UNSET) minOf(s.endMs, dur) else s.endMs)
        Toast.makeText(this, "Skipped " + SponsorBlock.label(s.category), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(RxMediaSourceFactory())     // video (picture + sound), live, and sound-only tracks
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true                                   // pause for calls and other apps' sound
            )
            .setHandleAudioBecomingNoisy(true)         // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)          // keep playing with the screen off
            // start after 1 second of video is ready (instead of 2.5): videos begin almost at once
            .setLoadControl(
                androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
                    .build()
            )
            .build()
        player.addListener(object : Player.Listener {
            private var retriedFor: String? = null
            private var skips = 0                         // tracks skipped in a row because they failed

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem?.mediaId != retriedFor) retriedFor = null
                // Music player: sleep timer "end of this song", and similar songs before the list runs out
                if (MusicQueue.sleepEndOfSong && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
                ) {
                    MusicQueue.sleepEndOfSong = false
                    player.pause()
                    Toast.makeText(this@BgPlayService, "Sleep timer: music paused", Toast.LENGTH_SHORT).show()
                }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) MusicQueue.topUpIfNeeded(player)
                // look up the parts to skip of the next video early
                val id = youtubeId(mediaItem?.mediaId)
                if (id != null && AppPrefs.sponsorBlock(this@BgPlayService) && SponsorBlock.cached(id) == null && fetching.add(id)) {
                    Thread { SponsorBlock.segments(id); fetching.remove(id) }.start()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicking() else saveProgress(player, true)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) { retriedFor = null; skips = 0 }   // playing fine again
                if (state == Player.STATE_ENDED) MusicQueue.sleepEndOfSong = false  // nothing left to stop after
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = player.currentMediaItem
                val page = item?.mediaId
                // no internet: wait (pause) instead of skipping through the whole queue
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                ) {
                    player.pause()
                    Toast.makeText(this@BgPlayService, "No internet. Press play when you're back online", Toast.LENGTH_LONG).show()
                    return
                }
                // 1) try once more with fresh addresses (YouTube's links expire after some hours)
                if (item != null && page != null && retriedFor != page) {
                    retriedFor = page
                    resolved.remove(page)
                    FastExtractor.forget(page)
                    val uri = item.localConfiguration?.uri
                    val lazy = uri?.scheme == "rainax" && uri.authority == "play"
                    if (!lazy && FastExtractor.supports(page)) {
                        // picture + sound addresses expired: keep going with fresh sound right away
                        // (the app puts the picture back when it is open)
                        val pos = player.currentPosition
                        // (a Music player song stays a Music player song, now sound only)
                        val keep = android.os.Bundle().apply {
                            if (MusicPlayer.isMusic(item)) {
                                putAll(item.mediaMetadata.extras ?: android.os.Bundle())
                                putBoolean(MusicPlayer.EXTRA_PICTURE, false)
                            }
                        }
                        val fresh = item.buildUpon().setUri(lazyUri(page))
                            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(keep).build())
                            .build()
                        player.replaceMediaItem(player.currentMediaItemIndex, fresh)
                        player.seekTo(pos)
                    }
                    player.prepare()
                    player.play()
                    return
                }
                // 2) still failing: move on to the next track (a few times at most)
                if (player.hasNextMediaItem() && skips < 3) {
                    skips++
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

        MusicQueue.player = player

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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
                            // only addresses the app makes itself (https streams or rainax://)
                            val uri = item.requestMetadata.mediaUri?.takeIf { it.scheme == "https" || it.scheme == "http" || it.scheme == "rainax" }
                                ?: lazyUri(item.mediaId)
                            item.buildUpon().setUri(uri).build()
                        }
                    }.toMutableList()
                    return com.google.common.util.concurrent.Futures.immediateFuture(fixed)
                }
            })
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(PLAYER_NOTIFICATION_ID)     // own number: downloads use 1001 (Media3's default) and replaced it
                .setChannelId("rainax_player")
                .setChannelName(R.string.player_channel)
                .build().apply { setSmallIcon(R.drawable.ic_stat_download) }
        )
    }

    companion object {
        private const val PLAYER_NOTIFICATION_ID = 2001

        /** Video page -> sound address, so a track is looked up once (addresses stay valid for hours). */
        private val resolved = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
        private const val VALID_MS = 4 * 60 * 60 * 1000L           // YouTube's addresses last about 6 hours

        /** The app already found this video's sound: no need to look it up again. */
        fun remember(page: String, audio: String) {
            if (resolved.size > 200) resolved.clear()
            resolved[page] = audio to System.currentTimeMillis()
        }

        /** Blocking (player thread). The sound address of a video page; [fresh] skips the saved one. */
        fun audioFor(page: String, fresh: Boolean): String {
            if (!fresh) {
                resolved[page]?.let { (url, at) -> if (System.currentTimeMillis() - at < VALID_MS) return url }
            } else FastExtractor.forget(page)
            return try {
                FastExtractor.audioUrl(page).also { remember(page, it) }
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
        handler.removeCallbacks(tick)
        if (MusicQueue.player === session?.player) {
            MusicQueue.reset()
            MusicQueue.player = null
        }
        session?.player?.let { saveProgress(it, true) }
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
