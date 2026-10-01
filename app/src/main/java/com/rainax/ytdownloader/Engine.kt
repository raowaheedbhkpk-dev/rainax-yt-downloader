package com.rainax.ytdownloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** One-time initialisation of yt-dlp + FFmpeg, shared by the UI and the download service. */
object Engine {
    private var ready = false

    @Synchronized
    fun ensureInit(context: Context) {
        if (ready) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        ready = true
    }
}
