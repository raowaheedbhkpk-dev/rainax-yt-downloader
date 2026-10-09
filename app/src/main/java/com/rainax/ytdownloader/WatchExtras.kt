package com.rainax.ytdownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** YouTube video id from any YouTube link (watch, shorts, youtu.be, live). */
fun youtubeId(url: String?): String? =
    url?.let { Regex("(?:[?&]v=|youtu\\.be/|shorts/|/live/)([\\w-]{11})").find(it)?.groupValues?.get(1) }

/** Small blocking GET for the free public services below (null on any problem). */
private fun getText(url: String): String? {
    var con: HttpURLConnection? = null
    return try {
        con = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "RAINAX-Tube")
            setRequestProperty("Accept", "application/json")
        }
        if (con.responseCode != 200) null else con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    } finally {
        con?.disconnect()
    }
}

/**
 * SponsorBlock (sponsor.ajay.app): parts of a video that viewers marked as sponsor ads, self-promotion or
 * "like and subscribe" reminders. Only the first 4 characters of a hash of the video id are sent (privacy).
 */
object SponsorBlock {

    class Segment(val startMs: Long, val endMs: Long, val category: String)

    private val cache = ConcurrentHashMap<String, List<Segment>>()

    fun cached(id: String): List<Segment>? = cache[id]

    /** Blocking. The parts to skip in video [id] (empty when there are none or the service can't be reached). */
    fun segments(id: String): List<Segment> {
        cache[id]?.let { return it }
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(4)
        val cats = java.net.URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"interaction\"]", "UTF-8")
        val text = getText("https://sponsor.ajay.app/api/skipSegments/$hash?categories=$cats")
        val list = mutableListOf<Segment>()
        if (text != null) {
            runCatching {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    if (v.optString("videoID") != id) continue
                    val segs = v.optJSONArray("segments") ?: continue
                    for (k in 0 until segs.length()) {
                        val s = segs.getJSONObject(k)
                        val range = s.optJSONArray("segment") ?: continue
                        val start = (range.optDouble(0) * 1000).toLong()
                        val end = (range.optDouble(1) * 1000).toLong()
                        if (end - start >= 1000) list += Segment(start, end, s.optString("category"))
                    }
                }
            }
        }
        val sorted = list.sortedBy { it.startMs }
        if (text == null) return sorted                // service not reached: ask again next time
        if (cache.size > 100) cache.clear()
        cache[id] = sorted
        return sorted
    }

    fun label(category: String) = when (category) {
        "sponsor" -> "sponsor"
        "selfpromo" -> "self-promotion"
        "interaction" -> "subscribe reminder"
        else -> "segment"
    }
}

/** Return YouTube Dislike (returnyoutubedislikeapi.com): estimated dislike count of a video. */
object Dislikes {
    private val cache = ConcurrentHashMap<String, Pair<Long, Long>>()      // id -> likes, dislikes

    /** Blocking. Likes and dislikes (-1 when unknown). */
    fun votes(id: String): Pair<Long, Long> {
        cache[id]?.let { return it }
        val text = getText("https://returnyoutubedislikeapi.com/votes?videoId=$id") ?: return -1L to -1L
        val v = runCatching { JSONObject(text).let { it.optLong("likes", -1) to it.optLong("dislikes", -1) } }
            .getOrDefault(-1L to -1L)
        if (v.second >= 0) {
            if (cache.size > 200) cache.clear()
            cache[id] = v
        }
        return v
    }

    /** Blocking. -1 when unknown. */
    fun count(id: String): Long = votes(id).second
}

/**
 * Where you stopped in each video (Continue watching on Home, red bars on pictures, resume on open).
 * Kept on the phone only (filesDir/watch.json), newest 80 videos.
 */
object WatchHistory {

    class Entry(
        val url: String, val title: String, val uploader: String, val thumb: String?,
        val posMs: Long, val durMs: Long, val at: Long
    ) {
        val fraction: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
        /** Started (10+ s) and not finished (more than 20 s and 5% left). */
        val unfinished: Boolean get() = posMs >= 10_000 && durMs > 0 && durMs - posMs > 20_000 && fraction < 0.95f
    }

    private const val MAX = 80
    private val entries = LinkedHashMap<String, Entry>()        // by video id, oldest first
    private var loaded = false
    private var dirty = false
    private var lastWrite = 0L

    @Volatile var version = 0                                    // changes on every update (lists refresh)
        private set

    private fun file(c: Context) = File(c.applicationContext.filesDir, "watch.json")

    @Synchronized
    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(file(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val e = Entry(
                    o.getString("u"), o.optString("t"), o.optString("a"), o.optString("th").ifBlank { null },
                    o.optLong("p"), o.optLong("d"), o.optLong("at")
                )
                youtubeId(e.url)?.let { entries[it] = e }
            }
        }
    }

    /** Saves the position in a video (written to disk at most every 10 s, or with [force]). */
    @Synchronized
    fun save(c: Context, url: String, title: String, uploader: String, thumb: String?, posMs: Long, durMs: Long, force: Boolean = false) {
        val id = youtubeId(url) ?: return
        if (durMs <= 0 || posMs < 0) return
        load(c)
        val old = entries.remove(id)
        entries[id] = Entry(
            url, title.ifBlank { old?.title.orEmpty() }, uploader.ifBlank { old?.uploader.orEmpty() },
            thumb ?: old?.thumb, posMs, durMs, System.currentTimeMillis()
        )
        while (entries.size > MAX) entries.remove(entries.keys.first())
        dirty = true
        version++
        val now = System.currentTimeMillis()
        if (force || now - lastWrite > 10_000) flush(c)
    }

    @Synchronized
    fun flush(c: Context) {
        if (!dirty) return
        dirty = false
        lastWrite = System.currentTimeMillis()
        val arr = JSONArray()
        entries.values.forEach { e ->
            arr.put(JSONObject().put("u", e.url).put("t", e.title).put("a", e.uploader).put("th", e.thumb ?: "")
                .put("p", e.posMs).put("d", e.durMs).put("at", e.at))
        }
        val text = arr.toString()
        val f = file(c)
        writer.execute {
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    /** One writer thread: saves never overlap and land in order. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Reads the saved list early (off the main thread), so lists show their bars at once. */
    fun preload(c: Context) = load(c)

    /** Where to start this video: the saved spot when it was started and not finished, else 0. */
    @Synchronized
    fun resumeAt(c: Context, url: String): Long {
        load(c)
        val e = youtubeId(url)?.let { entries[it] } ?: return 0
        return if (e.unfinished) e.posMs else 0
    }

    /** 0..1 watched, or -1 when never watched. */
    @Synchronized
    fun progress(c: Context, url: String): Float {
        load(c)
        val e = youtubeId(url)?.let { entries[it] } ?: return -1f
        return if (e.posMs < 10_000) -1f else if (e.unfinished) e.fraction else 1f
    }

    /** Videos to continue, newest first. */
    @Synchronized
    fun continueList(c: Context): List<Entry> {
        load(c)
        return entries.values.filter { it.unfinished }.sortedByDescending { it.at }.take(20)
    }

    @Synchronized
    fun remove(c: Context, url: String) {
        load(c)
        youtubeId(url)?.let { entries.remove(it) }
        dirty = true
        version++
        flush(c)
    }
}
