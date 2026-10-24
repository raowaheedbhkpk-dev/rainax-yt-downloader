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
        val audio = FormatChoice("audio:m4a", "Audio (M4A)", "Original quality, ready at once (no converting)", formatSize(audioBytes), KIND_AUDIO, bytes = audioBytes)
        // MP3: YouTube has none, so it is made on the phone from the original sound (128 kbit/s = 16 KB per second)
        fun mp3(kbps: Int, spec: String, what: String) = FormatChoice(
            spec, "Audio (MP3 $kbps kbps)", what,
            if (duration > 0) formatSize(duration * kbps * 125L) else "", KIND_AUDIO, bytes = duration * kbps * 125L
        )
        val mp3Small = mp3(128, "audio:mp3:128", "Smallest MP3, made on your phone")
        val mp3 = mp3(192, "audio:mp3", "Standard MP3, made on your phone")
        val mp3Best = mp3(320, "audio:mp3:320", "Highest MP3 bitrate, made on your phone")

        // ----- Video -----
        val heights = (progressive.keys + videoOnly.keys).filter { it >= 144 }.toSortedSet().toList()
        // An upright video (Shorts, phone videos) comes as 1080x1920: its "height" 1920 was shown as 2K and
        // 3840 as 5K. People know it by its short side (1080p), so that is what the sheet says.
        val portrait = heights.any { it in PORTRAIT_ONLY }
        fun shown(h: Int) = if (!portrait) h else PORTRAIT[h] ?: (h * 9 / 16)
        val videos = if (heights.isEmpty()) {
            listOf(FormatChoice("video:0", "Best quality", "MP4", "", KIND_VIDEO))
        } else heights.map { h ->
            val total = progressive[h]?.takeIf { it > 0 }
                ?: ((videoOnly[h] ?: 0L).let { if (it > 0) it + bestAudio else 0L })
            val q = shown(h)
            FormatChoice(
                "video:$h", videoTitle(q), videoDesc(q), formatSize(total), KIND_VIDEO,
                badge = if (q <= 144) "Low" else null, bytes = total, quality = q
            )
        }
        fun heightOf(c: FormatChoice) = c.quality.takeIf { it > 0 } ?: (c.spec.substringAfter(":").toIntOrNull() ?: 0)
        val quickVideo = if (heights.isEmpty()) videos else listOfNotNull(
            videos.minByOrNull { abs(heightOf(it) - 360) },
            videos.minByOrNull { abs(heightOf(it) - 720) },
            videos.minByOrNull { abs(heightOf(it) - 1080) }
        ).distinctBy { it.spec }.sortedBy { heightOf(it) }

        return Built(
            quick = listOf(audio, mp3Small, mp3Best) + quickVideo,
            all = listOf(audio, mp3Small, mp3, mp3Best) + videos,
            subs = subs
        )
    }

    /** Upright videos: their height to the quality name (1920 tall = 1080p). */
    private val PORTRAIT = mapOf(256 to 144, 426 to 240, 640 to 360, 854 to 480, 1280 to 720, 1920 to 1080,
        2560 to 1440, 3840 to 2160, 7680 to 4320)
    /** Heights only an upright video has (a wide one is 144, 240, 360, 480...). */
    private val PORTRAIT_ONLY = setOf(256, 426, 640, 854)

    private fun videoTitle(h: Int) = when {
        h >= 4320 -> "High quality (8K)"
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
            // read the bytes once, then decode at most ~640 px wide (a huge picture can't run the phone out of memory)
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 640) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
