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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The words on a quality card: "High Quality" / "720p", "MP3 · Best" / "320k"... (shared by every download sheet). */
object DlCardText {
    fun height(spec: String) = spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0

    /** The quality people know ([FormatChoice.quality]: an upright video's short side), else the height. */
    fun height(c: FormatChoice) = c.quality.takeIf { it > 0 } ?: height(c.spec)

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
        val h = height(c)
        val q = when { h >= 4320 -> "8K"; h >= 2160 -> "4K"; h >= 1440 -> "2K"; h > 0 -> "${h}p"; else -> "Best" }
        val tag = when { h == 0 -> "MP4"; h < 720 -> "Fast"; h < 1440 -> "High Quality"; else -> "Ultra HD" }
        return tag to q
    }

    /** The suggested choice: 720p (or nearest) for video, M4A for sound. */
    fun recommended(list: List<FormatChoice>, kind: Int): FormatChoice? {
        if (list.size < 2) return list.firstOrNull()
        return if (kind == KIND_AUDIO) list.firstOrNull { it.spec == "audio:m4a" } ?: list.first()
        else list.filter { height(it) > 0 }.minByOrNull { kotlin.math.abs(height(it) - 720) } ?: list.first()
    }
}

/**
 * The download sheet for one video, for screens outside the main one (Shorts): the same look as everywhere —
 * picture and title, Video / Audio tabs, quality cards with sizes and a Recommended pick, More formats, and the
 * bar with "Selected" and DOWNLOAD.
 */
object QuickSheet {

    fun show(act: AppCompatActivity, url: String, knownTitle: String?, thumb: String?, onClosed: () -> Unit = {}, onAdded: () -> Unit = {}) {
        val ui = DlSheetUi()
        ui.thumbUrl = thumb

        var state: PreviewState? = null
        var loading = true
        var showAll = false
        var downloading = false
        var tabKind = KIND_VIDEO
        var selVideo: String? = null
        var selAudio: String? = null
        fun selected() = if (tabKind == KIND_AUDIO) selAudio else selVideo
        fun select(spec: String?) { if (tabKind == KIND_AUDIO) selAudio = spec else selVideo = spec }

        var render: () -> Unit = {}
        var load: () -> Unit = {}
        var download: () -> Unit = {}
        var dialogRef: BottomSheetDialog? = null
        val (dialog, view) = DlSheet.create(act, ui, DlSheetActions(
            close = { dialogRef?.dismiss() },
            back = { showAll = false; render() },
            tab = { k -> if (tabKind != k) { tabKind = k; ui.riseKey++; render() } },
            pick = { select(it.spec); render() },
            more = { showAll = true; render() },
            nav = {},
            retry = { load() },
            download = { download() }
        ))
        dialogRef = dialog
        dialog.setOnDismissListener { onClosed() }

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
            ui.heading = if (showAll) "More formats" else title
            ui.back = showAll
            ui.showThumb = !showAll
            if (st?.thumbUrl != null) ui.thumbUrl = st.thumbUrl
            ui.duration = st?.subtitle?.let { Regex("(\\d+:)?\\d{1,2}:\\d{2}$").find(it.trim())?.value }
            ui.sub = when {
                showAll -> title
                loading -> "Getting sizes…"
                error != null -> error
                else -> "Choose a format to download"
            }
            ui.loading = loading
            ui.error = if (!loading) error else null
            ui.showTabs = (hasVideo && hasAudio) || loading
            ui.tabKind = tabKind
            ui.listMode = showAll
            if (!showAll) {
                val rec = DlCardText.recommended(tabQuick, tabKind)?.spec
                val items = tabQuick.map { c ->
                    val (tag, q) = DlCardText.of(c)
                    DlCard.Format(c, tag, q, c.size.ifBlank { "Size unknown" }, c.spec == rec && tabQuick.size > 1, c.spec == selected())
                }.toMutableList<DlCard>()
                val more = tabAll.count { a -> tabQuick.none { it.spec == a.spec } }
                if (more > 0) items += DlCard.More("View all ${tabAll.size}")
                ui.cards = if (loading || error != null) emptyList<DlCard>() else items
            } else {
                val rows = mutableListOf<FormatRow>(FormatRow.Section(if (tabKind == KIND_AUDIO) "Audio" else "Video"))
                tabAll.forEach { rows += FormatRow.Choice(it, it.spec == selected(), true) }
                ui.rows = rows
            }
            val sel = all.firstOrNull { it.spec == selected() }
            ui.selectedLabel = when {
                sel != null -> DlCardText.of(sel).second + if (sel.size.isNotBlank()) " (${sel.size})" else ""
                loading -> "Getting sizes…"
                else -> "Nothing selected"
            }
            ui.canDownload = sel != null && !downloading
            ui.tall = showAll
        }

        load = {
            loading = true
            render()
            act.lifecycleScope.launch {
                val st = withContext(Dispatchers.IO) {
                    runCatching { FastExtractor.fetch(url) }.getOrElse { PreviewState(error = friendlyError(it.message ?: "Couldn't read this video")) }
                }
                loading = false
                state = st
                if (dialog.isShowing) { ui.riseKey++; render() }
            }
        }

        download = download@{
            if (downloading) return@download
            val spec = selected() ?: return@download
            val st = state
            val title = st?.title?.takeIf { it.isNotBlank() } ?: knownTitle.orEmpty()
            Downloader.enqueueAsync(act.applicationContext, listOf(EnqueueItem(url, title, null, st?.thumbUrl ?: thumb)), spec, null, st)
            downloading = true
            ui.canDownload = false
            DlSheet.tear(ui, view) {
                if (act.isFinishing || act.isDestroyed) return@tear
                dialog.dismiss()
                onAdded()
            }
        }
        dialog.show()
        load()
    }
}
