package com.rainax.ytdownloader

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Watch while downloading: knows which pieces of each file being downloaded are already on the phone,
 * so the player can play them right away (and asks the downloader for the piece it is waiting for).
 *
 * A download's parts have roles: "media" (one file), or "video" + "audio" (joined at the end).
 */
object PartialFiles {

    /** One file being downloaded. [done] = finished pieces of [block] bytes; null for one-connection downloads. */
    class Entry(val file: File, val total: Long, val block: Long, private val done: BooleanArray?) {
        @Volatile var prefix = 0L            // one-connection downloads: bytes written from the start
        @Volatile var complete = false
        @Volatile var want = -1               // piece the player is waiting for (-1 = none)
        @Volatile private var stamp = 0       // makes piece updates visible to the player's thread

        /** A piece was finished (call after setting it in the shared array). */
        fun touch() { stamp++ }

        /** How many bytes from [pos] can be read now. */
        fun available(pos: Long): Long {
            if (complete) return if (total > 0) (total - pos).coerceAtLeast(0) else Long.MAX_VALUE
            if (stamp < 0) return 0                       // read the volatile first
            val d = done ?: return (prefix - pos).coerceAtLeast(0)
            var b = (pos / block).toInt()
            var end = pos
            while (b in d.indices && d[b]) {
                end = minOf(total, (b + 1) * block)
                b++
            }
            return end - pos
        }

        fun pieceOf(pos: Long): Int = if (done == null) -1 else (pos / block).toInt()
    }

    private val entries = ConcurrentHashMap<String, Entry>()        // by file path
    private val roles = ConcurrentHashMap<String, File>()           // "id/role" -> file
    private val watchers = ConcurrentHashMap<String, AtomicInteger>()
    private val deleteLater = ConcurrentHashMap<String, File>()     // finished while someone was watching

    fun register(e: Entry) { entries[e.file.path] = e }
    fun entry(file: File): Entry? = entries[file.path]

    /** The download's parts, known as soon as it has found the streams. */
    fun setRoles(id: String, parts: Map<String, File>) {
        roles.keys.removeAll { it.startsWith("$id/") }
        parts.forEach { (role, f) -> roles["$id/$role"] = f }
    }

    fun setRole(id: String, role: String, file: File) { roles["$id/$role"] = file }
    fun roleFile(id: String, role: String): File? = roles["$id/$role"]

    /**
     * "av" (picture + separate sound), "media" (one file), "video" (picture only: a paused download from
     * an older version that fetched the sound last), or null while the download is still starting.
     */
    fun layout(id: String): String? = when {
        roles.containsKey("$id/media") -> "media"
        roles.containsKey("$id/video") && roles.containsKey("$id/audio") -> "av"
        roles.containsKey("$id/video") -> "video"
        else -> null
    }

    fun watch(id: String) { watchers.getOrPut(id) { AtomicInteger() }.incrementAndGet() }

    fun unwatch(id: String) {
        val left = watchers[id]?.decrementAndGet() ?: 0
        if (left <= 0) {
            watchers.remove(id)
            deleteLater.remove(id)?.let { dir -> Thread { dir.deleteRecursively(); forget(id) }.start() }
        }
    }

    fun isWatched(id: String) = (watchers[id]?.get() ?: 0) > 0

    /** The download is saved: delete its working folder now, or when the player closes. */
    fun finished(id: String, dir: File) {
        if (isWatched(id)) {
            roles.keys.filter { it.startsWith("$id/") }.forEach { k -> roles[k]?.let { entries[it.path]?.complete = true } }
            deleteLater[id] = dir
            if (!isWatched(id)) unwatch(id)        // the player closed meanwhile
        } else {
            dir.deleteRecursively()
            forget(id)
        }
    }

    /**
     * A download that isn't running (paused, failed, waiting for internet, or after the app restarted):
     * finds its parts in its working folder [dir] and which pieces are already there (the ".state" files),
     * so what was downloaded can be played offline. True when there is something to play.
     */
    @Synchronized
    fun restore(id: String, dir: File): Boolean {
        if (layout(id) != null) return true
        val files = dir.listFiles()?.filter { it.isFile && !it.name.contains(".state") && it.length() > 0 } ?: return false
        fun find(base: String) = files.firstOrNull { it.name.startsWith("$base.") }
            ?: files.filter { it.name.startsWith("$base-") }.maxByOrNull { it.lastModified() }
        val media = find("media")
        val video = find("video")
        val audio = find("audio")
        val parts = when {
            media != null -> mapOf("media" to media)
            video != null && audio != null -> mapOf("video" to video, "audio" to audio)
            video != null -> mapOf("video" to video)              // sound not downloaded yet: picture only
            else -> return false
        }
        for (f in parts.values) if (entry(f) == null) register(fromDisk(f))
        setRoles(id, parts)
        return true
    }

    /** Rebuilds a part's piece list from the downloader's ".state" file ("size" then the finished pieces). */
    private fun fromDisk(f: File): Entry {
        val lines = runCatching { File(f.path + ".state").readLines() }.getOrDefault(emptyList())
        val total = lines.firstOrNull()?.trim()?.toLongOrNull()
        if (total == null || total <= 0) {
            // one-connection download: everything written so far, from the start
            return Entry(f, -1, 0, null).also { it.prefix = f.length() }
        }
        val block = NativeDownloader.BLOCK
        val done = BooleanArray(((total + block - 1) / block).toInt())
        lines.getOrNull(1)?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.forEach { if (it in done.indices) done[it] = true }
        return Entry(f, total, block, done).also { it.complete = done.all { d -> d } }
    }

    /** Cancelled or cleaned up: nothing of it can be played any more. */
    fun forget(id: String) {
        roles.keys.filter { it.startsWith("$id/") }.forEach { k -> roles.remove(k)?.let { entries.remove(it.path) } }
        // also failed backup streams and earlier attempts of this download
        entries.keys.removeAll { it.contains("/downloads/$id/") }
    }
}
