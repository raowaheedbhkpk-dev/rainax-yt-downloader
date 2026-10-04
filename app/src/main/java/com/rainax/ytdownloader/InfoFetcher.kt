package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** Helpers for the download sheet: quality choices, thumbnails. */
object InfoFetcher {

    internal class Built(val quick: List<FormatChoice>, val all: List<FormatChoice>, val subs: List<SubtitleOption>)

    /**
     * Turns raw stream data (height -> bytes) into the choices shown in the download sheet.
     * Audio is saved in its original quality (M4A, no re-encoding, so it is instant).
     */
    internal fun buildChoices(
        progressive: Map<Int, Long>,
        videoOnly: Map<Int, Long>,
        bestAudio: Long,
        bestM4a: Long,
        duration: Int,
        subs: List<SubtitleOption>
    ): Built {
        // ----- Music -----
        val audioBytes = when {
            bestM4a > 0 -> bestM4a
            bestAudio > 0 -> bestAudio
            else -> duration * 16_000L
        }
        val audio = FormatChoice("audio:m4a", "Audio (M4A)", "Original quality, plays on all phones", formatSize(audioBytes), KIND_AUDIO)
        // MP3 192 kbit/s = 24 KB per second
        val mp3 = FormatChoice("audio:mp3", "Audio (MP3)", "192 kbps, plays everywhere (converted on your phone)",
            if (duration > 0) formatSize(duration * 24_000L) else "", KIND_AUDIO)

        // ----- Video -----
        val heights = (progressive.keys + videoOnly.keys).filter { it >= 144 }.toSortedSet().toList()
        val videos = if (heights.isEmpty()) {
            listOf(FormatChoice("video:0", "Best quality", "MP4", "", KIND_VIDEO))
        } else heights.map { h ->
            val total = progressive[h]?.takeIf { it > 0 }
                ?: ((videoOnly[h] ?: 0L).let { if (it > 0) it + bestAudio else 0L })
            FormatChoice(
                "video:$h", videoTitle(h), videoDesc(h), formatSize(total), KIND_VIDEO,
                badge = if (h <= 144) "Low" else null
            )
        }
        fun heightOf(c: FormatChoice) = c.spec.substringAfter(":").toIntOrNull() ?: 0
        val quickVideo = if (heights.isEmpty()) videos else listOfNotNull(
            videos.minByOrNull { abs(heightOf(it) - 360) },
            videos.minByOrNull { abs(heightOf(it) - 720) },
            videos.minByOrNull { abs(heightOf(it) - 1080) }
        ).distinctBy { it.spec }.sortedBy { heightOf(it) }

        return Built(
            quick = listOf(audio, mp3) + quickVideo,
            all = listOf(audio, mp3) + videos,
            subs = subs
        )
    }

    private fun videoTitle(h: Int) = when {
        h >= 4320 -> "High quality (8K)"
        h >= 2880 -> "High quality (5K)"
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

    fun saveThumb(context: Context, id: String, bitmap: Bitmap): String? = try {
        val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
        val file = File(dir, "$id.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    internal fun loadBitmap(url: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", FastExtractor.UA)
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
