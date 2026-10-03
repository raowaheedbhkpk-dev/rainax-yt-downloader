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
import kotlinx.coroutines.isActive
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
    private val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastStartId = 0
    private var failedStart = false
    private var callbackRegistered = false
    private var thumbJob: Job? = null
    private val thumbTried = ConcurrentHashMap.newKeySet<String>()

    private val speedRe = Regex("""at\s+(\S+/s)""")
    private val formatRe = Regex("""Downloading \d+ format\(s\):\s*(\S+)""")
    private val sizeParseRe = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")

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
        // (only when online on mobile data; a short network drop is left to yt-dlp's own retries)
        if (isOnline() && !canDownload() && running.isNotEmpty()) {
            running.keys.toList().forEach { id ->
                if (TaskRepository.get(id)?.status == Status.RUNNING) {
                    TaskRepository.update(id, true) { it.copy(status = Status.WAITING, message = "Waiting for Wi-Fi…") }
                    YoutubeDL.getInstance().destroyProcessById(id)
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

    private fun isYoutube(url: String): Boolean {
        val h = Uri.parse(url).host.orEmpty().lowercase()
        return h.endsWith("youtube.com") || h == "youtu.be"
    }

    private fun downloadOnce(id: String) {
        val task = TaskRepository.get(id) ?: return
        Engine.ensureInit(this)

        // Links added in bulk have no title yet: look it up now
        if (task.title.isBlank()) {
            runCatching { InfoFetcher.fetch(this, task.url, null, "info-$id") }.getOrNull()?.let { info ->
                val thumbPath = info.thumb?.let { InfoFetcher.saveThumb(this, id, it) }
                TaskRepository.update(id, true) { it.copy(title = info.title.orEmpty(), thumbPath = thumbPath ?: it.thumbPath) }
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
            // Names are limited in BYTES (emoji take 4 bytes each); Android allows 255, so 90 leaves room for ".f137.mp4.part"
            addOption("-o", "${dir.absolutePath}/%(title).90B.%(ext)s")
            Formats.configure(this, task.format, task.subLang)
        }

        // A video+audio download is two separate transfers; fold them into one live 0-100% bar
        val spec = task.format
        var streams = if (spec.startsWith("video") && isYoutube(task.url)) 2 else 1
        var cur = 0                          // stream being downloaded right now (1-based)
        var lastP = -1
        val sizes = DoubleArray(4)           // bytes of each stream, as reported by yt-dlp
        var best = task.progress.coerceAtMost(98)   // the bar never goes backwards, even after a retry
        // Paused or cancelled while the title lookup / engine start was running? Don't start the transfer.
        if (TaskRepository.get(id)?.status != Status.RUNNING) return
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

        // Paused or cancelled at the very end: keep the partial files for resume, save nothing
        if (TaskRepository.get(id)?.status != Status.RUNNING) return
        val saved = saveToDownloads(dir)
        saveSubtitles(dir)
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
        YoutubeDL.getInstance().destroyProcessById("info-$id")
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
        YoutubeDL.getInstance().destroyProcessById(id)
        YoutubeDL.getInstance().destroyProcessById("info-$id")
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
            YoutubeDL.getInstance().destroyProcessById(id)
            YoutubeDL.getInstance().destroyProcessById("info-$id")
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

    /** Copies the finished file into the download folder chosen in Settings (default: Downloads/rainax-yt-downloader). */
    private fun saveToDownloads(dir: File): FileStore.Saved {
        // The biggest real media file (any format a site may give: mp4, mkv, flv, 3gp, ts, aac, flac, ...)
        val file = dir.listFiles { f -> f.isFile && f.extension.lowercase() !in SKIP_EXT && f.length() > 0 }
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
        private val SKIP_EXT = setOf("part", "ytdl", "srt", "vtt", "ass", "jpg", "jpeg", "webp", "png", "json", "temp", "tmp", "txt")

        fun send(context: Context, action: String, id: String? = null, ids: Array<String>? = null) {
            val intent = Intent(context, DownloadService::class.java).setAction(action)
            if (id != null) intent.putExtra(EXTRA_ID, id)
            if (ids != null) intent.putExtra(EXTRA_IDS, ids)
            try { ContextCompat.startForegroundService(context, intent) } catch (e: Exception) { }
        }
    }
}
