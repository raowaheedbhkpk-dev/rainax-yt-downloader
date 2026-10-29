package com.rainax.ytdownloader

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Your own playlists of downloaded songs and videos (kept on the phone: filesDir/playlists.json). */
object LocalPlaylists {

    class Playlist(val id: String, var name: String, val items: MutableList<String>)    // download ids

    private var list: MutableList<Playlist>? = null

    private fun file(c: Context) = File(c.applicationContext.filesDir, "playlists.json")

    @Synchronized
    fun all(c: Context): List<Playlist> {
        list?.let { return it }
        val loaded = mutableListOf<Playlist>()
        runCatching {
            val arr = JSONArray(file(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ids = o.optJSONArray("items") ?: JSONArray()
                loaded += Playlist(o.getString("id"), o.optString("name"), MutableList(ids.length()) { ids.getString(it) })
            }
        }
        list = loaded
        return loaded
    }

    @Synchronized
    private fun mutable(c: Context): MutableList<Playlist> {
        all(c)
        return list!!
    }

    @Synchronized
    private fun save(c: Context) {
        val arr = JSONArray()
        all(c).forEach { p -> arr.put(JSONObject().put("id", p.id).put("name", p.name).put("items", JSONArray(p.items))) }
        runCatching {
            val f = file(c)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    @Synchronized
    fun create(c: Context, name: String): Playlist {
        val p = Playlist(java.util.UUID.randomUUID().toString(), name.trim().ifBlank { "My playlist" }, mutableListOf())
        mutable(c).add(0, p)
        save(c)
        return p
    }

    @Synchronized
    fun rename(c: Context, p: Playlist, name: String) {
        p.name = name.trim().ifBlank { p.name }
        save(c)
    }

    @Synchronized
    fun delete(c: Context, p: Playlist) {
        mutable(c).removeAll { it.id == p.id }
        save(c)
    }

    /** False when it was already in the playlist. */
    @Synchronized
    fun add(c: Context, p: Playlist, taskId: String): Boolean {
        if (taskId in p.items) return false
        p.items += taskId
        save(c)
        return true
    }

    @Synchronized
    fun remove(c: Context, p: Playlist, taskId: String) {
        p.items.remove(taskId)
        save(c)
    }
}

/**
 * Library tab: Downloads (the download manager) plus Music, Videos and Playlists, made from your finished
 * downloads. Search, Play all and Shuffle; the RAINAX player plays them as a list (repeat in its menu).
 *
 * The page itself is drawn by YouScreen.kt (Compose); this class keeps the choices ([YouState]) and does the work.
 */
class LibraryScreen(
    private val act: AppCompatActivity,
    private val s: YouState,
    private val onModeChanged: () -> Unit,
    private val shareFile: (DownloadTask) -> Unit,
    private val deleteFile: (DownloadTask) -> Unit
) {
    /** ⋮ > Move to Private folder (set by the main screen). */
    var onMoveToPrivate: (DownloadTask) -> Unit = {}

    val mode get() = s.libMode

    fun setMode(index: Int) {
        if (s.libMode == index) return
        s.libMode = index
        s.openPlaylistId = null
        s.libQuery = ""
        onModeChanged()
    }

    /** Back inside the Library: playlist -> playlists. True when it went back. */
    fun back(): Boolean {
        if (s.openPlaylistId != null) {
            s.openPlaylistId = null
            return true
        }
        return false
    }

    fun openPlaylist(): LocalPlaylists.Playlist? = s.openPlaylistId?.let { id -> LocalPlaylists.all(act).firstOrNull { it.id == id } }

    fun openPlaylist(p: LocalPlaylists.Playlist) {
        s.openPlaylistId = p.id
        s.libQuery = ""
    }

    private fun changed() { s.playlistsTick++ }

    private fun playable(t: DownloadTask): Boolean {
        if (t.status != Status.DONE || t.fileUri == null) return false
        val m = t.mime.orEmpty()
        return m.startsWith("video/") || m.startsWith("audio/") || t.isAudio ||
            listOf(".mp4", ".mkv", ".webm", ".mov", ".m4a", ".mp3", ".opus", ".ogg").any { t.fileUri.lowercase().contains(it) }
    }

    /** Songs and videos of a playlist that are still on the phone. */
    fun countIn(p: LocalPlaylists.Playlist): Int {
        val have = s.tasks.filter { playable(it) }.mapTo(HashSet()) { it.id }
        return p.items.count { it in have }
    }

    /** The files shown now (Music, Videos, or the open playlist), newest first. */
    fun currentFiles(): List<DownloadTask> {
        val q = s.libQuery.trim()
        val done = s.tasks.filter { playable(it) }
        val list = when (s.libMode) {
            1 -> done.filter { it.isAudio }.sortedByDescending { it.createdAt }
            2 -> done.filter { !it.isAudio }.sortedByDescending { it.createdAt }
            3 -> openPlaylist()?.let { p -> val byId = done.associateBy { it.id }; p.items.mapNotNull { byId[it] } }.orEmpty()
            else -> emptyList()
        }
        return list.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
    }

    fun playList(all: List<DownloadTask>, index: Int, shuffle: Boolean) {
        // (a file whose address was lost is skipped instead of closing the app)
        val wanted = all.getOrNull(index)
        val files = all.filter { it.fileUri != null }
        if (files.isEmpty()) return
        val at = files.indexOf(wanted).coerceAtLeast(0)
        try {
            PlayerActivity.open(act, files.map { it.fileUri!! }, files.map { it.title }, at, shuffle)
        } catch (e: Exception) {
            Notify.show(act, "Can't play these files")
        }
    }

    // ---------- menus ----------

    /** A file's ⋮ menu: label and what it does. */
    fun fileMenu(t: DownloadTask, p: LocalPlaylists.Playlist?): List<Pair<String, () -> Unit>> = buildList {
        add("Add to playlist" to { addToPlaylist(t) })
        if (p != null) add("Remove from this playlist" to { LocalPlaylists.remove(act, p, t.id); changed() })
        add("Share" to { shareFile(t) })
        add("Move to Private folder" to { onMoveToPrivate(t) })
        add("Delete file" to { deleteFile(t) })
    }

    fun newPlaylist() = askName("New playlist", "") { LocalPlaylists.create(act, it); changed() }

    private fun addToPlaylist(t: DownloadTask) {
        val lists = LocalPlaylists.all(act)
        val names = arrayOf("+ New playlist") + lists.map { it.name }.toTypedArray()
        RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Add to playlist")
            .setItems(names) { _, which ->
                if (which == 0) {
                    askName("New playlist", "") { name ->
                        val p = LocalPlaylists.create(act, name)
                        LocalPlaylists.add(act, p, t.id)
                        Notify.show(act, "Added to ${p.name}")
                        changed()
                    }
                } else {
                    val p = lists[which - 1]
                    val added = LocalPlaylists.add(act, p, t.id)
                    Notify.show(act, if (added) "Added to ${p.name}" else "Already in ${p.name}")
                    changed()
                }
            }
            .show()
    }

    fun renamePlaylist(p: LocalPlaylists.Playlist) =
        askName("Rename playlist", p.name) { LocalPlaylists.rename(act, p, it); changed() }

    fun deletePlaylist(p: LocalPlaylists.Playlist) {
        RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Delete \"${p.name}\"?")
            .setMessage("The songs and videos stay in your downloads.")
            .setPositiveButton("Delete") { _, _ ->
                LocalPlaylists.delete(act, p)
                if (s.openPlaylistId == p.id) s.openPlaylistId = null
                changed()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askName(title: String, current: String, onName: (String) -> Unit) {
        val input = EditText(act).apply {
            setText(current)
            setSelection(current.length)
            hint = "Playlist name"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val box = android.widget.FrameLayout(act).apply {
            val pad = (20 * act.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> onName(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
