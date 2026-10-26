package com.rainax.ytdownloader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/** Blocking. How much room downloads take (videos, sound) and how much is free on the phone. */
object StorageMeter {

    class Usage(val videoBytes: Long, val audioBytes: Long, val freeBytes: Long)

    private val sizes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun sizeOf(c: Context, uri: String): Long = sizes.getOrPut(uri) {
        runCatching {
            val u = Uri.parse(uri)
            if (u.scheme == "file" || u.scheme == null) File(u.path ?: uri).length()
            else c.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst() && !cur.isNull(0)) cur.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun measure(c: Context, done: List<DownloadTask>): Usage {
        var video = 0L
        var audio = 0L
        done.forEach { t ->
            val u = t.fileUri ?: return@forEach
            val n = sizeOf(c, u)
            if (t.isAudio) audio += n else video += n
        }
        val free = runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(0L)
        return Usage(video, audio, free)
    }

    fun short(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
        else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
