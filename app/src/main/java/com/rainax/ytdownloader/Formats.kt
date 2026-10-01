package com.rainax.ytdownloader

import com.yausername.youtubedl_android.YoutubeDLRequest

/** Translates a format spec ("video:1080", "audio:mp3:128", ...) into yt-dlp options. */
object Formats {

    fun configure(request: YoutubeDLRequest, rawSpec: String, subLang: String? = null) {
        val parts = rawSpec.split(":")
        val kind = parts[0]
        if (kind == "audio") {
            if (parts.getOrNull(1) == "m4a") {
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "m4a")
            } else {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                val bitrate = parts.getOrNull(2)
                request.addOption("--audio-quality", if (bitrate != null) "${bitrate}K" else "0")
            }
        } else {
            val h = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
            val cap = h?.let { "[height<=$it]" }.orEmpty()
            request.addOption(
                "-f",
                "bv*$cap[ext=mp4]+ba[ext=m4a]/b$cap[ext=mp4]/bv*$cap+ba/b$cap/b"
            )
            request.addOption("--merge-output-format", "mp4")
            if (!subLang.isNullOrBlank()) {
                request.addOption("--write-subs")
                request.addOption("--write-auto-subs")
                request.addOption("--sub-langs", subLang)
                request.addOption("--convert-subs", "srt")
            }
        }
    }
}
