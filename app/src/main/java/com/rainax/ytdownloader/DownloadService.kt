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
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service that owns the download queue, so downloads continue when the app is closed.
 * - runs up to N downloads at once (Settings)
 * - resumes partial files (yt-dlp --continue) after errors, restarts and network loss
 * - can wait for Wi-Fi only
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()
    private lateinit var nm: NotificationManager
    private lateinit var cm: ConnectivityManager

    @Volatile private var stopping = false
    @Volatile private var doneCount = 0
    private var failedStart = false
    private var callbackRegistered = false
    private var thumbJob: Job? = null
    private val thumbTried = ConcurrentHashMap.newKeySet<String>()

    private val speedRe = Regex("""at\s+(\S+/s)""")
    private val sizeRe = Regex("""of\s+~?\s*([\d.]+\S*)""")
    private val formatRe = Regex("""Downloading \d+ format\(s\):\s*(\S+)""")
    private val destRe = Regex("""Destination:\s*(.+)""")
    private val sizeParseRe = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { pump() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { pump() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TaskRepository.init(this)
        nm = getSystemService(NotificationManager::class.java)
        cm = getSystemService(ConnectivityManager::class.java)
        createChannels()
        try {
            promote()
        } catch (e: Exception) {
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
        stopping = false
        try { promote() } catch (e: Exception) { /* already foreground */ }

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
        pump()
        return START_STICKY
    }

    override fun onDestroy() {
        if (callbackRegistered) {
            try { cm.unregisterNetworkCallback(netCallback) } catch (e: Exception) { }
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------- scheduler ----------

    @Synchronized
    private fun pump() {
        fillThumbnails()
        // Network came back (or Wi-Fi-only was switched off): waiting tasks rejoin the queue
        if (canDownload()) {
            TaskRepository.tasks.value.filter { it.status == Status.WAITING }.forEach { t ->
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
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    private fun isYoutube(url: String): Boolean {
        val h = Uri.parse(url).host.orEmpty().lowercase()
        return h.endsWith("youtube.com") || h == "youtu.be"
    }

    private fun downloadOnce(id: String) {
        val task = TaskRepository.get(id) ?: return
        Engine.ensureInit(this)
        // Always use the CURRENT browser sign-in (YouTube rotates cookies during long queues)
        val cookieFile: String? = null

        // Links added in bulk have no title yet: look it up now
        if (task.title.isBlank()) {
            runCatching { InfoFetcher.fetch(this, task.url, cookieFile, "info-$id") }.getOrNull()?.let { info ->
                val thumbPath = info.thumb?.let { InfoFetcher.saveThumb(this, id, it) }
                TaskRepository.update(id, true) { it.copy(title = info.title.orEmpty(), thumbPath = thumbPath) }
            }
        }

        // Same folder on every attempt so yt-dlp can continue the .part files
        val dir = File(filesDir, "downloads/$id").apply { mkdirs() }

        val request = YoutubeDLRequest(task.url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--continue")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("--socket-timeout", "30")
            // Speed: several pieces at once (HLS/DASH sites), smaller ranged requests for YouTube so it does not
            // throttle, and a fresh link if the speed ever drops to a crawl
            addOption("-N", "8")
            addOption("--throttled-rate", "100K")
            addOption("--buffer-size", "64K")
            if (isYoutube(task.url)) addOption("--http-chunk-size", "10M")
            addOption("-o", "${dir.absolutePath}/%(title).80s.%(ext)s")
            cookieFile?.let { addOption("--cookies", it) }
            Formats.configure(this, task.format, task.subLang)
        }

        // A video+audio download is two separate transfers; fold them into one live 0-100% bar
        val spec = task.format
        val host = Uri.parse(task.url).host.orEmpty()
        var streams = if (spec.startsWith("video") && (host.contains("youtube") || host.contains("youtu.be"))) 2 else 1
        var cur = 0                          // stream being downloaded right now (1-based)
        var lastP = -1
        val sizes = DoubleArray(4)           // bytes of each stream, as reported by yt-dlp
        var best = task.progress.coerceAtMost(98)   // the bar never goes backwards, even after a retry
        YoutubeDL.getInstance().execute(request, id) { progress, eta, line ->
            formatRe.find(line)?.let { streams = it.groupValues[1].split('+').size.coerceIn(1, 3) }

            val p = progress.toInt().coerceIn(0, 100)
            if (cur == 0) cur = 1
            else if (lastP >= 80 && p < 30 && cur < 3) cur++     // percent restarted: next stream began
            lastP = p
            sizeParseRe.find(line)?.let { m ->
                val bytes = toBytes(m.groupValues[1], m.groupValues[2])
                if (bytes > 0) sizes[cur] = bytes
            }

            val processing = line.contains("Merger") || line.contains("ExtractAudio") || line.contains("Fixup")
            val overall = when {
                processing -> 99
                streams <= 1 -> p
                else -> combined(streams, cur, p, sizes)
            }
            if (overall > best) best = overall

            val text = if (processing) {
                "Processing…"
            } else {
                val speed = speedRe.find(line)?.groupValues?.get(1)
                buildString {
                    append("Downloading $best%")
                    if (streams > 1) append("  •  part ${minOf(cur, streams)}/$streams")
                    if (speed != null) append("  •  $speed")
                    if (eta >= 0) append("  •  ETA ${formatEta(eta)}")
                }
            }
            TaskRepository.update(id) {
                if (it.status != Status.RUNNING || (it.progress == best && it.message == text)) it
                else it.copy(progress = best, message = text)
            }
        }

        val saved = saveToDownloads(dir)
        saveSubtitles(dir)
        TaskRepository.update(id, true) {
            it.copy(
                status = Status.DONE, progress = 100, message = "Completed",
                fileUri = saved.uri, mime = saved.mime, title = saved.name
            )
        }
        dir.deleteRecursively()
        cookieFile?.let { File(it).delete() }
        TaskRepository.get(id)?.let { notifyDone(it, true) }
    }

    /** Overall percent of a multi-stream download, weighted by the real size of each stream. */
    private fun combined(streams: Int, cur: Int, p: Int, sizes: DoubleArray): Int {
        val known = (1..cur).all { sizes[it] > 0 }
        if (!known) {
            // sizes not reported: the video part is most of the data
            return if (cur <= 1) p * 85 / 100 else 85 + p * 15 / 100
        }
        var done = 0.0
        var total = 0.0
        for (i in 1 until cur) {
            done += sizes[i]
            total += sizes[i]
        }
        done += sizes[cur] * p / 100.0
        total += sizes[cur]
        // streams that have not started yet (audio is usually ~10% of the video)
        if (cur < streams) total += sizes[1] * 0.12 * (streams - cur)
        return (done * 100.0 / total).toInt().coerceIn(0, 99)
    }

    private fun toBytes(number: String, unit: String): Double {
        val n = number.toDoubleOrNull() ?: return 0.0
        val mult = when (unit.firstOrNull()?.uppercaseChar()) {
            'K' -> 1024.0
            'M' -> 1048576.0
            'G' -> 1073741824.0
            'T' -> 1099511627776.0
            else -> 1.0
        }
        return n * mult
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
        YoutubeDL.getInstance().destroyProcessById(id)
        running[id]?.cancel()
    }

    private fun resume(id: String) {
        val t = TaskRepository.get(id) ?: return
        if (t.status == Status.PAUSED || t.status == Status.FAILED) {
            TaskRepository.update(id, true) {
                it.copy(status = Status.QUEUED, message = "Queued", retries = 0)
            }
        }
    }

    private fun cancel(id: String) {
        TaskRepository.get(id) ?: return
        TaskRepository.remove(id)
        YoutubeDL.getInstance().destroyProcessById(id)
        running[id]?.cancel()
        File(filesDir, "downloads/$id").deleteRecursively()
    }

    /** Cancels many downloads with a single write: stops their processes and deletes partial files. */
    private fun cancelMany(ids: Collection<String>) {
        val present = ids.filter { TaskRepository.get(it) != null }
        if (present.isEmpty()) return
        TaskRepository.removeMany(present)
        for (id in present) {
            YoutubeDL.getInstance().destroyProcessById(id)
            running[id]?.cancel()
            File(filesDir, "downloads/$id").deleteRecursively()
        }
    }

    private fun pauseAll() {
        TaskRepository.tasks.value.forEach { pause(it.id) }
    }

    private fun resumeAll() {
        TaskRepository.tasks.value.forEach { resume(it.id) }
    }

    // ---------- storage ----------

    /** Copies the finished file into the download folder chosen in Settings (default: Downloads/rainax-yt-downloader). */
    private fun saveToDownloads(dir: File): FileStore.Saved {
        val file = dir.listFiles { f -> f.isFile && f.extension.lowercase() in KEEP_EXT }
            ?.maxByOrNull { it.length() } ?: error("No output file was produced")
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        return FileStore.save(this, file, mime)
    }

    /** Subtitle files (.srt) requested in the download sheet go next to the video. */
    private fun saveSubtitles(dir: File) {
        dir.listFiles { f -> f.isFile && f.extension.lowercase() == "srt" }?.forEach { FileStore.saveSubtitle(this, it) }
    }

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

    private fun promote() {
        // Some phones refuse a foreground start in rare background cases: keep going instead of crashing
        try {
            startForeground(FG_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) { }
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

    private fun updateNotification() {
        if (stopping) return
        nm.notify(FG_ID, buildNotification())
    }

    /** One notification for all finished downloads (a 100-video playlist must not spam 100 of them). */
    private fun notifyDone(task: DownloadTask, success: Boolean) {
        if (!success) {
            notifyFailed()
            return
        }
        doneCount++
        val n = NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (doneCount == 1) "Download complete" else "$doneCount downloads complete")
            .setContentText(label(task).take(60))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(DONE_ID, n)
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
        private val KEEP_EXT = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "ogg", "mov")

        fun send(context: Context, action: String, id: String? = null, ids: Array<String>? = null) {
            val intent = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            if (ids != null) intent.putExtra(EXTRA_IDS, ids)
            try { ContextCompat.startForegroundService(context, intent) } catch (e: Exception) { }
        }
    }
}
