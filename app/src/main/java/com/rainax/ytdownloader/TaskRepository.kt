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
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("tasks", Context.MODE_PRIVATE)
        filesRoot = context.applicationContext.filesDir
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
        File(filesRoot, "downloads/${t.id}").deleteRecursively()
        File(filesRoot, "cookies/${t.id}.txt").delete()
    }

    @Synchronized
    private fun persist() {
        var doneCount = 0
        val kept = _tasks.value.filter {
            if (it.status == Status.DONE) { doneCount++; doneCount <= MAX_DONE } else true
        }
        _tasks.value = kept
        val arr = JSONArray()
        kept.forEach { t ->
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
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): List<DownloadTask> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
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
