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

/** The collection shelf on the You page: the newest downloaded files as DVD cases. Tap plays the file. */
class ShelfAdapter(private val onOpen: (DownloadTask) -> Unit) : RecyclerView.Adapter<ShelfAdapter.VH>() {

    private var list: List<DownloadTask> = emptyList()

    fun submit(l: List<DownloadTask>) {
        if (l.map { it.id } == list.map { it.id }) return
        list = l
        notifyDataSetChanged()
    }

    override fun getItemCount() = list.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_case, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) = h.bind(list[position])

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.caseThumb)
        private val kind: TextView = v.findViewById(R.id.caseKind)
        private val title: TextView = v.findViewById(R.id.caseTitle)

        fun bind(t: DownloadTask) {
            val path = t.thumbPath
            if (path != null && File(path).exists()) Img.loadFile(thumb, path) else Img.load(thumb, t.thumbUrl, widthPx = 360)
            kind.text = when {
                t.isAudio -> "MP3"
                t.format.substringAfter(':').toIntOrNull()?.let { it >= 720 } == true -> "HD"
                else -> "VIDEO"
            }
            title.text = t.title
            itemView.contentDescription = "Play ${t.title}"
            itemView.setOnClickListener { onOpen(t) }
        }
    }
}

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
