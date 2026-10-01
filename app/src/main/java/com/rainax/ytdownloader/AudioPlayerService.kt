package com.rainax.ytdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * "Background play": the very same YouTube video keeps playing in the app's browser when you press Home or turn the
 * screen off. This service keeps the app alive (and the phone awake) and shows play/pause/stop in the notification.
 * The video itself is controlled through [controller], which MainActivity sets.
 */
class AudioPlayerService : Service() {

    private var title = "RAINAX"
    private var playing = true
    private var wake: PowerManager.WakeLock? = null
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH, "Background play", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> {
                intent.getStringExtra(EXTRA_TITLE)?.let { title = it }
                playing = intent.getBooleanExtra(EXTRA_PLAYING, true)
                state.value = if (playing) PLAYING else PAUSED
                acquireWake()
                promote()
            }
            ACTION_TOGGLE -> controller?.invoke("toggle")
            ACTION_STOP -> {
                if (controller != null) controller?.invoke("stop") else stopAll()
            }
            else -> if (state.value == IDLE) stopAll()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopAll()
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rainax:bgplay").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun stopAll() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        state.value = IDLE
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
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

    private fun promote() {
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(if (playing) "Playing in background" else "Paused")
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Pause", ACTION_TOGGLE, 1)
                else action(R.drawable.ic_play, "Play", ACTION_TOGGLE, 1)
            )
            .addAction(action(R.drawable.ic_close, "Stop", ACTION_STOP, 2))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1))
        startForeground(NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    companion object {
        const val IDLE = 0
        const val PLAYING = 2
        const val PAUSED = 3

        const val ACTION_START = "com.rainax.ytdownloader.BG_START"
        const val ACTION_UPDATE = "com.rainax.ytdownloader.BG_UPDATE"
        const val ACTION_TOGGLE = "com.rainax.ytdownloader.BG_TOGGLE"
        const val ACTION_STOP = "com.rainax.ytdownloader.BG_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYING = "playing"

        private const val CH = "bgplay"
        private const val NOTIF_ID = 1101

        val state: MutableStateFlow<Int> = MutableStateFlow(IDLE)

        /** Set by MainActivity: receives "toggle" and "stop" from the notification buttons. */
        @Volatile var controller: ((String) -> Unit)? = null

        private fun send(context: Context, action: String, title: String? = null, playing: Boolean = true) {
            val i = Intent(context, AudioPlayerService::class.java).setAction(action)
            if (title != null) i.putExtra(EXTRA_TITLE, title)
            i.putExtra(EXTRA_PLAYING, playing)
            try { ContextCompat.startForegroundService(context, i) } catch (e: Exception) { }
        }

        fun start(context: Context, title: String, playing: Boolean) = send(context, ACTION_START, title, playing)
        fun update(context: Context, title: String, playing: Boolean) = send(context, ACTION_UPDATE, title, playing)

        fun stop(context: Context) {
            controller = null
            try { context.stopService(Intent(context, AudioPlayerService::class.java)) } catch (e: Exception) { }
            state.value = IDLE
        }
    }
}
