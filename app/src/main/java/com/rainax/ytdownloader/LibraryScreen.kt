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
import com.rainax.ytdownloader.databinding.PagePlayBinding
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
 */
class LibraryScreen(
    private val act: AppCompatActivity,
    private val pl: PagePlayBinding,
    private val onModeChanged: () -> Unit,
    private val shareFile: (DownloadTask) -> Unit,
    private val deleteFile: (DownloadTask) -> Unit
) {
    private val modes = listOf("Downloads", "Music", "Videos", "Playlists")
    var mode = 0
        private set
    val showingDownloads get() = mode == 0

    private var openPlaylist: LocalPlaylists.Playlist? = null
    private var tasks: List<DownloadTask> = emptyList()
    private var query = ""
    private val adapter = Adapter()

    fun setup() {
        modes.forEachIndexed { i, t ->
            val chip = LayoutInflater.from(act).inflate(R.layout.item_chip, pl.libChips, false) as Chip
            chip.id = View.generateViewId()
            chip.text = t
            pl.libChips.addView(chip)
            if (i == 0) chip.isChecked = true
        }
        pl.libChips.setOnCheckedStateChangeListener { group, ids ->
            val index = ids.firstOrNull()?.let { id -> (0 until group.childCount).firstOrNull { group.getChildAt(it).id == id } }
                ?: return@setOnCheckedStateChangeListener
            setMode(index)
        }
        pl.libList.layoutManager = LinearLayoutManager(act)
        pl.libList.adapter = adapter
        pl.libSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                pl.libSearchClear.isVisible = query.isNotEmpty()
                render()
            }
        })
        pl.libSearchClear.setOnClickListener { pl.libSearch.setText("") }
        pl.libPlayAll.setOnClickListener { playList(currentFiles(), 0, shuffle = false) }
        pl.libShuffle.setOnClickListener {
            val files = currentFiles()
            if (files.isNotEmpty()) playList(files, files.indices.random(), shuffle = true)
        }
        pl.libNewPlaylist.setOnClickListener { askName("New playlist", "") { LocalPlaylists.create(act, it); render() } }
        pl.libPlaylistBack.setOnClickListener { back() }
        pl.libPlaylistMore.setOnClickListener { v -> openPlaylist?.let { playlistMenu(it, v) } }
    }

    private fun setMode(index: Int) {
        if (mode == index) return
        mode = index
        openPlaylist = null
        pl.libraryBox.isVisible = mode != 0
        onModeChanged()
        render()
    }

    /** Back inside the Library: playlist -> playlists. True when it went back. */
    fun back(): Boolean {
        if (openPlaylist != null) {
            openPlaylist = null
            render()
            return true
        }
        return false
    }

    /** New list of downloads from the download manager. */
    fun onTasks(all: List<DownloadTask>) {
        tasks = all
        if (mode != 0) render()
    }

    private fun playable(t: DownloadTask): Boolean {
        if (t.status != Status.DONE || t.fileUri == null) return false
        val m = t.mime.orEmpty()
        return m.startsWith("video/") || m.startsWith("audio/") || t.isAudio ||
            listOf(".mp4", ".mkv", ".webm", ".mov", ".m4a", ".mp3", ".opus", ".ogg").any { t.fileUri.lowercase().contains(it) }
    }

    private fun matches(t: DownloadTask) = query.isEmpty() || t.title.contains(query, ignoreCase = true)

    /** The files shown now (Music, Videos, or the open playlist), newest first. */
    private fun currentFiles(): List<DownloadTask> {
        val done = tasks.filter { playable(it) }
        val list = when (mode) {
            1 -> done.filter { it.isAudio }.sortedByDescending { it.createdAt }
            2 -> done.filter { !it.isAudio }.sortedByDescending { it.createdAt }
            3 -> openPlaylist?.let { p -> val byId = done.associateBy { it.id }; p.items.mapNotNull { byId[it] } }.orEmpty()
            else -> emptyList()
        }
        return list.filter { matches(it) }
    }

    private fun render() {
        if (mode == 0) return
        val p = openPlaylist
        val showPlaylists = mode == 3 && p == null
        pl.libPlaylistBar.isVisible = p != null
        p?.let { pl.libPlaylistName.text = it.name }
        pl.libNewPlaylist.isVisible = showPlaylists
        pl.libPlayAll.isVisible = !showPlaylists
        pl.libShuffle.isVisible = !showPlaylists
        pl.libSearch.hint = if (showPlaylists) "Search playlists" else "Search your downloads"
        val rows: List<Row> = if (showPlaylists) {
            LocalPlaylists.all(act).filter { query.isEmpty() || it.name.contains(query, true) }.map { Row(null, it) }
        } else {
            currentFiles().map { Row(it, null) }
        }
        adapter.submit(rows)
        val files = !showPlaylists && rows.isNotEmpty()
        pl.libPlayAll.isEnabled = files
        pl.libShuffle.isEnabled = files
        pl.libEmpty.isVisible = rows.isEmpty()
        pl.libEmpty.text = when {
            query.isNotEmpty() -> "Nothing found for \"$query\""
            showPlaylists -> "No playlists yet. Tap New playlist, then add songs or videos with their ⋮ button."
            p != null -> "This playlist is empty. Open Music or Videos and add songs with their ⋮ button."
            mode == 1 -> "No music yet. Download a song as M4A or MP3 and it shows here."
            else -> "No videos yet. Downloaded videos show here."
        }
    }

    private fun playList(files: List<DownloadTask>, index: Int, shuffle: Boolean) {
        if (files.isEmpty()) return
        try {
            PlayerActivity.open(act, files.map { it.fileUri!! }, files.map { it.title }, index, shuffle)
        } catch (e: Exception) {
            Toast.makeText(act, "Can't play these files", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- menus ----------

    private fun fileMenu(t: DownloadTask, anchor: View) {
        val p = openPlaylist
        val menu = PopupMenu(act, anchor)
        menu.menu.add(0, 1, 0, "Add to playlist")
        if (p != null) menu.menu.add(0, 2, 1, "Remove from this playlist")
        menu.menu.add(0, 3, 2, "Share")
        menu.menu.add(0, 4, 3, "Delete file")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> addToPlaylist(t)
                2 -> if (p != null) { LocalPlaylists.remove(act, p, t.id); render() }
                3 -> shareFile(t)
                4 -> deleteFile(t)
            }
            true
        }
        menu.show()
    }

    private fun addToPlaylist(t: DownloadTask) {
        val lists = LocalPlaylists.all(act)
        val names = arrayOf("+ New playlist") + lists.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Add to playlist")
            .setItems(names) { _, which ->
                if (which == 0) {
                    askName("New playlist", "") { name ->
                        val p = LocalPlaylists.create(act, name)
                        LocalPlaylists.add(act, p, t.id)
                        Toast.makeText(act, "Added to ${p.name}", Toast.LENGTH_SHORT).show()
                        render()
                    }
                } else {
                    val p = lists[which - 1]
                    val added = LocalPlaylists.add(act, p, t.id)
                    Toast.makeText(act, if (added) "Added to ${p.name}" else "Already in ${p.name}", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .show()
    }

    private fun playlistMenu(p: LocalPlaylists.Playlist, anchor: View) {
        val menu = PopupMenu(act, anchor)
        menu.menu.add(0, 1, 0, "Rename")
        menu.menu.add(0, 2, 1, "Delete playlist")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askName("Rename playlist", p.name) { LocalPlaylists.rename(act, p, it); render() }
                2 -> MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
                    .setTitle("Delete \"${p.name}\"?")
                    .setMessage("The songs and videos stay in your downloads.")
                    .setPositiveButton("Delete") { _, _ ->
                        LocalPlaylists.delete(act, p)
                        if (openPlaylist?.id == p.id) openPlaylist = null
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        menu.show()
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
        MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ -> onName(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- list ----------

    private class Row(val file: DownloadTask?, val playlist: LocalPlaylists.Playlist?)

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_library, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val t = row.file
            val p = row.playlist
            if (p != null) {
                holder.square(true)
                holder.thumb.setImageResource(R.drawable.ic_playlist)
                holder.thumb.scaleType = ImageView.ScaleType.CENTER
                holder.title.text = p.name
                val have = tasks.filter { playable(it) }.mapTo(HashSet()) { it.id }
                val n = p.items.count { it in have }
                holder.meta.text = if (n == 1) "1 item" else "$n items"
                holder.itemView.setOnClickListener {
                    openPlaylist = p
                    render()
                }
                holder.more.setOnClickListener { playlistMenu(p, it) }
                return
            }
            if (t == null) return
            holder.square(t.isAudio)
            holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            val path = t.thumbPath
            if (path != null && File(path).exists()) {
                Img.loadFile(holder.thumb, path)
            } else {
                Img.load(holder.thumb, null)
                holder.thumb.scaleType = ImageView.ScaleType.CENTER
                holder.thumb.setImageResource(if (t.isAudio) R.drawable.ic_audio else R.drawable.ic_video)
            }
            holder.title.text = t.title.ifBlank { "Download" }
            val kind = when {
                t.mime?.contains("mpeg") == true -> "MP3"
                t.isAudio -> "Music"
                else -> "Video"
            }
            holder.meta.text = kind + "  •  " + android.text.format.DateUtils.getRelativeTimeSpanString(t.createdAt)
            holder.itemView.setOnClickListener {
                val files = currentFiles()
                playList(files, files.indexOfFirst { it.id == t.id }.coerceAtLeast(0), shuffle = false)
            }
            holder.more.setOnClickListener { fileMenu(t, it) }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val box: View = v.findViewById(R.id.libThumbBox)
            val thumb: ImageView = v.findViewById(R.id.libThumb)
            val title: TextView = v.findViewById(R.id.libTitle)
            val meta: TextView = v.findViewById(R.id.libMeta)
            val more: ImageButton = v.findViewById(R.id.libMore)

            init { box.clipToOutline = true }

            /** Music and playlists get a square picture, videos a wide one. */
            fun square(on: Boolean) {
                val d = itemView.resources.displayMetrics.density
                box.updateLayoutParams { width = ((if (on) 56 else 96) * d).toInt() }
            }
        }
    }
}
