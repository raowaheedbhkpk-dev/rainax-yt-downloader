package com.rainax.ytdownloader

import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.rainax.ytdownloader.databinding.SheetDownloadBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The words on a quality card: "High Quality" / "720p", "MP3 · Best" / "320k"... (shared by every download sheet). */
object DlCardText {
    fun height(spec: String) = spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0

    /** (small line, big line) for [c]. */
    fun of(c: FormatChoice): Pair<String, String> {
        if (c.kind == KIND_AUDIO) {
            val parts = c.spec.split(':')
            return when (val ext = parts.getOrNull(1).orEmpty()) {
                "m4a" -> "Original · Instant" to "M4A"
                "mp3" -> {
                    val kbps = parts.getOrNull(2)?.toIntOrNull() ?: 192
                    (when { kbps <= 128 -> "MP3 · Small"; kbps >= 320 -> "MP3 · Best"; else -> "MP3 · Standard" }) to "${kbps}k"
                }
                "" -> "Sound only" to "Audio"
                else -> "Sound only" to ext.uppercase()
            }
        }
        val h = height(c.spec)
        val q = when { h >= 4320 -> "8K"; h >= 2160 -> "4K"; h >= 1440 -> "2K"; h > 0 -> "${h}p"; else -> "Best" }
        val tag = when { h == 0 -> "MP4"; h < 720 -> "Fast"; h < 1440 -> "High Quality"; else -> "Ultra HD" }
        return tag to q
    }

    /** The suggested choice: 720p (or nearest) for video, M4A for sound. */
    fun recommended(list: List<FormatChoice>, kind: Int): FormatChoice? {
        if (list.size < 2) return list.firstOrNull()
        return if (kind == KIND_AUDIO) list.firstOrNull { it.spec == "audio:m4a" } ?: list.first()
        else list.filter { height(it.spec) > 0 }.minByOrNull { kotlin.math.abs(height(it.spec) - 720) } ?: list.first()
    }
}

/**
 * The download sheet for one video, for screens outside the main one (Shorts): the same look as everywhere —
 * picture and title, Video / Audio tabs, quality cards with sizes and a Recommended pick, More formats, and the
 * bar with "Selected" and DOWNLOAD.
 */
object QuickSheet {

    fun show(act: AppCompatActivity, url: String, knownTitle: String?, thumb: String?, onAdded: () -> Unit = {}) {
        val sb = SheetDownloadBinding.inflate(act.layoutInflater)
        val dialog = BottomSheetDialog(act)
        dialog.setContentView(sb.root)
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        }
        sb.dlThumbBox.clipToOutline = true
        sb.closeBtn.setOnClickListener { dialog.dismiss() }

        var state: PreviewState? = null
        var loading = true
        var showAll = false
        var tabKind = KIND_VIDEO
        var selVideo: String? = null
        var selAudio: String? = null
        fun selected() = if (tabKind == KIND_AUDIO) selAudio else selVideo
        fun select(spec: String?) { if (tabKind == KIND_AUDIO) selAudio = spec else selVideo = spec }

        var render: () -> Unit = {}
        val cards = DlCardAdapter({ select(it.spec); render() }, { showAll = true; render() })
        val list = FormatAdapter({ select(it.spec); render() }, {})
        val grid = GridLayoutManager(act, 2)
        val linear = LinearLayoutManager(act)
        sb.formatList.layoutManager = grid
        sb.formatList.adapter = cards
        sb.formatList.itemAnimator = null
        sb.formatList.layoutAnimation = android.view.animation.AnimationUtils.loadLayoutAnimation(act, R.anim.rx_list_rise)
        Img.load(sb.dlThumb, thumb, widthPx = 320)

        render = {
            val st = state
            val all = st?.all.orEmpty().ifEmpty { st?.quick.orEmpty() }
            val quick = st?.quick.orEmpty().ifEmpty { all }
            val hasVideo = all.any { it.kind == KIND_VIDEO }
            val hasAudio = all.any { it.kind == KIND_AUDIO }
            if (!hasVideo && hasAudio) tabKind = KIND_AUDIO
            if (!hasAudio && hasVideo) tabKind = KIND_VIDEO
            val tabQuick = quick.filter { it.kind == tabKind }
            val tabAll = all.filter { it.kind == tabKind }
            if (tabQuick.isNotEmpty() && (selected() == null || tabAll.none { it.spec == selected() })) {
                select(DlCardText.recommended(tabQuick, tabKind)?.spec)
            }
            val error = st?.error
            val title = st?.title?.takeIf { it.isNotBlank() } ?: knownTitle?.takeIf { it.isNotBlank() } ?: "Download"
            sb.sheetHeading.text = if (showAll) "More formats" else title
            sb.backBtn.isVisible = showAll
            sb.dlThumbBox.isVisible = !showAll
            if (st?.thumbUrl != null && sb.dlThumb.getTag(R.id.img_url) != st.thumbUrl) Img.load(sb.dlThumb, st.thumbUrl, widthPx = 320)
            val duration = st?.subtitle?.let { Regex("(\\d+:)?\\d{1,2}:\\d{2}$").find(it.trim())?.value }
            sb.dlDuration.isVisible = duration != null
            sb.dlDuration.text = duration
            sb.sheetSub.text = when {
                showAll -> title
                loading -> "Getting sizes…"
                error != null -> error
                else -> "Choose a format to download"
            }
            sb.previewLoading.isVisible = loading
            sb.errorBlock.isVisible = error != null && !loading
            sb.errorText.text = error
            sb.dlTabs.isVisible = (hasVideo && hasAudio) || loading
            fun styleTab(box: View, icon: android.widget.ImageView, text: android.widget.TextView, on: Boolean) {
                val color = if (on) Color.WHITE else ContextCompat.getColor(act, R.color.dl_text2)
                box.setBackgroundResource(if (on) R.drawable.bg_dl_tab_on else 0)
                box.elevation = if (on) 4 * act.resources.displayMetrics.density else 0f
                text.setTextColor(color)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            }
            styleTab(sb.tabVideo, sb.tabVideoIcon, sb.tabVideoText, tabKind == KIND_VIDEO)
            styleTab(sb.tabAudio, sb.tabAudioIcon, sb.tabAudioText, tabKind == KIND_AUDIO)

            if (!showAll) {
                val rec = DlCardText.recommended(tabQuick, tabKind)?.spec
                val items = tabQuick.map { c ->
                    val (tag, q) = DlCardText.of(c)
                    DlCard.Format(c, tag, q, c.size.ifBlank { "Size unknown" }, c.spec == rec && tabQuick.size > 1, c.spec == selected())
                }.toMutableList<DlCard>()
                val more = tabAll.count { a -> tabQuick.none { it.spec == a.spec } }
                if (more > 0) items += DlCard.More("View all ${tabAll.size}")
                if (sb.formatList.adapter !== cards) { sb.formatList.layoutManager = grid; sb.formatList.adapter = cards }
                cards.submit(if (loading || error != null) emptyList() else items)
            } else {
                if (sb.formatList.adapter !== list) { sb.formatList.layoutManager = linear; sb.formatList.adapter = list }
                val rows = mutableListOf<FormatRow>(FormatRow.Section(if (tabKind == KIND_AUDIO) "Audio" else "Video"))
                tabAll.forEach { rows += FormatRow.Choice(it, it.spec == selected(), true) }
                list.submitList(rows)
            }
            val sel = all.firstOrNull { it.spec == selected() }
            sb.selectedLabel.text = when {
                sel != null -> DlCardText.of(sel).second + if (sel.size.isNotBlank()) " (${sel.size})" else ""
                loading -> "Getting sizes…"
                else -> "Nothing selected"
            }
            sb.downloadBtn.isEnabled = sel != null
            sb.downloadBtn.alpha = if (sel != null) 1f else 0.5f
            sb.root.minimumHeight = (act.resources.displayMetrics.heightPixels * if (showAll) 0.9 else 0.72).toInt()
        }

        sb.tabVideo.setOnClickListener { if (tabKind != KIND_VIDEO) { tabKind = KIND_VIDEO; sb.formatList.scheduleLayoutAnimation(); render() } }
        sb.tabAudio.setOnClickListener { if (tabKind != KIND_AUDIO) { tabKind = KIND_AUDIO; sb.formatList.scheduleLayoutAnimation(); render() } }
        sb.backBtn.setOnClickListener { showAll = false; render() }

        fun load() {
            loading = true
            render()
            act.lifecycleScope.launch {
                val st = withContext(Dispatchers.IO) {
                    runCatching { FastExtractor.fetch(url) }.getOrElse { PreviewState(error = friendlyError(it.message ?: "Couldn't read this video")) }
                }
                loading = false
                state = st
                if (dialog.isShowing) { sb.formatList.scheduleLayoutAnimation(); render() }
            }
        }
        sb.retryBtn.setOnClickListener { load() }

        sb.downloadBtn.setOnClickListener {
            val spec = selected() ?: return@setOnClickListener
            val st = state
            val title = st?.title?.takeIf { it.isNotBlank() } ?: knownTitle.orEmpty()
            Downloader.enqueueAsync(act.applicationContext, listOf(EnqueueItem(url, title, null, st?.thumbUrl ?: thumb)), spec, null, st)
            sb.downloadBtn.isEnabled = false
            DlCardAdapter.tear(sb.formatList) {
                if (act.isFinishing || act.isDestroyed) return@tear
                dialog.dismiss()
                onAdded()
            }
        }
        dialog.show()
        load()
    }
}
