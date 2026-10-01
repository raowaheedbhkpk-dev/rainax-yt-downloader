package com.rainax.ytdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Background play: the video you are watching continues as audio when the app is hidden or the screen is off.
 * The video keeps playing in the page until the audio is ready, then hands over at the same second.
 */
class AudioPlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var player: MediaPlayer? = null
    private var title = "RAINAX"
    private var wake: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hadFocusLoss = false
    private var startMs = 0L
    private var pendingStart = false
    private var noisyRegistered = false
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS -> {
                if (player?.isPlaying == true) { hadFocusLoss = true; pausePlayback() }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (hadFocusLoss) { hadFocusLoss = false; resumePlayback() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH, "Background play", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                startMs = intent.getLongExtra(EXTRA_START, 0L)
                intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
                intent.getStringExtra(EXTRA_URL)?.let { load(it) }
            }
            ACTION_HANDOFF -> begin(intent.getLongExtra(EXTRA_START, startMs))
            ACTION_TOGGLE -> if (player?.isPlaying == true) pausePlayback() else resumePlayback()
            ACTION_STOP -> {
                val c = controller
                if (c != null) c("stop") else stopAll()
            }
            else -> if (state.value == IDLE) stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAll()
        super.onTaskRemoved(rootIntent)
    }

    private fun load(url: String) {
        releasePlayer()
        loadJob?.cancel()
        state.value = LOADING
        promote(false, "Getting the audio ready…")
        loadJob = scope.launch {
            try {
                val d = StreamResolver.request(this@AudioPlayerService, url) ?: error("Unsupported link")
                val r = d.await()
                title = r.title
                prepare(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(friendlyError(e.message.orEmpty()).take(120))
            }
        }
    }

    private fun prepare(r: ResolvedAudio) {
        val mp = MediaPlayer()
        player = mp
        current = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setDataSource(this, Uri.parse(r.url), r.headers)
        mp.setOnPreparedListener {
            val c = controller
            if (c != null) c("ready") else begin(startMs)      // the page tells us the exact second, then pauses
        }
        mp.setOnSeekCompleteListener {
            if (pendingStart) {
                pendingStart = false
                startNow()
            }
        }
        mp.setOnCompletionListener { stopAll() }
        mp.setOnErrorListener { _, _, _ -> fail("Can't play this audio. Try again."); true }
        mp.prepareAsync()
    }

    private fun begin(ms: Long) {
        val mp = player ?: return
        if (state.value == PLAYING) return
        if (ms > 1000) {
            pendingStart = true
            try { mp.seekTo(ms.toInt()) } catch (e: Exception) { pendingStart = false; startNow() }
        } else startNow()
    }

    private fun startNow() {
        val mp = player ?: return
        if (!requestFocus()) { fail("Another app is using the audio"); return }
        try { mp.start() } catch (e: Exception) { fail("Can't play this audio. Try again."); return }
        state.value = PLAYING
        acquireWake()
        registerNoisy()
        promote(true, "Playing in background")
    }

    private fun pausePlayback() {
        val p = player ?: return
        try { if (p.isPlaying) p.pause() } catch (e: Exception) { }
        state.value = PAUSED
        promote(true, "Paused")
    }

    private fun resumePlayback() {
        val p = player ?: return
        if (state.value == LOADING) return
        if (requestFocus()) {
            try { p.start() } catch (e: Exception) { return }
            state.value = PLAYING
            promote(true, "Playing in background")
        }
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rainax:audio").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyRegistered = true
    }

    private fun fail(message: String) {
        releasePlayer()
        state.value = IDLE
        controller?.invoke("failed")
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Background play stopped")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(ERR_ID, n)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopAll() {
        loadJob?.cancel()
        releasePlayer()
        state.value = IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        try { player?.let { lastPositionMs = it.currentPosition.toLong() } } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        current = null
        pendingStart = false
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        hadFocusLoss = false
        if (noisyRegistered) {
            try { unregisterReceiver(noisy) } catch (e: Exception) { }
            noisyRegistered = false
        }
    }

    override fun onDestroy() {
        releasePlayer()
        scope.cancel()
        state.value = IDLE
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, label: String, act: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code, Intent(this, AudioPlayerService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
    }

    private fun promote(controls: Boolean, text: String) {
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (controls) {
            b.addAction(
                if (state.value == PLAYING) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 1)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE, 1)
            )
        }
        b.addAction(action(R.drawable.ic_close, "Stop", ACTION_STOP, 2))
        b.setStyle(
            if (controls) Notification.MediaStyle().setShowActionsInCompactView(0, 1)
            else Notification.MediaStyle().setShowActionsInCompactView(0)
        )
        startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    companion object {
        const val IDLE = 0
        const val LOADING = 1
        const val PLAYING = 2
        const val PAUSED = 3

        const val ACTION_PLAY = "com.rainax.ytdownloader.AUDIO_PLAY"
        const val ACTION_HANDOFF = "com.rainax.ytdownloader.AUDIO_HANDOFF"
        const val ACTION_TOGGLE = "com.rainax.ytdownloader.AUDIO_TOGGLE"
        const val ACTION_STOP = "com.rainax.ytdownloader.AUDIO_STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"

        private const val CH = "audio"
        private const val NOTIF_ID = 1101
        private const val ERR_ID = 1102

        val state: MutableStateFlow<Int> = MutableStateFlow(IDLE)

        /** Where the audio stopped (ms), so the video can continue from there. */
        @Volatile var lastPositionMs = 0L
        @Volatile private var current: MediaPlayer? = null

        /** Set by MainActivity. Events: "ready" (audio can take over now), "stop", "failed". */
        @Volatile var controller: ((String) -> Unit)? = null

        private fun send(context: Context, action: String, fill: (Intent) -> Unit = {}) {
            val i = Intent(context, AudioPlayerService::class.java).setAction(action)
            fill(i)
            try { ContextCompat.startForegroundService(context, i) } catch (e: Exception) { }
        }

        fun play(context: Context, url: String, title: String, startMs: Long) = send(context, ACTION_PLAY) {
            it.putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_START, startMs)
        }

        fun handoff(context: Context, ms: Long) = send(context, ACTION_HANDOFF) { it.putExtra(EXTRA_START, ms) }

        fun stop(context: Context) {
            controller = null
            lastPositionMs = try { current?.currentPosition?.toLong() ?: 0L } catch (e: Exception) { 0L }
            try { context.stopService(Intent(context, AudioPlayerService::class.java)) } catch (e: Exception) { }
            state.value = IDLE
        }
    }
}
