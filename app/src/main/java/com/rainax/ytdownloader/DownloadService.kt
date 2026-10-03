package com.rainax.ytdownloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.IBinder
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns the download queue, so downloads continue when the app is closed.
 * - runs up to N downloads at once (Settings)
 * - resumes partial files (finished pieces are kept) after errors, restarts and network loss
 * - can wait for Wi-Fi only
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager

    @Volatile private var stopping = false
    private val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastStartId = 0
    private var failedStart = false
    private var callbackRegistered = false
    private var thumbJob: Job? = null
    private val thumbTried = ConcurrentHashMap.newKeySet<String>()


    // Auto-retry: how many times each failed download was re-tried, and when the next try is due
    private val autoTries = ConcurrentHashMap<String, Int>()
    private val retryAt = ConcurrentHashMap<String, Long>()
    @Volatile private var wentOffline = false

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wentOffline) {           // internet is back: waiting retries go now
                wentOffline = false
                retryAt.clear()
            }
            pump()
        }
        override fun onLost(network: Network) { wentOffline = true }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { pump() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TaskRepository.init(this)
        nm = getSystemService(NotificationManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        createChannels()
        if (!promote()) {           // Android refused the foreground start: stop cleanly instead of being killed
            failedStart = true
            stopSelf()
            return
        }
        cm.registerDefaultNetworkCallback(netCallback)
        callbackRegistered = true
        scope.launch {
            // StateFlow already keeps only the latest value; the delay throttles notification updates
            TaskRepository.tasks.collect {
                updateNotification()
                delay(700)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (failedStart) return START_NOT_STICKY
        // Same lock as pump(): the service can't decide to stop halfway through handling this command
        synchronized(this) {
            lastStartId = startId
            stopping = false
            promote()
            handleCommand(intent)
        }
        pump()
        return START_STICKY
    }

    private fun handleCommand(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID)
        val ids = intent?.getStringArrayExtra(EXTRA_IDS)
        when (intent?.action) {
            ACTION_PAUSE -> id?.let { pause(it) }
            ACTION_RESUME -> id?.let { resume(it) }
            ACTION_CANCEL -> id?.let { cancel(it) }
            ACTION_PAUSE_ALL -> pauseAll()
            ACTION_RESUME_ALL -> resumeAll()
            ACTION_PAUSE_SEL -> ids?.forEach { pause(it) }
            ACTION_RESUME_SEL -> ids?.forEach { resume(it) }
            ACTION_CANCEL_SEL -> ids?.let { cancelMany(it.toList()) }
            ACTION_CANCEL_ALL ->
                cancelMany(TaskRepository.tasks.value.filter { it.status != Status.DONE }.map { it.id })
        }
    }

    override fun onDestroy() {
        running.keys.forEach { NativeDownloader.stop(it) }
        try { nm.cancel(FG_ID) } catch (e: Exception) { }
        if (callbackRegistered) {
            try { cm.unregisterNetworkCallback(netCallback) } catch (e: Exception) { }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------- scheduler ----------

    @Synchronized
    private fun pump() {
        if (!scope.isActive) return           // service is shutting down
        fillThumbnails()
        // Wi-Fi only and the phone moved to mobile data: pause running downloads until Wi-Fi is back
        // (only when online on mobile data; a short network drop is handled by the piece retries)
        if (isOnline() && !canDownload() && running.isNotEmpty()) {
            running.keys.toList().forEach { id ->
                if (TaskRepository.get(id)?.status == Status.RUNNING) {
                    TaskRepository.update(id, true) { it.copy(status = Status.WAITING, message = "Waiting for Wi-Fi…") }
                    NativeDownloader.stop(id)
                }
            }
        }
        // Network came back (or Wi-Fi-only was switched off): waiting tasks rejoin the queue
        if (canDownload()) {
            val now = System.currentTimeMillis()
            TaskRepository.tasks.value.filter { it.status == Status.WAITING && (retryAt[it.id] ?: 0L) <= now }.forEach { t ->
                TaskRepository.update(t.id, true) { it.copy(status = Status.QUEUED, message = "Queued") }
            }
        }
        var slots = AppPrefs.maxParallel(this) - running.size
        for (t in TaskRepository.tasks.value.asReversed()) { // oldest first
            if (slots <= 0) break
            if (t.status == Status.QUEUED && !running.containsKey(t.id)) {
                if (start(t)) slots--
            }
        }
        val busy = TaskRepository.tasks.value.any {
            it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
        }
        if (!busy && running.isEmpty() && thumbJob?.isActive != true) {
            stopping = true
            doneCount.set(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)            // a newer start request keeps the service alive
        }
    }

    private fun start(task: DownloadTask): Boolean {
        if (!canDownload()) {
            val msg = if (!isOnline()) "Waiting for network…" else "Waiting for Wi-Fi…"
            TaskRepository.update(task.id, true) { it.copy(status = Status.WAITING, message = msg) }
            return false
        }
        TaskRepository.update(task.id, true) { it.copy(status = Status.RUNNING, message = "Starting…") }
        val job = scope.launch(start = CoroutineStart.LAZY) { runTask(task.id) }
        running[task.id] = job
        job.invokeOnCompletion {
            running.remove(task.id)
            pump()
        }
        job.start()
        return true
    }

    private suspend fun runTask(id: String) {
        var attempt = TaskRepository.get(id)?.retries ?: return
        while (true) {
            try {
                downloadOnce(id)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cur = TaskRepository.get(id) ?: return          // cancelled
                if (cur.status != Status.RUNNING) return            // paused
                val msg = e.readable()

                if (msg.lowercase().let { "private video" in it || "members-only" in it || "join this channel" in it }) {
                    TaskRepository.remove(id)                       // private videos are skipped, never listed
                    return
                }
                if (msg.isFatalError()) {
                    TaskRepository.update(id, true) { it.copy(status = Status.FAILED, message = friendlyError(msg)) }
                    notifyDone(cur, false)
                    return
                }
                if (!canDownload()) {
                    val why = if (!isOnline()) "Waiting for network…" else "Waiting for Wi-Fi…"
                    TaskRepository.update(id, true) { it.copy(status = Status.WAITING, message = why) }
                    return
                }
                if (attempt >= MAX_RETRIES) {
                    if (scheduleAutoRetry(id)) return
                    TaskRepository.update(id, true) { it.copy(status = Status.FAILED, message = friendlyError(msg)) }
                    notifyDone(cur, false)
                    return
                }
                attempt++
                TaskRepository.update(id, true) {
                    it.copy(retries = attempt, message = "Connection problem. Retrying $attempt/$MAX_RETRIES…")
                }
                delay(3_000L * attempt)
                if (TaskRepository.get(id)?.status != Status.RUNNING) return
            }
        }
    }

    /** Failed after all quick retries: try again by itself after 1, 3 and 10 minutes. */
    private fun scheduleAutoRetry(id: String): Boolean {
        if (!AppPrefs.autoRetry(this)) return false
        val n = autoTries[id] ?: 0
        if (n >= AUTO_DELAYS.size) return false
        val wait = AUTO_DELAYS[n]
        autoTries[id] = n + 1
        val at = System.currentTimeMillis() + wait
        retryAt[id] = at
        val min = wait / 60_000
        TaskRepository.update(id, true) {
            it.copy(
                status = Status.WAITING, retries = 0,
                message = "Failed. Trying again by itself in $min min (${n + 1}/${AUTO_DELAYS.size})"
            )
        }
        scope.launch {
            delay(wait)
            retryAt.remove(id, at)              // only our own timer (a newer one may be set)
            pump()
        }
        return true
    }

    private fun downloadOnce(id: String) {
        val task = TaskRepository.get(id) ?: return
        NativeDownloader.begin(id)
        try {
            transfer(id, task)
        } finally {
            NativeDownloader.end(id)
        }
    }

    /** One attempt: find the streams, download them (several connections each), join, save. */
    private fun transfer(id: String, task: DownloadTask) {
        // Fresh stream addresses on every attempt (YouTube's links expire after some hours)
        val plan = FastExtractor.plan(task.url, task.format, task.subLang)
        if (task.title.isBlank()) {
            val thumbPath = plan.thumbUrl?.let { InfoFetcher.loadBitmap(it) }?.let { InfoFetcher.saveThumb(this, id, it) }
            TaskRepository.update(id, true) { it.copy(title = plan.title, thumbPath = thumbPath ?: it.thumbPath) }
        }
        // Paused or cancelled meanwhile? Don't start the transfer.
        if (TaskRepository.get(id)?.status != Status.RUNNING) return

        // Same folder on every attempt, so finished pieces are kept and the download continues
        val dir = File(filesDir, "downloads/$id").apply { mkdirs() }
        val title = TaskRepository.get(id)?.title?.ifBlank { plan.title } ?: plan.title

        val parts = listOfNotNull(plan.single, plan.video, plan.audio)
        val total = parts.sumOf { it.size }.coerceAtLeast(1L)
        val done = java.util.concurrent.atomic.AtomicLong(0)
        var best = task.progress.coerceAtMost(98)    // the bar never goes backwards, even after a retry
        var lastUi = -1L
        var lastBytes = 0L
        var lastTime = System.currentTimeMillis()
        var speed = 0.0

        val uiLock = Any()
        var label = ""

        fun report() = synchronized(uiLock) {
            val now = System.currentTimeMillis()
            if (lastUi < 0) {            // first call: start measuring speed from here
                lastBytes = done.get(); lastTime = now; lastUi = now
                return@synchronized
            }
            if (now - lastUi < 500) return@synchronized
            val bytes = done.get()
            val dt = (now - lastTime).coerceAtLeast(1)
            speed = speed * 0.6 + ((bytes - lastBytes) * 1000.0 / dt) * 0.4
            lastBytes = bytes
            lastTime = now
            lastUi = now
            val pct = (bytes * 100 / total).toInt().coerceIn(0, 99)
            if (pct > best) best = pct
            val eta = if (speed > 1) ((total - bytes) / speed).toLong() else -1
            val text = buildString {
                append("Downloading $best%")
                if (label.isNotEmpty()) append("  •  ").append(label)
                if (speed > 1) append("  •  ").append(formatSize(speed.toLong())).append("/s")
                if (eta in 0..86_400) append("  •  ETA ").append(formatEta(eta))
            }
            TaskRepository.update(id) {
                if (it.status != Status.RUNNING) it else it.copy(progress = best, message = text)
            }
        }

        val onBytes: (Long) -> Unit = { n -> done.addAndGet(n); report() }
        // bytes already on disk from an earlier try: count them, but not as speed
        val onResumed: (Long) -> Unit = { n -> synchronized(uiLock) { done.addAndGet(n); lastBytes += n } }

        val output: File
        val s = plan.single
        if (s != null) {
            label = ""
            val f = File(dir, "media.${s.ext}")
            NativeDownloader.download(id, s.url, f, onResumed, onBytes)
            output = f
        } else {
            val v = plan.video!!
            val a = plan.audio!!
            val vf = File(dir, "video.${v.ext}")
            val af = File(dir, "audio.${a.ext}")
            label = "video"
            NativeDownloader.download(id, v.url, vf, onResumed, onBytes)
            label = "sound"
            NativeDownloader.download(id, a.url, af, onResumed, onBytes)
            if (TaskRepository.get(id)?.status != Status.RUNNING) return
            TaskRepository.update(id) { it.copy(progress = 99, message = "Joining video and sound…") }
            val joined = File(dir, if (plan.webm) "joined.webm" else "joined.mp4")
            try {
                NativeDownloader.mux(id, vf, af, joined, plan.webm)
            } catch (e: NativeDownloader.Stopped) {
                throw e
            } catch (e: Exception) {
                if (!plan.canFallBack) throw e
                // 2K/4K (VP9/AV1) could not be joined on this phone: the next try downloads H.264 MP4 (max 1080p)
                dir.listFiles()?.forEach { it.delete() }
                val want = task.format.split(':').getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1080
                TaskRepository.update(id, true) { t -> t.copy(format = "video:${minOf(want, 1080)}:mp4") }
                throw java.io.IOException("Trying 1080p instead of 2K/4K on this phone")
            }
            output = joined
        }

        // Paused or cancelled at the very end: keep the files for resume, save nothing
        if (TaskRepository.get(id)?.status != Status.RUNNING) return
        TaskRepository.update(id) { it.copy(progress = 99, message = "Saving…") }

        val ext = output.extension.lowercase()
        val named = File(dir, NativeDownloader.safeName(title, ext))
        if (named.path != output.path) {
            named.delete()
            if (!output.renameTo(named)) output.copyTo(named, overwrite = true)
        }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (task.format.startsWith("audio")) "audio/mp4" else "video/mp4"
        val saved = FileStore.save(this, named, mime)

        // Subtitles (.srt) next to the video. Optional: a failure never fails the download.
        plan.subtitle?.let { sub ->
            try {
                val srt = NativeDownloader.subtitleSrt(sub.content, sub.extension)
                if (srt.isNotBlank()) {
                    val sf = File(dir, NativeDownloader.safeName(title + "." + (task.subLang ?: "sub"), "srt"))
                    sf.writeText(srt)
                    FileStore.saveSubtitle(this, sf)
                }
            } catch (e: Exception) { }
        }

        TaskRepository.update(id, true) {
            it.copy(
                status = Status.DONE, progress = 100, message = "Completed",
                fileUri = saved.uri, mime = saved.mime, title = it.title.ifBlank { saved.name }
            )
        }
        dir.deleteRecursively()
        autoTries.remove(id)
        retryAt.remove(id)
        TaskRepository.get(id)?.let { notifyDone(it, true) }
    }

    /** Loads missing thumbnails (playlist items, shared links) one by one in the background. */
    private fun fillThumbnails() {
        if (thumbJob?.isActive == true) return
        val pending = TaskRepository.tasks.value.any {
            it.thumbPath == null && it.thumbUrl != null && it.id !in thumbTried
        }
        if (!pending) return
        val job = scope.launch {
            while (true) {
                val t = TaskRepository.tasks.value.asReversed().firstOrNull {
                    it.thumbPath == null && it.thumbUrl != null && it.id !in thumbTried
                } ?: break
                thumbTried.add(t.id)
                val bmp = InfoFetcher.loadBitmap(t.thumbUrl!!)
                val path = bmp?.let { InfoFetcher.saveThumb(this@DownloadService, t.id, it) }
                if (path != null) TaskRepository.update(t.id, true) { it.copy(thumbPath = path) }
            }
        }
        thumbJob = job
        job.invokeOnCompletion { pump() }
    }

    // ---------- commands ----------

    private fun pause(id: String) {
        val t = TaskRepository.get(id) ?: return
        if (t.status != Status.RUNNING && t.status != Status.QUEUED && t.status != Status.WAITING) return
        TaskRepository.update(id, true) { it.copy(status = Status.PAUSED, message = "Paused") }
        NativeDownloader.stop(id)
        running[id]?.cancel()
    }

    private fun resume(id: String) {
        val t = TaskRepository.get(id) ?: return
        retryAt.remove(id)
        autoTries.remove(id)
        if (t.status == Status.PAUSED || t.status == Status.FAILED || t.status == Status.WAITING) {
            TaskRepository.update(id, true) {
                it.copy(status = Status.QUEUED, message = "Queued", retries = 0)
            }
        }
    }

    private fun cancel(id: String) {
        TaskRepository.get(id) ?: return
        TaskRepository.remove(id)
        NativeDownloader.stop(id)
        running[id]?.cancel()
        retryAt.remove(id)
        autoTries.remove(id)
        val dir = File(filesDir, "downloads/$id")
        scope.launch { delay(500); dir.deleteRecursively() }     // big folders: never on the main thread
    }

    /** Cancels many downloads with a single write: stops their processes and deletes partial files. */
    private fun cancelMany(ids: Collection<String>) {
        val present = ids.filter { TaskRepository.get(it) != null }
        if (present.isEmpty()) return
        TaskRepository.removeMany(present)
        for (id in present) {
            NativeDownloader.stop(id)
            running[id]?.cancel()
            retryAt.remove(id)
            autoTries.remove(id)
        }
        scope.launch {
            delay(500)
            present.forEach { File(filesDir, "downloads/$it").deleteRecursively() }
        }
    }

    private fun pauseAll() {
        TaskRepository.tasks.value.forEach { pause(it.id) }
    }

    private fun resumeAll() {
        TaskRepository.tasks.value.forEach { resume(it.id) }
    }

    // ---------- storage ----------

    // ---------- network ----------

    private fun caps(): NetworkCapabilities? {
        val network = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(network)
    }

    private fun isOnline(): Boolean =
        caps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun canDownload(): Boolean {
        val c = caps() ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !AppPrefs.wifiOnly(this) || c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---------- notifications ----------

    /** False when Android refuses the foreground start (rare background cases on Android 12+). */
    private fun promote(): Boolean = try {
        startForeground(FG_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: Exception) {
        false
    }

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun label(t: DownloadTask) = t.title.ifBlank { Uri.parse(t.url).host.orEmpty() }

    private fun buildNotification(): Notification {
        val tasks = TaskRepository.tasks.value
        val active = tasks.filter { it.status == Status.RUNNING }
        val queued = tasks.count { it.status == Status.QUEUED }
        val waiting = tasks.count { it.status == Status.WAITING }
        val queuedText = if (queued > 0) "  •  $queued queued" else ""
        val text = when {
            active.size == 1 -> "${label(active[0]).take(32)} — ${active[0].progress}%$queuedText"
            active.size > 1 -> "${active.size} downloading: " +
                active.joinToString("  •  ") { "${it.progress}%" } + queuedText
            waiting > 0 -> "Waiting for network…"
            queued > 0 -> "$queued queued"
            else -> "Preparing…"
        }
        val avg = if (active.isEmpty()) 0 else active.sumOf { it.progress } / active.size
        val pauseAll = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("RAINAX")
            .setContentText(text)
            .setProgress(100, avg, active.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Pause all", pauseAll)

        if (active.isNotEmpty()) {
            // expanded view: one line per active download with its own progress
            val inbox = NotificationCompat.InboxStyle()
            active.take(5).forEach { inbox.addLine("${it.progress}%   ${label(it).take(40)}") }
            when {
                active.size > 5 -> inbox.setSummaryText("+${active.size - 5} more")
                queued > 0 -> inbox.setSummaryText("$queued queued")
            }
            builder.setStyle(inbox)
        }
        return builder.build()
    }

    /** Same lock as pump(): a late update can never bring back the notification after the service stopped. */
    private fun updateNotification() = synchronized(this) {
        if (!stopping) nm.notify(FG_ID, buildNotification())
    }

    /** One notification for all finished downloads (a 100-video playlist must not spam 100 of them). */
    private fun notifyDone(task: DownloadTask, success: Boolean) {
        if (!success) {
            notifyFailed()
            return
        }
        val count = doneCount.incrementAndGet()
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (count == 1) "Download complete" else "$count downloads complete")
            .setContentText(label(task).take(60))
            .setAutoCancel(true)
            .setContentIntent(if (count == 1) playIntent(task) ?: openAppIntent() else openAppIntent())
            .build()
        nm.notify(DONE_ID, n)
    }

    /** Tapping "Download complete" plays the file in the RAINAX player. */
    private fun playIntent(task: DownloadTask): PendingIntent? {
        val t = TaskRepository.get(task.id) ?: task
        val uri = t.fileUri ?: return null
        val m = t.mime.orEmpty()
        if (!(m.startsWith("video/") || m.startsWith("audio/") || t.isAudio)) return null
        val i = Intent(this, PlayerActivity::class.java)
            .putStringArrayListExtra(PlayerActivity.EXTRA_URIS, arrayListOf(uri))
            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf(t.title))
            .putExtra(PlayerActivity.EXTRA_INDEX, 0)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notifyFailed() {
        val count = TaskRepository.tasks.value.count { it.status == Status.FAILED }
        if (count == 0) return
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (count == 1) "1 download failed" else "$count downloads failed")
            .setContentText("Open the app to retry or remove them")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(FAIL_ID, n)
    }

    companion object {
        const val ACTION_PUMP = "com.rainax.ytdownloader.PUMP"
        const val ACTION_PAUSE = "com.rainax.ytdownloader.PAUSE"
        const val ACTION_RESUME = "com.rainax.ytdownloader.RESUME"
        const val ACTION_CANCEL = "com.rainax.ytdownloader.CANCEL"
        const val ACTION_PAUSE_ALL = "com.rainax.ytdownloader.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.rainax.ytdownloader.RESUME_ALL"
        const val ACTION_PAUSE_SEL = "com.rainax.ytdownloader.PAUSE_SEL"
        const val ACTION_RESUME_SEL = "com.rainax.ytdownloader.RESUME_SEL"
        const val ACTION_CANCEL_SEL = "com.rainax.ytdownloader.CANCEL_SEL"
        const val ACTION_CANCEL_ALL = "com.rainax.ytdownloader.CANCEL_ALL"
        const val EXTRA_ID = "id"
        const val EXTRA_IDS = "ids"

        private const val FG_ID = 1001
        private const val DONE_ID = 1003
        private const val FAIL_ID = 1002
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val MAX_RETRIES = 5
        private val AUTO_DELAYS = longArrayOf(60_000L, 180_000L, 600_000L)

        fun send(context: Context, action: String, id: String? = null, ids: Array<String>? = null) {
            val intent = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            if (ids != null) intent.putExtra(EXTRA_IDS, ids)
            try { ContextCompat.startForegroundService(context, intent) } catch (e: Exception) { }
        }
    }
}
