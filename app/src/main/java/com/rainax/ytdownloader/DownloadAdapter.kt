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
        /**
         * Last status seen per download, shared by the "downloading" and "finished" lists (a finished item
         * moves from one to the other), so the check mark pops exactly when a download finishes.
         */
        val lastStatus = HashMap<String, Status>()
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
                // a freshly made MP3 also says how long making it took
                val note = if (t.message.startsWith("MP3 made")) "  •  ${t.message}" else ""
                "$kind  •  ${DateUtils.getRelativeTimeSpanString(t.createdAt)}$note"
            }
            else -> t.message.ifBlank { t.status.name.lowercase().replaceFirstChar { it.uppercase() } }
        }
        val colorAttr = if (t.status == Status.FAILED)
            com.google.android.material.R.attr.colorError
        else
            com.google.android.material.R.attr.colorOnSurfaceVariant
        b.status.setTextColor(MaterialColors.getColor(b.status, colorAttr))

        // small coloured pill: what the download is doing right now (finished ones need none)
        val pill: Pair<String, Int>? = when (t.status) {
            Status.RUNNING -> "Downloading" to Ui.accent(b.root.context)
            Status.QUEUED -> "Queued" to 0xFF6E6E79.toInt()
            Status.PAUSED -> "Paused" to 0xFFE08A00.toInt()
            Status.WAITING -> "Waiting" to 0xFF3D7BE0.toInt()
            Status.FAILED -> "Failed" to 0xFFD32F2F.toInt()
            Status.DONE -> null
        }
        // running with live numbers: the stat strip (speed / time left / size) says it all, no pill needed
        val live = if (t.status == Status.RUNNING) LiveStats.get(t.id) else null
        b.statsRow.isVisible = live != null
        if (live != null) {
            b.status.text = qualityLabel(t)
            b.status.setTextColor(MaterialColors.getColor(b.status, com.google.android.material.R.attr.colorOnSurfaceVariant))
            val sp = LiveStats.split(live.bytesPerSec)
            b.statSpeed.text = if (live.bytesPerSec > 1) unit(b.root.context, sp.first, sp.second + "/s") else "—"
            b.statLeft.text = LiveStats.eta(live.etaSec)
            val got = LiveStats.split(live.done.toDouble())
            b.statSize.text = if (live.total > 0) {
                val all = LiveStats.split(live.total.toDouble())
                unit(b.root.context, got.first, "/ ${all.first} ${all.second}")
            } else unit(b.root.context, got.first, got.second)
        }
        b.statusPill.isVisible = pill != null && live == null
        if (pill != null && live == null) {
            val d = b.root.resources.displayMetrics.density
            b.statusPill.text = pill.first
            b.statusPill.setTextColor(pill.second)
            b.statusPill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 2 * d
                setColor((pill.second and 0x00FFFFFF) or 0x24000000)
            }
        }

        // the moment a download finishes: a red check pops over the picture, then fades away
        val before = lastStatus.put(t.id, t.status)
        b.doneCheck.animate().cancel()
        if (t.status == Status.DONE && before != null && before != Status.DONE) {
            val v = b.doneCheck
            v.isVisible = true
            v.alpha = 0f; v.scaleX = 0.4f; v.scaleY = 0.4f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
                .withEndAction {
                    v.animate().alpha(0f).setStartDelay(1300).setDuration(400)
                        .withEndAction { v.isVisible = false; v.alpha = 1f }.start()
                }.start()
        } else {
            b.doneCheck.isVisible = false
        }

        bindThumb(b.thumb, t)

        // projector: the reel turns while this one downloads
        val running = t.status == Status.RUNNING
        b.reelBox.isVisible = running
        if (running) {
            b.reelPct.text = if (t.progress in 1..100) "${t.progress}%" else "···"
            if (b.reel.animation == null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                b.reel.startAnimation(android.view.animation.RotateAnimation(0f, 360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 1400
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                })
            }
        } else if (b.reel.animation != null) {
            b.reel.clearAnimation()
        }
        val showProgress = t.status == Status.RUNNING || t.status == Status.QUEUED ||
            t.status == Status.PAUSED || t.status == Status.WAITING
        b.progressRow.isVisible = showProgress
        if (showProgress) {
            b.progress.setProgressCompat(t.progress, true)
            b.progressPct.text = if (t.progress in 1..100) "${t.progress}%" else ""
        }

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

    /** "2.4" big with " MB/s" smaller and grey after it. */
    private fun unit(c: android.content.Context, number: String, unit: String): CharSequence {
        val sb = android.text.SpannableStringBuilder(number).append(' ')
        val start = sb.length
        sb.append(unit)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.6f), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(c, R.color.rx_text2)),
            start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** What is being saved: "720p video", "Best quality video", "M4A audio", "App update". */
    private fun qualityLabel(t: DownloadTask): String {
        val f = t.format
        return when {
            f.startsWith(AppUpdater.TASK_PREFIX) -> "App update"
            f.startsWith("audio:") -> f.removePrefix("audio:").substringBefore(':').uppercase().ifBlank { "Audio" } + " audio"
            f.startsWith("video:") -> {
                val q = f.removePrefix("video:").substringBefore(':').toIntOrNull() ?: 0
                if (q > 0) "${q}p video" else "Best quality video"
            }
            t.isAudio -> "Audio"
            else -> "Video"
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

/**
 * One header row (a section title with its buttons) inside the downloads list. The same [view] is kept and
 * moved into whichever row shows it, so its buttons and texts can be changed from outside at any time.
 */
class SectionHeader(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Shows or hides the row. */
    var visible = false
        set(v) {
            if (field == v) return
            field = v
            if (v) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount() = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val box = android.widget.FrameLayout(parent.context)
        box.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return object : RecyclerView.ViewHolder(box) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val box = holder.itemView as ViewGroup
        view.isVisible = true
        if (view.parent === box) return
        (view.parent as? ViewGroup)?.removeView(view)
        box.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
