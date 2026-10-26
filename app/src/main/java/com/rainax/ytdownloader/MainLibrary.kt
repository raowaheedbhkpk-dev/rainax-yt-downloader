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
    // The You page is a Compose screen (YouScreen.kt): it draws what [you] holds.
    pl.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    pl.setContent { RxTheme { YouScreen(this, you) } }
}

/** Library chips: Downloads shows the download manager, the others show your files. */
internal fun MainActivity.onLibraryMode() {
    if (selecting) exitSelection()
}

internal fun MainActivity.renderTasks(all: List<DownloadTask>) {
    latestTasks = all
    val done = all.filter { it.status == Status.DONE }

    // forget selected items that no longer exist
    val before = selected.size
    selected.retainAll(all.map { it.id }.toSet())
    val selectionChanged = before != selected.size || (selecting && selected.isEmpty())
    if (selecting && selected.isEmpty()) selecting = false

    you.tasks = all                      // the You page redraws itself (lists, sections, collection)
    checkUpdateReady(all)
    renderUpdatePill(all)
    renderYouBadge(all)
    countForRating(done)

    if (selectionChanged) refreshSelectionUi()
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
    val was = you.selecting
    you.selecting = selecting
    you.selected = selected.toSet()
    // the floating mini player moves above the selection buttons (and back) once they are drawn
    if (was != selecting && b.miniPlayer.root.isVisible) pl.postDelayed({ placeMiniPlayer() }, 120)
    updateBack()
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
    val menu = PopupMenu(this, anchor)
    val items = taskMenu(t)
    items.forEachIndexed { i, (label, _) -> menu.menu.add(0, i, i, label) }
    menu.setOnMenuItemClickListener { item -> items.getOrNull(item.itemId)?.second?.invoke(); true }
    menu.show()
}

/** A finished download's ⋮ menu: label and what it does. */
internal fun MainActivity.taskMenu(t: DownloadTask): List<Pair<String, () -> Unit>> {
    if (t.format.startsWith(AppUpdater.TASK_PREFIX)) {
        return listOf("Install" to { installUpdate(t) }, "Delete" to { deleteFilesOf(listOf(t)) })
    }
    return listOf(
        "Play" to { openFile(t) },
        "Open with another app" to { openWithOtherApp(t) },
        "Share" to { shareFile(t) },
        "Copy link" to {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            AppPrefs.setClipSeen(this, t.url)
            cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
            message("Link copied")
        },
        "Move to Private folder" to { moveToPrivate(t) },
        "Remove from list" to { TaskRepository.remove(t.id) },
        "Delete file" to { confirm("Delete file?", "\"${t.title}\" is deleted from your phone.") { deleteFilesOf(listOf(t)) } }
    )
}

/** YouTube thumbnail address from the video id (no lookup needed). */
internal fun MainActivity.youtubeThumb(url: String): String? =
    if (!FastExtractor.supports(url)) null else Regex("(?:[?&]v=|youtu\\.be/|shorts/)([\\w-]{6,})").find(url)?.groupValues?.get(1)
        ?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

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
