package com.rainax.ytdownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rainax.ytdownloader.databinding.ItemTaskBinding
import com.google.android.material.color.MaterialColors

class DownloadAdapter(
    private val onAction: (DownloadTask) -> Unit,
    private val onClose: (DownloadTask, View) -> Unit,
    private val onOpen: (DownloadTask) -> Unit,
    private val onToggle: (DownloadTask) -> Unit,
    private val onLongPress: (DownloadTask) -> Unit
) : ListAdapter<DownloadTask, DownloadAdapter.VH>(Diff) {

    private val cache = LruCache<String, Bitmap>(80)

    private companion object {
        val decoder: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2)
    }

    private var selecting = false
    private var selectedIds: Set<String> = emptySet()

    /** Shows or hides the checkboxes and ticks the selected rows. */
    fun setSelection(on: Boolean, ids: Set<String>) {
        selecting = on
        selectedIds = ids.toSet()
        notifyDataSetChanged()
    }

    class VH(val b: ItemTaskBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemTaskBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = getItem(position)
        val b = holder.b

        b.title.text = t.title.ifBlank { t.url }
        b.status.text = when (t.status) {
            Status.DONE -> {
                val kind = when {
                    t.format.startsWith(AppUpdater.TASK_PREFIX) -> "App update  •  Tap to install"
                    t.isAudio -> "Audio"
                    else -> "Video"
                }
                "$kind  •  ${DateUtils.getRelativeTimeSpanString(t.createdAt)}"
            }
            else -> t.message.ifBlank { t.status.name.lowercase().replaceFirstChar { it.uppercase() } }
        }
        val colorAttr = if (t.status == Status.FAILED)
            com.google.android.material.R.attr.colorError
        else
            com.google.android.material.R.attr.colorOnSurfaceVariant
        b.status.setTextColor(MaterialColors.getColor(b.status, colorAttr))

        bindThumb(b.thumb, t)

        val showProgress = t.status == Status.RUNNING || t.status == Status.QUEUED ||
            t.status == Status.PAUSED || t.status == Status.WAITING
        b.progress.isVisible = showProgress
        if (showProgress) b.progress.setProgressCompat(t.progress, true)

        b.actionBtn.setIconResource(
            when (t.status) {
                Status.RUNNING, Status.QUEUED, Status.WAITING -> R.drawable.ic_pause
                Status.PAUSED, Status.DONE -> R.drawable.ic_play
                Status.FAILED -> R.drawable.ic_refresh
            }
        )
        b.actionBtn.contentDescription = when (t.status) {
            Status.RUNNING, Status.QUEUED, Status.WAITING -> "Pause"
            Status.PAUSED -> "Resume"
            Status.FAILED -> "Retry"
            Status.DONE -> "Open"
        }
        // Finished items get a menu (share / delete); others get a cancel button
        b.closeBtn.setIconResource(if (t.status == Status.DONE) R.drawable.ic_more else R.drawable.ic_close)
        b.closeBtn.contentDescription = if (t.status == Status.DONE) "More" else "Cancel"

        b.actionBtn.setOnClickListener { if (t.status == Status.DONE) onOpen(t) else onAction(t) }
        b.closeBtn.setOnClickListener { onClose(t, it) }
        b.selectBox.isVisible = selecting
        b.selectBox.isChecked = t.id in selectedIds
        b.actionBtn.isVisible = !selecting
        b.closeBtn.isVisible = !selecting
        b.root.setOnClickListener {
            if (selecting) onToggle(t) else onOpen(t)          // done: open the file; still downloading: watch it now
        }
        b.root.setOnLongClickListener {
            onLongPress(t)
            true
        }
    }

    /** Thumbnails are read from disk in the background, so long lists scroll smoothly. */
    private fun bindThumb(view: ImageView, t: DownloadTask) {
        val path = t.thumbPath
        view.tag = path
        val cached = path?.let { cache.get(it) }
        if (cached != null) {
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(cached)
            return
        }
        view.scaleType = ImageView.ScaleType.CENTER
        view.setImageResource(if (t.isAudio) R.drawable.ic_audio else R.drawable.ic_video)
        if (path == null) return
        decoder.execute {
            val bmp = try {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
            } catch (e: Throwable) { null } ?: return@execute
            view.post {
                cache.put(path, bmp)
                if (view.tag == path) {                     // the row still shows this download
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageBitmap(bmp)
                }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DownloadTask>() {
        override fun areItemsTheSame(a: DownloadTask, b: DownloadTask) = a.id == b.id
        override fun areContentsTheSame(a: DownloadTask, b: DownloadTask) = a == b
    }
}
