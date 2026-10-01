package com.rainax.ytdownloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ResolvedAudio(val url: String, val title: String, val headers: Map<String, String>)

/**
 * Finds the direct audio address of a video. It is looked up quietly while you watch, so the headphones button
 * can start the audio at once instead of waiting for yt-dlp.
 */
object StreamResolver {
    private class Entry(val deferred: Deferred<ResolvedAudio>, val at: Long) {
        @Volatile var failed = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entries = ConcurrentHashMap<String, Entry>()
    private const val TTL = 4 * 60 * 60 * 1000L

    private val ID = Regex("(?:[?&]v=|youtu\\.be/|shorts/)([\\w-]{6,})")

    fun key(url: String): String? = ID.find(url)?.groupValues?.get(1)

    /** Starts (or joins) the lookup for this video. */
    @Synchronized
    fun request(context: Context, url: String): Deferred<ResolvedAudio>? {
        val k = key(url) ?: return null
        val old = entries[k]
        if (old != null && !old.failed && System.currentTimeMillis() - old.at < TTL) return old.deferred
        val app = context.applicationContext
        lateinit var entry: Entry
        val d = scope.async {
            try {
                resolve(app, url, k)
            } catch (e: Exception) {
                entry.failed = true
                throw e
            }
        }
        entry = Entry(d, System.currentTimeMillis())
        entries[k] = entry
        return d
    }

    fun isReady(url: String): Boolean {
        val e = entries[key(url) ?: return false] ?: return false
        return e.deferred.isCompleted && !e.failed
    }

    private fun resolve(context: Context, url: String, k: String): ResolvedAudio {
        Engine.ensureInit(context)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
            addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
        }
        val out = YoutubeDL.getInstance().execute(request, "res-$k", null).out
        val start = out.indexOf('{')
        if (start < 0) error("No data returned for this link")
        val json = JSONObject(out.substring(start))
        var stream = json.optString("url").takeIf { it.startsWith("http") }
        var headerSrc = json.optJSONObject("http_headers")
        if (stream == null) {
            val f = json.optJSONArray("requested_formats")?.optJSONObject(0)
            stream = f?.optString("url")?.takeIf { it.startsWith("http") }
            headerSrc = f?.optJSONObject("http_headers") ?: headerSrc
        }
        if (stream == null) error("No playable audio found")
        val headers = mutableMapOf<String, String>()
        val hs = headerSrc
        if (hs != null) {
            val keys = hs.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                headers[name] = hs.optString(name)
            }
        }
        val title = json.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "Audio"
        return ResolvedAudio(stream, title, headers)
    }
}
