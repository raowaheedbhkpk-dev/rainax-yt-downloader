package com.rainax.ytdownloader

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background play for downloaded videos (the full-screen player). While you watch, the player screen plays the
 * file itself; when you lock the phone or leave the app with Background play on, the same list carries on
 * here as sound, from the same second, with the lock-screen and notification controls. Coming back to the
 * player takes it over again at the point it reached. Separate from the streaming player (BgPlayService), so
 * the two never get in each other's way.
 */
@OptIn(UnstableApi::class)
class LocalPlayService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(PartialMediaSourceFactory(this))     // also files still downloading
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)                          // headphones out: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)                             // keeps going with the screen off
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        // sound only while in the background: saves battery
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        val open = PendingIntent.getActivity(
            this, 0,
            PlayerActivity.sign(this, Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_FROM_BACKGROUND, true))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setId("rainax_local")
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                // the list arrives without its file addresses (they travel in requestMetadata): put them back
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri ?: Uri.parse(it.mediaId)).build() }.toMutableList()
                )
            })
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(2002)
                .setChannelId("rainax_local")
                .setChannelName(R.string.player_channel)
                .build().apply { setSmallIcon(R.drawable.ic_stat_logo) }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recent apps: keep playing if it plays, otherwise stop. */
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

/**
 * Hands the full-screen player's list to [LocalPlayService] and takes it back. Lives as long as the app's
 * process, so the player screen can be closed and opened again from the notification.
 */
object LocalHandoff {

    class State(val index: Int, val positionMs: Long, val playing: Boolean)

    @Volatile var active = false
        private set
    /** The list that went to the background (to reopen the player from the notification). */
    var uris: ArrayList<String> = arrayListOf()
        private set
    var titles: ArrayList<String> = arrayListOf()
        private set

    private var future: ListenableFuture<MediaController>? = null

    /** Starts playing [items] (from [index] at [positionMs]) in the background service. */
    fun start(context: Context, items: List<MediaItem>, uriList: List<String>, titleList: List<String>,
              index: Int, positionMs: Long, speed: Float, repeat: Int, shuffle: Boolean) {
        stopNow()
        val app = context.applicationContext
        uris = ArrayList(uriList)
        titles = ArrayList(titleList)
        active = true
        val token = SessionToken(app, ComponentName(app, LocalPlayService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { active = false; return@addListener }
            if (future !== f) { c.release(); return@addListener }       // taken back before it connected
            val list = items.map { m ->
                val uri = m.localConfiguration?.uri ?: Uri.parse(m.mediaId)
                MediaItem.Builder()
                    .setMediaId(m.mediaId)
                    .setUri(uri)
                    .setMediaMetadata(m.mediaMetadata)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .build()
            }
            c.setMediaItems(list, index.coerceIn(0, (list.size - 1).coerceAtLeast(0)), positionMs.coerceAtLeast(0))
            c.setPlaybackSpeed(speed)
            c.repeatMode = repeat
            c.shuffleModeEnabled = shuffle
            c.prepare()
            c.play()
        }, androidx.core.content.ContextCompat.getMainExecutor(app))
    }

    /** Back in the player: where the background got to, and the background stops. Null when nothing was there. */
    fun takeBack(): State? {
        val f = future ?: run { active = false; return null }
        future = null
        active = false
        val c = if (f.isDone) runCatching { f.get() }.getOrNull() else { f.cancel(true); null }
        c ?: return null
        val state = State(c.currentMediaItemIndex, c.currentPosition, c.playWhenReady &&
            c.playbackState != Player.STATE_ENDED)
        c.stop()
        c.clearMediaItems()
        c.release()
        return state
    }

    /** Ends background play without taking it over. */
    fun stopNow() {
        takeBack()
    }
}
