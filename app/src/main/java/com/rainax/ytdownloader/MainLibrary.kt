package com.rainax.ytdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rainax.ytdownloader.databinding.ActivityMainBinding
import com.rainax.ytdownloader.databinding.SheetDownloadBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The You tab: downloads, collection, update pill (part of MainActivity).
// These are MainActivity's own functions, kept in their own file so each part stays small.

internal fun MainActivity.setupPlay() {
    // both sections in one recycling list: only the rows on screen are made, however many downloads there are
    pl.playList.layoutManager = LinearLayoutManager(this)
    (pl.playList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    pl.playList.adapter = androidx.recyclerview.widget.ConcatAdapter(collectionHeader, activeHeader, activeAdapter, doneHeader, doneAdapter)
    applyPlayColumns()                       // tablets: finished downloads in 2-3 columns
    pl.shelfList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    pl.shelfList.adapter = shelf
    pl.shelfList.isNestedScrollingEnabled = false

    pl.emptySearchBtn.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_home }
    pl.selectActiveBtn.setOnClickListener { selectAll(done = false) }
    pl.selectDoneBtn.setOnClickListener { selectAll(done = true) }
    pl.removeAllBtn.setOnClickListener { removeAllDone() }
    pl.deleteAllBtn.setOnClickListener { deleteAllDone() }
    pl.clearFailedBtn.setOnClickListener {
        TaskRepository.removeFailed()
        message("Removed failed downloads")
    }

    // selection bar
    pl.closeSelBtn.setOnClickListener { exitSelection() }
    pl.selAllBtn.setOnClickListener { toggleSelectAll() }
    pl.actPause.setOnClickListener { pauseSelected() }
    pl.actResume.setOnClickListener { resumeSelected() }
    pl.actCancel.setOnClickListener { cancelSelected() }
    pl.actRemove.setOnClickListener { removeSelected() }
    pl.actDelete.setOnClickListener { deleteSelected() }
}

/** Library chips: Downloads shows the download manager, the others show your files. */
internal fun MainActivity.onLibraryMode() {
    if (selecting) exitSelection()
    pl.emptyState.isVisible = latestTasks.isEmpty() && library.showingDownloads
    pl.playScroll.isVisible = latestTasks.isNotEmpty() && library.showingDownloads
}

internal fun MainActivity.renderTasks(all: List<DownloadTask>) {
    latestTasks = all
    val active = all.filter { it.status != Status.DONE }
    val done = all.filter { it.status == Status.DONE }

    // forget selected items that no longer exist
    val before = selected.size
    selected.retainAll(all.map { it.id }.toSet())
    val selectionChanged = before != selected.size || (selecting && selected.isEmpty())
    if (selecting && selected.isEmpty()) selecting = false

    pl.emptyState.isVisible = all.isEmpty() && library.showingDownloads
    pl.playScroll.isVisible = all.isNotEmpty() && library.showingDownloads
    library.onTasks(all)
    checkUpdateReady(all)
    renderUpdatePill(all)
    renderYouBadge(all)
    countForRating(done)

    renderCollection(done)
    activeHeader.visible = active.isNotEmpty()
    pl.clearFailedBtn.isVisible = active.any { it.status == Status.FAILED }
    pl.activeTitle.text = "Downloading (${active.size})"
    // Downloading now always on top, then the queue in the order it will download
    // (oldest first, like the service), then paused and failed ones
    val order = HashMap<String, Int>(all.size).apply { all.forEachIndexed { i, t -> put(t.id, all.size - i) } }
    val newActive = active.sortedWith(
        compareBy<DownloadTask> {
            when (it.status) {
                Status.RUNNING -> 0
                Status.WAITING -> 1
                Status.QUEUED -> 2
                Status.PAUSED -> 3
                else -> 4
            }
        }.thenBy { order[it.id] ?: 0 }
    )
    val topChanged = newActive.firstOrNull()?.id != activeAdapter.currentList.firstOrNull()?.id
    // show the new top download, unless you scrolled far down to look at something else
    val nearTop = pl.playList.computeVerticalScrollOffset() < resources.displayMetrics.heightPixels / 2
    activeAdapter.submitList(newActive) { if (topChanged && nearTop) pl.playList.scrollToPosition(0) }

    doneHeader.visible = done.isNotEmpty()
    pl.doneTitle.text = "Downloaded (${done.size})"
    doneAdapter.submitList(done.sortedByDescending { it.createdAt })      // newest download first

    // (the You badge is drawn by renderYouBadge above: only downloads in progress, not paused or failed ones)

    if (selectionChanged) refreshSelectionUi() else updateSelectionBar()
}

// ----- multi-select -----

internal fun MainActivity.startSelect(t: DownloadTask) {
    selecting = true
    selected.clear()
    selected += t.id
    refreshSelectionUi()
}

internal fun MainActivity.toggleSelect(t: DownloadTask) {
    if (!selected.add(t.id)) selected.remove(t.id)
    if (selected.isEmpty()) selecting = false
    refreshSelectionUi()
}

internal fun MainActivity.toggleSelectAll() {
    val all = latestTasks.map { it.id }
    if (all.isEmpty() || selected.size == all.size) {
        exitSelection()
        return
    }
    selected.clear()
    selected += all
    selecting = true
    refreshSelectionUi()
}

internal fun MainActivity.exitSelection() {
    selecting = false
    selected.clear()
    refreshSelectionUi()
}

internal fun MainActivity.refreshSelectionUi() {
    activeAdapter.setSelection(selecting, selected)
    doneAdapter.setSelection(selecting, selected)
    updateSelectionBar()
    updateBack()
}

internal fun MainActivity.updateSelectionBar() {
    val wasSelecting = pl.selectionActions.isVisible
    pl.selectionBar.isVisible = selecting
    pl.selectionActions.isVisible = selecting
    if (wasSelecting != selecting && b.miniPlayer.root.isVisible) pl.selectionActions.post { placeMiniPlayer() }    // move above / back
    pl.selectionCount.text = "${selected.size} selected"
    if (!selecting) return

    // only show the actions that apply to what is selected
    val sel = selectedTasks()
    pl.actPause.isVisible = sel.any {
        it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
    }
    pl.actResume.isVisible = sel.any { it.status == Status.PAUSED || it.status == Status.FAILED }
    pl.actCancel.isVisible = sel.any { it.status != Status.DONE }
    pl.actRemove.isVisible = sel.any { it.status == Status.DONE }
    pl.actDelete.isVisible = sel.any { it.status == Status.DONE }
}

internal fun MainActivity.selectedTasks() = latestTasks.filter { it.id in selected }

internal fun MainActivity.pauseSelected() {
    val ids = selectedTasks().filter {
        it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
    }.map { it.id }
    if (ids.isEmpty()) message("Nothing to pause")
    else DownloadService.send(this, DownloadService.ACTION_PAUSE_SEL, ids = ids.toTypedArray())
    exitSelection()
}

internal fun MainActivity.resumeSelected() {
    val ids = selectedTasks().filter {
        it.status == Status.PAUSED || it.status == Status.FAILED
    }.map { it.id }
    if (ids.isEmpty()) message("Nothing to resume")
    else DownloadService.send(this, DownloadService.ACTION_RESUME_SEL, ids = ids.toTypedArray())
    exitSelection()
}

/** Cancel: only for unfinished downloads (stops them and deletes partial files). */
internal fun MainActivity.cancelSelected() {
    val ids = selectedTasks().filter { it.status != Status.DONE }.map { it.id }
    if (ids.isEmpty()) return
    confirm("Cancel ${ids.size} download(s)?", "Partial files will be deleted.") {
        DownloadService.send(this, DownloadService.ACTION_CANCEL_SEL, ids = ids.toTypedArray())
        exitSelection()
    }
}

/** Remove: finished items leave the list, the files stay in Downloads. */
internal fun MainActivity.removeSelected() {
    val ids = selectedTasks().filter { it.status == Status.DONE }.map { it.id }
    if (ids.isEmpty()) return
    TaskRepository.removeMany(ids)
    exitSelection()
    message("Removed ${ids.size} from the list")
}

/** Delete: finished items are removed from the list and their files deleted. */
internal fun MainActivity.deleteSelected() {
    val done = selectedTasks().filter { it.status == Status.DONE }
    if (done.isEmpty()) return
    confirm("Delete ${done.size} file(s)?", "They will be removed from your phone.") {
        deleteFilesOf(done)
        exitSelection()
    }
}

internal fun MainActivity.removeAllDone() {
    val n = latestTasks.count { it.status == Status.DONE }
    if (n == 0) return
    confirm("Remove $n items from the list?", "The files stay in your Downloads folder.") {
        TaskRepository.removeDone()
    }
}

internal fun MainActivity.deleteAllDone() {
    val done = latestTasks.filter { it.status == Status.DONE }
    if (done.isEmpty()) return
    confirm("Delete all ${done.size} files?", "They will be removed from your phone.") {
        deleteFilesOf(done)
    }
}

/** Deletes the files (off the main thread); only rows whose file is really gone leave the list. */
internal fun MainActivity.deleteFilesOf(tasks: List<DownloadTask>) {
    val app = applicationContext
    lifecycleScope.launch {
        val gone = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tasks.filter { t ->
                val uri = t.fileUri ?: return@filter true
                // the app's update is a file in the app's own folder, not in Downloads
                if (t.format.startsWith(AppUpdater.TASK_PREFIX)) java.io.File(uri).let { !it.exists() || it.delete() }
                else FileStore.delete(app, uri)
            }.map { it.id }
        }
        TaskRepository.removeMany(gone)
        val failed = tasks.size - gone.size
        message(
            if (failed == 0) (if (gone.size == 1) "File deleted" else "Deleted ${gone.size} files")
            else "$failed file(s) couldn't be deleted. Use \"Remove from list\", or delete them in your Downloads folder"
        )
    }
}

/** Selects every unfinished (done = false) or finished (done = true) item. */
internal fun MainActivity.selectAll(done: Boolean) {
    val ids = latestTasks.filter { (it.status == Status.DONE) == done }.map { it.id }
    if (ids.isEmpty()) return
    selected.clear()
    selected += ids
    selecting = true
    refreshSelectionUi()
}

internal fun MainActivity.confirm(title: String, text: String, onYes: () -> Unit) {
    RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
        .setTitle(title)
        .setMessage(text)
        .setPositiveButton("Yes") { _, _ -> onYes() }
        .setNegativeButton("No", null)
        .show()
}

internal fun MainActivity.onTaskAction(t: DownloadTask) {
    when (t.status) {
        Status.RUNNING, Status.QUEUED, Status.WAITING ->
            DownloadService.send(this, DownloadService.ACTION_PAUSE, t.id)
        Status.PAUSED, Status.FAILED ->
            DownloadService.send(this, DownloadService.ACTION_RESUME, t.id)
        Status.DONE -> if (t.format.startsWith(AppUpdater.TASK_PREFIX)) installUpdate(t) else openFile(t)
    }
}

internal fun MainActivity.installUpdate(t: DownloadTask) {
    val path = t.fileUri ?: return
    AppUpdater.installDownloaded(this, java.io.File(path), t.format.removePrefix(AppUpdater.TASK_PREFIX))
}

internal fun MainActivity.countForRating(done: List<DownloadTask>) {
    val ids = done.filter { !it.format.startsWith(AppUpdater.TASK_PREFIX) }.map { it.id }
    val seen = doneSeen ?: run { doneSeen = HashSet(ids); return }
    val fresh = ids.count { seen.add(it) }
    if (fresh > 0) AppShare.onDownloadsDone(this, fresh, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
}

/** A finished update that is already installed (or was refused) is cleaned away; the pill shows the rest. */
internal fun MainActivity.checkUpdateReady(all: List<DownloadTask>) {
    val t = all.firstOrNull { it.format.startsWith(AppUpdater.TASK_PREFIX) && it.status == Status.DONE } ?: return
    val version = t.format.removePrefix(AppUpdater.TASK_PREFIX)
    if (!AppUpdater.isNewer(this, version) || version == AppPrefs.badRelease(this)) {
        // already installed: the finished update row and its file are not needed any more
        t.fileUri?.let { java.io.File(it).delete() }
        TaskRepository.remove(t.id)
    }
}

/**
 * The update pill above the bottom bar: the new version downloads by itself ("v12.3 · 72%"), then
 * "v12.3 READY" with INSTALL. Paused or failed: CONTINUE / RETRY. Nothing to show: hidden.
 */
internal fun MainActivity.renderUpdatePill(all: List<DownloadTask>) {
    val pill = b.updatePill
    val t = all.firstOrNull {
        it.format.startsWith(AppUpdater.TASK_PREFIX) &&
            it.format.removePrefix(AppUpdater.TASK_PREFIX).let { v -> AppUpdater.isNewer(this, v) && v != AppPrefs.badRelease(this) }
    }
    if (t == null || pillHidden) { pill.isVisible = false; padForPill(false); return }
    val v = t.format.removePrefix(AppUpdater.TASK_PREFIX)
    val done = t.status == Status.DONE
    b.updateDot.isVisible = done
    b.updateRing.isVisible = !done
    when (t.status) {
        Status.DONE -> {
            b.updateTitle.text = "v$v READY"
            b.updateSub.text = "Tap Install to update"
            b.updateAction.text = "Install"
        }
        Status.PAUSED, Status.FAILED -> {
            b.updateRing.isIndeterminate = false
            b.updateRing.setProgressCompat(t.progress.coerceIn(0, 100), false)
            b.updateTitle.text = "v$v · ${t.progress.coerceIn(0, 100)}%"
            b.updateSub.text = if (t.status == Status.FAILED) "Couldn't get the update" else "Update paused"
            b.updateAction.text = if (t.status == Status.FAILED) "Retry" else "Continue"
        }
        else -> {
            val known = t.progress in 1..100
            if (b.updateRing.isIndeterminate != !known) {
                b.updateRing.visibility = View.INVISIBLE                  // (the indicator can't switch while shown)
                b.updateRing.isIndeterminate = !known
                b.updateRing.visibility = View.VISIBLE
            }
            if (known) b.updateRing.setProgressCompat(t.progress, true)
            b.updateTitle.text = if (known) "v$v · ${t.progress}%" else "v$v"
            b.updateSub.text = when (t.status) {
                Status.WAITING -> t.message.ifBlank { "Waiting for the internet" }
                Status.QUEUED -> "Update queued…"
                else -> "Getting the update…"
            }
            b.updateAction.text = "Install"
        }
    }
    val actionOn = done || t.status == Status.PAUSED || t.status == Status.FAILED
    b.updateAction.alpha = if (actionOn) 1f else 0.35f
    val act = {
        when (t.status) {
            Status.DONE -> installUpdate(t)
            Status.PAUSED, Status.FAILED -> DownloadService.send(this, DownloadService.ACTION_RESUME, t.id)
            else -> message("The update is still downloading. Install shows when it's ready")
        }
    }
    b.updateAction.setOnClickListener { act() }
    pill.setOnClickListener { act() }
    pill.contentDescription = "${b.updateTitle.text}. ${b.updateSub.text}"
    if (!pill.isVisible) {
        pill.isVisible = true
        Motion.popIn(pill)
    }
    padForPill(true)
}

/** A small red count on "You" in the bottom bar while downloads are running (not the app's own update). */
internal fun MainActivity.renderYouBadge(all: List<DownloadTask>) {
    val n = all.count {
        !it.format.startsWith(AppUpdater.TASK_PREFIX) &&
            (it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING)
    }
    if (n == 0) { b.bottomNav.removeBadge(R.id.nav_downloads); return }
    b.bottomNav.getOrCreateBadge(R.id.nav_downloads).apply {
        number = n
        maxNumber = 99
        backgroundColor = Ui.accent(this@renderYouBadge)
        badgeTextColor = android.graphics.Color.WHITE
        isVisible = true
    }
}

/**
 * While the update pill floats above the bottom bar, every scrolling list gets that much more room at the
 * bottom, so the last video or download is never hidden under it.
 */
internal fun MainActivity.padForPill(on: Boolean) {
    if (on == pillPadded) return
    pillPadded = on
    val px = (64 * resources.displayMetrics.density).toInt()
    Ui.pillExtra = if (on) px else 0
    val extra = px * (if (on) 1 else -1)
    val pages = b.homePage.root.parent as? android.view.ViewGroup ?: return
    fun walk(v: View) {
        if (v is androidx.recyclerview.widget.RecyclerView || v is androidx.core.widget.NestedScrollView || v is android.widget.ScrollView) {
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, (v.paddingBottom + extra).coerceAtLeast(0))
            if (v is android.view.ViewGroup) v.clipToPadding = false
            return
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
    walk(pages)
}

/** Hides a finished download in the Private folder (set it up first if needed). */
internal fun MainActivity.moveToPrivate(t: DownloadTask) {
    if (!PrivateVault.hasLock(this)) {
        RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Set up Private folder")
            .setMessage("Choose a PIN, pattern or password first. Then tap “Move to Private folder” again.")
            .setPositiveButton("Set up") { _, _ -> PrivateActivity.open(this) }
            .setNegativeButton("Cancel", null)
            .show()
        return
    }
    message("Moving to Private folder…")
    lifecycleScope.launch {
        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { PrivateVault.moveIn(this@moveToPrivate, t) }.isSuccess }
        message(if (ok) "Moved to Private folder" else "Couldn't move this file")
    }
}

internal fun MainActivity.onTaskClose(t: DownloadTask, anchor: View) {
    if (t.status != Status.DONE) {
        // with some progress, ask first: cancelling deletes what was downloaded
        if (t.progress > 0) {
            confirm("Cancel download?", "\"${t.title.ifBlank { "This download" }}\" stops and its downloaded part is deleted.") {
                DownloadService.send(this, DownloadService.ACTION_CANCEL, t.id)
            }
        } else DownloadService.send(this, DownloadService.ACTION_CANCEL, t.id)
        return
    }
    if (t.format.startsWith(AppUpdater.TASK_PREFIX)) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Install")
        menu.menu.add(0, 2, 1, "Delete")
        menu.setOnMenuItemClickListener { item ->
            if (item.itemId == 1) installUpdate(t) else deleteFilesOf(listOf(t))
            true
        }
        menu.show()
        return
    }
    val popup = PopupMenu(this, anchor)
    popup.menu.add(0, 1, 0, "Play")
    popup.menu.add(0, 6, 1, "Open with another app")
    popup.menu.add(0, 2, 1, "Share")
    popup.menu.add(0, 3, 2, "Copy link")
    popup.menu.add(0, 7, 3, "Move to Private folder")
    popup.menu.add(0, 4, 3, "Remove from list")
    popup.menu.add(0, 5, 4, "Delete file")
    popup.setOnMenuItemClickListener { item ->
        when (item.itemId) {
            1 -> openFile(t)
            2 -> shareFile(t)
            6 -> openWithOtherApp(t)
            3 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                AppPrefs.setClipSeen(this, t.url)
                cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
                message("Link copied")
            }
            4 -> TaskRepository.remove(t.id)
            5 -> confirm("Delete file?", "\"${t.title}\" is deleted from your phone.") { deleteFilesOf(listOf(t)) }
            7 -> moveToPrivate(t)
        }
        true
    }
    popup.show()
}

/** YouTube thumbnail address from the video id (no lookup needed). */
internal fun MainActivity.youtubeThumb(url: String): String? =
    if (!FastExtractor.supports(url)) null else Regex("(?:[?&]v=|youtu\\.be/|shorts/)([\\w-]{6,})").find(url)?.groupValues?.get(1)
        ?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

/** You page: the storage strip and the shelf of the newest downloads (updated when the finished list changes). */
internal fun MainActivity.renderCollection(done: List<DownloadTask>) {
    val playable = done.filter { it.fileUri != null && isPlayable(it) && !it.format.startsWith(AppUpdater.TASK_PREFIX) }
    collectionHeader.visible = playable.isNotEmpty()
    if (playable.isEmpty()) return
    shelf.submit(playable.sortedByDescending { it.createdAt }.take(12))
    val key = playable.joinToString(",") { it.id }
    if (key == meterKey) return
    meterKey = key
    lifecycleScope.launch {
        val u = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { StorageMeter.measure(applicationContext, playable) }
        if (isFinishing || isDestroyed) return@launch
        val all = (u.videoBytes + u.audioBytes + u.freeBytes).coerceAtLeast(1L)
        fun weight(v: View, bytes: Long) {
            (v.layoutParams as LinearLayout.LayoutParams).weight = bytes.toFloat() / all
        }
        weight(pl.meterVideo, u.videoBytes)
        weight(pl.meterAudio, u.audioBytes)
        weight(pl.meterFree, u.freeBytes)
        pl.meterBar.requestLayout()
        pl.meterTotal.text = "${StorageMeter.short(u.videoBytes + u.audioBytes)} USED"
        pl.meterText.text = "Videos ${StorageMeter.short(u.videoBytes)}  ·  MP3 ${StorageMeter.short(u.audioBytes)}  ·  Free ${StorageMeter.short(u.freeBytes)}"
        pl.meterBar.contentDescription = pl.meterText.text
    }
}

internal fun MainActivity.isPlayable(t: DownloadTask): Boolean {
    val m = t.mime.orEmpty()
    if (m.startsWith("video/") || m.startsWith("audio/")) return true
    val name = (t.title + " " + t.fileUri.orEmpty()).lowercase()
    return t.isAudio || listOf(".mp4", ".mkv", ".webm", ".mov", ".m4a", ".mp3", ".opus", ".ogg").any { name.contains(it) }
}

/** Finished videos and music always open in the RAINAX player (with the other downloads as a playlist). */
internal fun MainActivity.openFile(t: DownloadTask) {
    val uri = t.fileUri ?: return
    if (isPlayable(t)) {
        controller?.pause()                         // one thing plays at a time
        val list = TaskRepository.tasks.value.filter { it.status == Status.DONE && it.fileUri != null && isPlayable(it) }
        val index = list.indexOfFirst { it.id == t.id }.coerceAtLeast(0)
        try {
            if (list.isEmpty()) PlayerActivity.open(this, listOf(uri), listOf(t.title), 0)
            else PlayerActivity.open(this, list.map { it.fileUri!! }, list.map { it.title }, index)
            return
        } catch (e: Exception) { /* fall back to another app */ }
    }
    openWithOtherApp(t)
}

internal fun MainActivity.openWithOtherApp(t: DownloadTask) {
    val uri = t.fileUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), t.mime ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(intent)
    } catch (e: Exception) {
        Notify.show(this, "Can't open this file")
    }
}

internal fun MainActivity.shareFile(t: DownloadTask) {
    val uri = t.fileUri ?: return
    val send = Intent(Intent.ACTION_SEND)
        .setType(t.mime ?: "*/*")
        .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(Intent.createChooser(send, "Share"))
    } catch (e: Exception) {
        Notify.show(this, "Can't share this file")
    }
}
