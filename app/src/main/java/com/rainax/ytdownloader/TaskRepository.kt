package com.rainax.ytdownloader

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for the download queue and history.
 * Persisted so the queue survives the app or service being killed.
 */
object TaskRepository {
    private const val KEY = "tasks"
    private const val MAX_DONE = 50

    private lateinit var prefs: SharedPreferences
    private lateinit var filesRoot: File
    private lateinit var saveFile: File

    /** Saving runs on its own thread, at most every [SAVE_GAP_MS], always with the newest list. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val saveQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val SAVE_GAP_MS = 400L
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("tasks", Context.MODE_PRIVATE)
        filesRoot = context.applicationContext.filesDir
        saveFile = File(filesRoot, "tasks.json")
        // Anything that was mid-download when the process died goes back to the queue (auto resume)
        _tasks.value = load().map {
            if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED, message = "Queued") else it
        }
    }

    fun get(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    @Synchronized
    fun addAll(tasks: List<DownloadTask>) {
        if (tasks.isEmpty()) return
        _tasks.value = tasks.asReversed() + _tasks.value      // same order as adding one by one
        persist()
    }

    @Synchronized
    fun update(id: String, persist: Boolean = false, block: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) block(it) else it }
        if (persist) persist()
    }

    /** Changes every task matching [where] with one save (big playlists stay fast). */
    @Synchronized
    fun updateWhere(where: (DownloadTask) -> Boolean, block: (DownloadTask) -> DownloadTask) {
        var changed = false
        _tasks.value = _tasks.value.map { if (where(it)) { changed = true; block(it) } else it }
        if (changed) persist()
    }

    @Synchronized
    fun remove(id: String) {
        get(id)?.let { cleanup(it) }
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    @Synchronized
    fun removeDone() {
        _tasks.value.filter { it.status == Status.DONE }.forEach { cleanup(it) }
        _tasks.value = _tasks.value.filterNot { it.status == Status.DONE }
        persist()
    }

    @Synchronized
    fun removeDoneOlderThan(cutoff: Long) {
        val old = _tasks.value.filter { it.status == Status.DONE && it.createdAt < cutoff }
        if (old.isEmpty()) return
        old.forEach { cleanup(it) }
        val ids = old.map { it.id }.toSet()
        _tasks.value = _tasks.value.filterNot { it.id in ids }
        persist()
    }

    @Synchronized
    fun removeMany(ids: Collection<String>) {
        val set = ids.toSet()
        _tasks.value.filter { it.id in set }.forEach { cleanup(it) }
        _tasks.value = _tasks.value.filterNot { it.id in set }
        persist()
    }

    @Synchronized
    fun removeFailed() {
        _tasks.value.filter { it.status == Status.FAILED }.forEach { cleanup(it) }
        _tasks.value = _tasks.value.filterNot { it.status == Status.FAILED }
        persist()
    }

    private fun cleanup(t: DownloadTask) {
        t.thumbPath?.let { File(it).delete() }
        // partial downloads can be big: delete them off the main thread
        val dir = File(filesRoot, "downloads/${t.id}")
        if (dir.exists()) Thread { dir.deleteRecursively() }.start()
    }

    @Synchronized
    private fun persist() {
        var doneCount = 0
        val kept = _tasks.value.filter {
            if (it.status == Status.DONE) { doneCount++; doneCount <= MAX_DONE } else true
        }
        if (kept.size != _tasks.value.size) {
            val keptIds = kept.mapTo(HashSet()) { it.id }
            _tasks.value.filter { it.id !in keptIds }.forEach { t -> t.thumbPath?.let { File(it).delete() } }
        }
        _tasks.value = kept
        // Turning 1000+ downloads into text and writing it took long enough to freeze the app when it ran on
        // every change: now it runs in the background, and many quick changes make one save.
        if (saveQueued.compareAndSet(false, true)) {
            writer.execute {
                try { Thread.sleep(SAVE_GAP_MS) } catch (e: InterruptedException) { }
                saveQueued.set(false)
                write(_tasks.value)
            }
        }
    }

    private fun write(list: List<DownloadTask>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id).put("url", t.url).put("title", t.title)
                        .put("format", t.format).put("thumbPath", t.thumbPath ?: "")
                        .put("status", t.status.name).put("progress", t.progress)
                        .put("message", t.message).put("retries", t.retries)
                        .put("fileUri", t.fileUri ?: "").put("mime", t.mime ?: "")
                        .put("createdAt", t.createdAt)
                        .put("subLang", t.subLang ?: "").put("thumbUrl", t.thumbUrl ?: "")
                )
            }
            val tmp = File(saveFile.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(saveFile)) { saveFile.delete(); tmp.renameTo(saveFile) }
            // the old place (settings file) is emptied once: a big list there slowed every screen change
            if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
        }
    }

    private fun load(): List<DownloadTask> = try {
        val text = if (saveFile.exists()) saveFile.readText() else prefs.getString(KEY, "[]")
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DownloadTask(
                id = o.getString("id"),
                url = o.getString("url"),
                title = o.optString("title"),
                format = o.optString("format", "video:0"),
                thumbPath = o.optString("thumbPath").ifEmpty { null },
                status = Status.valueOf(o.getString("status")),
                progress = o.optInt("progress"),
                message = o.optString("message"),
                retries = o.optInt("retries"),
                fileUri = o.optString("fileUri").ifEmpty { null },
                mime = o.optString("mime").ifEmpty { null },
                createdAt = o.optLong("createdAt"),
                subLang = o.optString("subLang").ifEmpty { null },
                thumbUrl = o.optString("thumbUrl").ifEmpty { null }
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
