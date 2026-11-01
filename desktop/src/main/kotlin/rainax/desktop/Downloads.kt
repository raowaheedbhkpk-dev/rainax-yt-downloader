package rainax.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.prefs.Preferences
import kotlin.coroutines.coroutineContext

/** Settings kept between runs (Windows registry, under the current user). */
object Prefs {
    private val p: Preferences = Preferences.userRoot().node("rainax-tube")

    var theme: Int
        get() = p.getInt("theme", 0)
        set(v) = p.putInt("theme", v)

    var parallel: Int
        get() = p.getInt("parallel", 2).coerceIn(1, 4)
        set(v) = p.putInt("parallel", v)

    var folder: File
        get() = File(p.get("folder", File(System.getProperty("user.home"), "Downloads${File.separator}RAINAX").path))
        set(v) = p.put("folder", v.path)

    /** Finished downloads, one per line: title \t file \t quality \t size \t thumb. */
    var history: String
        get() = p.get("history", "")
        set(v) = p.put("history", v.takeLast(7000))
}

/** ffmpeg.exe, shipped next to the app: joins picture + sound, makes MP3s. */
object Ffmpeg {
    private val exe: File? by lazy {
        val dir = System.getProperty("compose.application.resources.dir")
        listOfNotNull(dir?.let { File(it, "ffmpeg.exe") }, dir?.let { File(it, "ffmpeg") })
            .firstOrNull { it.isFile }
    }

    fun available() = exe != null

    /** Blocking. Runs ffmpeg with [args]; throws with its last lines when it fails. */
    fun run(vararg args: String) {
        val bin = exe ?: error("ffmpeg is missing")
        val proc = ProcessBuilder(listOf(bin.path, "-hide_banner", "-loglevel", "error", "-y") + args)
            .redirectErrorStream(true).start()
        val log = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) error("ffmpeg: " + log.lines().takeLast(3).joinToString(" ").take(200))
    }
}

enum class Status { Queued, Downloading, Joining, Paused, Failed, Done }

class Task(val id: Long, val title: String, val thumb: String?, val choice: Choice, val file: File) {
    var state by mutableStateOf(Status.Queued)
    var progress by mutableDoubleStateOf(0.0)      // 0..1
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(choice.size)
    var speed by mutableLongStateOf(0L)            // bytes per second
    var error by mutableStateOf<String?>(null)
    internal var job: Job? = null
    val etaSec: Long get() = if (speed > 0 && total > done) (total - done) / speed else -1
}

/** Every download of this run, plus the finished ones of earlier runs. */
object Downloads {
    val tasks = mutableStateListOf<Task>()
    var running by mutableIntStateOf(0)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gate = Semaphore(Prefs.parallel)
    private var nextId = 1L
    private const val CHUNK = 8L * 1024 * 1024        // YouTube serves big files fastest in pieces

    init {
        // finished downloads from earlier runs (only those whose file is still there)
        Prefs.history.lines().filter { it.isNotBlank() }.forEach { line ->
            val f = line.split('\t')
            if (f.size >= 4 && File(f[1]).isFile) {
                val c = Choice(f[2], "", f[2].endsWith("3") || f[2] == "M4A", null, null, File(f[1]).extension, f[3].toLongOrNull() ?: 0L)
                tasks += Task(nextId++, f[0], f.getOrNull(4)?.takeIf { it.isNotBlank() }, c, File(f[1])).apply {
                    state = Status.Done; progress = 1.0; done = total
                }
            }
        }
    }

    fun setParallel(n: Int) { Prefs.parallel = n; gate = Semaphore(n) }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(120).ifBlank { "video" }

    /** Starts downloading [choice] of the video called [title]. */
    fun add(title: String, thumb: String?, choice: Choice): Task {
        val dir = Prefs.folder.apply { mkdirs() }
        var file = File(dir, "${safeName(title)} (${choice.label}).${choice.ext}")
        var n = 2
        while (file.exists() || tasks.any { it.file == file && it.state != Status.Done }) {
            file = File(dir, "${safeName(title)} (${choice.label}) $n.${choice.ext}"); n++
        }
        val t = Task(nextId++, title, thumb, choice, file)
        tasks.add(0, t)
        start(t)
        return t
    }

    fun pause(t: Task) {
        t.job?.cancel()
        t.state = Status.Paused
        t.speed = 0
    }

    fun resume(t: Task) { if (t.state == Status.Paused || t.state == Status.Failed) start(t) }

    fun remove(t: Task) {
        t.job?.cancel()
        tasks.remove(t)
        parts(t).forEach { it.delete() }
        saveHistory()
    }

    private fun parts(t: Task) = listOf(File(t.file.path + ".video.part"), File(t.file.path + ".audio.part"))

    private fun start(t: Task) {
        t.error = null
        t.state = Status.Queued
        t.job = scope.launch {
            gate.withPermit {
                running++
                try {
                    t.state = Status.Downloading
                    work(t)
                    t.state = Status.Done
                    t.progress = 1.0
                    t.speed = 0
                    saveHistory()
                } catch (e: CancellationException) {
                    if (t.state != Status.Paused) t.state = Status.Paused
                    throw e
                } catch (e: Exception) {
                    t.state = Status.Failed
                    t.speed = 0
                    t.error = e.message?.take(160) ?: "Download failed"
                } finally {
                    running--
                }
            }
        }
    }

    private suspend fun work(t: Task) {
        val c = t.choice
        val (vPart, aPart) = parts(t)
        val urls = listOfNotNull(c.videoUrl?.let { it to vPart }, c.audioUrl?.let { it to aPart })
        // sizes of the pieces, so progress covers all of them
        val sizes = urls.map { (u, f) -> contentLength(u).takeIf { it > 0 } ?: 0L }
        if (sizes.all { it > 0 }) t.total = sizes.sum()
        var before = 0L
        urls.forEachIndexed { i, (u, f) ->
            fetch(u, f, sizes[i]) { got -> t.done = before + got; if (t.total > 0) t.progress = t.done.toDouble() / t.total }
            before += f.length()
        }
        coroutineContext.ensureActive()
        t.state = Status.Joining
        t.speed = 0
        when {
            c.toMp3 -> { Ffmpeg.run("-i", aPart.path, "-vn", "-c:a", "libmp3lame", "-q:a", "2", t.file.path); aPart.delete() }
            c.videoUrl != null && c.audioUrl != null -> {
                Ffmpeg.run("-i", vPart.path, "-i", aPart.path, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", t.file.path)
                vPart.delete(); aPart.delete()
            }
            c.videoUrl != null -> { t.file.delete(); if (!vPart.renameTo(t.file)) { vPart.copyTo(t.file, true); vPart.delete() } }
            else -> { t.file.delete(); if (!aPart.renameTo(t.file)) { aPart.copyTo(t.file, true); aPart.delete() } }
        }
    }

    private fun open(url: String, from: Long, to: Long?): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 15_000
        con.readTimeout = 30_000
        con.setRequestProperty("User-Agent", Yt.UA)
        con.setRequestProperty("Range", if (to != null) "bytes=$from-$to" else "bytes=$from-")
        return con
    }

    private fun contentLength(url: String): Long = runCatching {
        val con = open(url, 0, 0)
        try {
            con.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: con.contentLengthLong
        } finally { con.disconnect() }
    }.getOrDefault(0L)

    /** Downloads [url] into [file] in 8 MB pieces, going on from what is already there (pause / retry). */
    private suspend fun fetch(url: String, file: File, size: Long, progress: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var pos = file.length()
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        val task = tasks.firstOrNull { parts(it).contains(file) }
        RandomAccessFile(file, "rw").use { out ->
            out.seek(pos)
            while (size <= 0 || pos < size) {
                coroutineContext.ensureActive()
                val end = if (size > 0) minOf(pos + CHUNK, size) - 1 else null
                val con = open(url, pos, end)
                try {
                    val code = con.responseCode
                    if (code == 416) break                      // already complete
                    if (code !in 200..299) error("The site answered $code. Try again.")
                    con.inputStream.use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            pos += n
                            windowBytes += n
                            progress(pos)
                            val now = System.nanoTime()
                            if (now - windowStart > 1_000_000_000L) {
                                task?.speed = windowBytes * 1_000_000_000L / (now - windowStart)
                                windowStart = now
                                windowBytes = 0
                            }
                        }
                    }
                } finally { con.disconnect() }
                if (size <= 0) break                            // unknown size: one request reads it all
            }
        }
    }

    private fun saveHistory() {
        Prefs.history = tasks.filter { it.state == Status.Done }.take(60).joinToString("\n") {
            listOf(it.title.replace('\t', ' '), it.file.path, it.choice.label, it.total.toString(), it.thumb.orEmpty()).joinToString("\t")
        }
    }

    fun openFolder(t: Task? = null) = runCatching {
        val f = t?.file?.parentFile ?: Prefs.folder.apply { mkdirs() }
        if (t != null && t.file.exists()) ProcessBuilder("explorer.exe", "/select,", t.file.path).start()
        else java.awt.Desktop.getDesktop().open(f)
    }

    fun play(t: Task) = runCatching { java.awt.Desktop.getDesktop().open(t.file) }
}
