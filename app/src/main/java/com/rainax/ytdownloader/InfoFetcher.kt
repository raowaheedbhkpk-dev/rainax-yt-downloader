package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

/** Reads info (title, thumbnail, formats with sizes, subtitles) for a link. Blocking. */
object InfoFetcher {

    /** Process id of the lookup started from the download sheet (so it can be cancelled). */
    const val PROCESS_ID = "info"

    private fun str(o: JSONObject, key: String): String? =
        o.optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun dumpJson(
        context: Context,
        url: String,
        cookiesPath: String?,
        processId: String,
        extra: YoutubeDLRequest.() -> Unit
    ): JSONObject {
        Engine.ensureInit(context)
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--no-warnings")
            addOption("--socket-timeout", "20")
            if (cookiesPath != null) addOption("--cookies", cookiesPath)
            extra()
        }
        val out = YoutubeDL.getInstance().execute(request, processId, null).out
        val start = out.indexOf('{')
        if (start < 0) error("No data returned for this link")
        return JSONObject(out.substring(start))
    }

    fun fetch(context: Context, url: String, cookiesPath: String?, processId: String = PROCESS_ID): PreviewState {
        val json = dumpJson(context, url, cookiesPath, processId) { addOption("--no-playlist") }
        val duration = json.optInt("duration", 0)
        val built = build(json, duration)
        val thumb = str(json, "thumbnail")?.takeIf { it.startsWith("http") }?.let { loadBitmap(it) }
        return PreviewState(
            title = str(json, "title") ?: url,
            subtitle = subtitle(str(json, "uploader") ?: str(json, "channel"), duration),
            thumb = thumb,
            quick = built.quick,
            all = built.all,
            subtitles = built.subs
        )
    }

    fun fetchPlaylist(context: Context, url: String, cookiesPath: String?, processId: String = PROCESS_ID): PreviewState {
        val json = dumpJson(context, url, cookiesPath, processId) {
            addOption("--flat-playlist")
            addOption("--playlist-end", "1000")
        }
        val entries = json.optJSONArray("entries") ?: error("No playlist found")
        val list = mutableListOf<PlaylistEntry>()
        var skipped = 0
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            var u = str(e, "url") ?: str(e, "webpage_url") ?: str(e, "id")
            if (u != null && !u.startsWith("http")) u = "https://www.youtube.com/watch?v=$u"
            if (u == null) continue

            val title = str(e, "title").orEmpty()
            val tl = title.lowercase()
            val avail = str(e, "availability").orEmpty().lowercase()
            val unavailable = tl.contains("private video") || tl.contains("deleted video") ||
                (avail == "private" || avail == "needs_auth" ||
                    avail == "premium_only" || avail == "subscriber_only")
            if (unavailable) {
                skipped++
                continue
            }

            val ytId = Regex("[?&]v=([\\w-]{6,})").find(u)?.groupValues?.get(1)
            val thumbs = e.optJSONArray("thumbnails")
            val mid = if (thumbs != null && thumbs.length() > 0) {
                thumbs.optJSONObject(thumbs.length() / 2)?.let { str(it, "url") }
            } else null
            val thumbUrl = ytId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }
                ?: mid ?: str(e, "thumbnail")
            list += PlaylistEntry(u, title, thumbUrl)
        }
        check(list.isNotEmpty()) {
            if (skipped > 0) "All $skipped videos in this playlist are private or deleted." else "Playlist is empty"
        }

        // Untitled entries are usually private or deleted. Try the first ones before queueing dozens of failures.
        if (list.all { it.title.isBlank() }) {
            var ok = false
            var lastError = ""
            for (entry in list.take(2)) {
                try {
                    dumpJson(context, entry.url, cookiesPath, processId) { addOption("--no-playlist") }
                    ok = true
                    break
                } catch (e: Exception) {
                    lastError = e.message.orEmpty()
                }
            }
            if (!ok && lastError.isFatalError()) error(friendlyError(lastError))
        }

        return PreviewState(
            title = str(json, "title") ?: "Playlist",
            subtitle = "${list.size} videos" + if (skipped > 0) "  •  $skipped private/deleted skipped" else "",
            playlist = list
        )
    }

    private class Built(val quick: List<FormatChoice>, val all: List<FormatChoice>, val subs: List<SubtitleOption>)

    private fun build(json: JSONObject, duration: Int): Built {
        val progressive = mutableMapOf<Int, Long>()
        val videoOnly = mutableMapOf<Int, Long>()
        var bestAudio = 0L
        var bestM4a = 0L

        val formats = json.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val v = f.optString("vcodec", "none")
                val a = f.optString("acodec", "none")
                val hasV = v.isNotEmpty() && v != "none" && v != "null"
                val hasA = a.isNotEmpty() && a != "none" && a != "null"
                val size = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L)
                val h = f.optInt("height", 0)
                if (hasV && h > 0) {
                    val map = if (hasA) progressive else videoOnly
                    map[h] = maxOf(map[h] ?: 0L, size)
                } else if (hasA && !hasV) {
                    bestAudio = maxOf(bestAudio, size)
                    if (f.optString("ext") == "m4a") bestM4a = maxOf(bestM4a, size)
                }
            }
        }

        // ----- Music -----
        val m4aBytes = when {
            bestM4a > 0 -> bestM4a
            bestAudio > 0 -> bestAudio
            else -> duration * 16_000L
        }
        val fast = FormatChoice("audio:m4a", "Fast", "Original quality, quick to save", formatSize(m4aBytes), KIND_AUDIO)
        val mp3Quick = FormatChoice("audio:mp3:128", "Classic MP3", "Works on all players", formatSize(duration * 16_000L), KIND_AUDIO)
        val mp3Full = mp3Quick.copy(title = "Classic MP3 (128K)")
        val mp3High = FormatChoice(
            "audio:mp3:320", "Classic MP3 (320K)",
            "Supports Bluetooth speakers, phones, car stereos, smartwatches, etc",
            formatSize(duration * 40_000L), KIND_AUDIO, badge = "Slow"
        )

        // ----- Video -----
        val heights = (progressive.keys + videoOnly.keys).filter { it >= 144 }.toSortedSet().toList()
        val videos = if (heights.isEmpty()) {
            listOf(FormatChoice("video:0", "Best quality", "MP4", "", KIND_VIDEO))
        } else heights.map { h ->
            val total = progressive[h] ?: ((videoOnly[h] ?: 0L).let { if (it > 0) it + bestAudio else 0L })
            FormatChoice(
                "video:$h", videoTitle(h), videoDesc(h), formatSize(total), KIND_VIDEO,
                badge = if (h <= 144) "Low" else null
            )
        }
        fun heightOf(c: FormatChoice) = c.spec.substringAfter(":").toIntOrNull() ?: 0
        val quickVideo = if (heights.isEmpty()) videos else listOfNotNull(
            videos.minByOrNull { abs(heightOf(it) - 360) },
            videos.minByOrNull { abs(heightOf(it) - 720) }
        ).distinctBy { it.spec }.sortedBy { heightOf(it) }

        return Built(
            quick = listOf(fast, mp3Quick) + quickVideo,
            all = listOf(fast, mp3Full, mp3High) + videos,
            subs = subtitleOptions(json)
        )
    }

    private fun videoTitle(h: Int) = when {
        h >= 2160 -> "High quality (4K)"
        h >= 1440 -> "High quality (2K)"
        h >= 720 -> "High quality (${h}p)"
        else -> "Fast (${h}p)"
    }

    private fun videoDesc(h: Int) = when {
        h <= 144 -> "Poor video quality"
        h <= 240 -> "Low quality for quick play"
        h < 720 -> "Normal quality for quick play"
        h == 720 -> "Clear view and quick play"
        h <= 1080 -> "High details for full screen play"
        else -> "High details for big screen play"
    }

    private fun langName(code: String): String {
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return name.ifBlank { code }
    }

    private fun subtitleOptions(json: JSONObject): List<SubtitleOption> {
        val subs = mutableListOf<SubtitleOption>()
        json.optJSONObject("subtitles")?.keys()?.asSequence()
            ?.filter { it != "live_chat" }?.take(30)?.forEach { code ->
                subs += SubtitleOption(code, langName(code))
            }
        json.optJSONObject("automatic_captions")?.let { auto ->
            val keys = auto.keys().asSequence().toList()
            val orig = keys.filter { it.endsWith("-orig") }
            val pick = if (orig.isNotEmpty()) orig else keys.filter { it == "en" }
            pick.forEach { code ->
                if (subs.none { it.code == code }) {
                    subs += SubtitleOption(code, langName(code.removeSuffix("-orig")) + " (auto)")
                }
            }
        }
        return subs
    }

    fun saveThumb(context: Context, id: String, bitmap: Bitmap): String? = try {
        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    private fun subtitle(uploader: String?, seconds: Int): String {
        val parts = mutableListOf<String>()
        if (!uploader.isNullOrBlank()) parts += uploader
        if (seconds > 0) {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            parts += if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
        return parts.joinToString("  •  ")
    }

    internal fun loadBitmap(url: String): Bitmap? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        conn.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }
}
