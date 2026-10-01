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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Plays the audio of a video in the background, like a music app: keeps playing with the screen off
 * and while you use other apps. Controls live in the notification.
 */
class AudioPlayerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loadJob: Job? = null
    private var player: MediaPlayer? = null
    private var title = "RAINAX"
    private var wake: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hadFocusLoss = false
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var noisyRegistered = false

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
        nm.createNotificationChannel(
            NotificationChannel(CH, "Background audio", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> intent.getStringExtra(EXTRA_URL)?.let { startLoading(it) }
            ACTION_TOGGLE -> if (player?.isPlaying == true) pausePlayback() else resumePlayback()
            ACTION_STOP -> stopAll()
            else -> if (player == null && loadJob?.isActive != true) stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startLoading(url: String) {
        releasePlayer()
        loadJob?.cancel()
        try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) } catch (e: Exception) { }
        title = "Loading audio…"
        currentUrl.value = url
        state.value = LOADING
        promote(false)
        loadJob = scope.launch {
            try {
                val (streamUrl, name, headers) = resolve(url)
                title = name
                prepare(streamUrl, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(friendlyError(e.message.orEmpty()).take(120))
            }
        }
    }

    private fun resolve(url: String): Triple<String, String, Map<String, String>> {
        Engine.ensureInit(this)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
        }
        val out = YoutubeDL.getInstance().execute(request, PROCESS_ID, null).out
        val start = out.indexOf('{')
        if (start < 0) error("No data returned for this link")
        val json = JSONObject(out.substring(start))
        var stream = json.optString("url").takeIf { it.startsWith("http") }
        var headerSrc = json.optJSONObject("http_headers")
        if (stream == null) {
            val rf = json.optJSONArray("requested_formats")
            val f = rf?.optJSONObject(0)
            stream = f?.optString("url")?.takeIf { it.startsWith("http") }
            headerSrc = f?.optJSONObject("http_headers") ?: headerSrc
        }
        if (stream == null) error("No playable audio found")
        val headers = mutableMapOf<String, String>()
        val hs = headerSrc
        if (hs != null) {
            val it = hs.keys()
            while (it.hasNext()) {
                val k = it.next()
                headers[k] = hs.optString(k)
            }
        }
        val name = json.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "Audio"
        return Triple(stream, name, headers)
    }

    private fun prepare(streamUrl: String, headers: Map<String, String>) {
        val mp = MediaPlayer()
        player = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
        mp.setDataSource(this, Uri.parse(streamUrl), headers)
        mp.setOnPreparedListener {
            if (requestFocus()) {
                it.start()
                state.value = PLAYING
                acquireWake()
                registerNoisy()
                promote(true)
            } else fail("Another app is using the audio")
        }
        mp.setOnCompletionListener { stopAll() }
        mp.setOnErrorListener { _, _, _ -> fail("Can't play this audio. Try again."); true }
        mp.prepareAsync()
    }

    private fun pausePlayback() {
        val p = player ?: return
        try { if (p.isPlaying) p.pause() } catch (e: Exception) { }
        state.value = PAUSED
        promote(true)
    }

    private fun resumePlayback() {
        val p = player ?: return
        if (state.value == LOADING) return
        if (requestFocus()) {
            try { p.start() } catch (e: Exception) { return }
            state.value = PLAYING
            promote(true)
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
            acquire(4 * 60 * 60 * 1000L)
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
        currentUrl.value = null
        val n = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Audio stopped")
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
        try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) } catch (e: Exception) { }
        releasePlayer()
        state.value = IDLE
        currentUrl.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (e: Exception) { }
        player = null
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
        currentUrl.value = null
        super.onDestroy()
    }

    // ---------- notification ----------

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

    private fun promote(controls: Boolean) {
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(
                when (state.value) {
                    LOADING -> "Getting the audio ready…"
                    PAUSED -> "Paused"
                    else -> "Playing in background"
                }
            )
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (controls) {
            val playing = state.value == PLAYING
            b.addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 1)
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
        const val ACTION_TOGGLE = "com.rainax.ytdownloader.AUDIO_TOGGLE"
        const val ACTION_STOP = "com.rainax.ytdownloader.AUDIO_STOP"
        const val EXTRA_URL = "url"

        private const val CH = "audio"
        private const val NOTIF_ID = 1101
        private const val ERR_ID = 1102
        private const val PROCESS_ID = "bg-audio"

        val state: MutableStateFlow<Int> = MutableStateFlow(IDLE)
        val currentUrl: MutableStateFlow<String?> = MutableStateFlow(null)
        val stateFlow: StateFlow<Int> get() = state

        private fun start(context: Context, action: String, url: String? = null) {
            val i = Intent(context, AudioPlayerService::class.java).setAction(action)
            if (url != null) i.putExtra(EXTRA_URL, url)
            try { ContextCompat.startForegroundService(context, i) } catch (e: Exception) { }
        }

        fun play(context: Context, url: String) = start(context, ACTION_PLAY, url)
        fun toggle(context: Context) = start(context, ACTION_TOGGLE)
        fun stop(context: Context) = start(context, ACTION_STOP)
    }
}
