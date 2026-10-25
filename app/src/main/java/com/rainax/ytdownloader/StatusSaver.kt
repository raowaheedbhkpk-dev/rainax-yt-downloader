package com.rainax.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rainax.ytdownloader.databinding.PageSocialBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WhatsApp status saver (WhatsApp and WhatsApp Business). WhatsApp keeps the statuses you viewed in a hidden
 * ".Statuses" folder for 24 hours. That folder is marked "don't index", so the normal photos/videos permission
 * can't see it. With "All files access" (asked once on first start, like MX Player) RAINAX reads it directly;
 * without it, you can instead pick just that folder once on Android's folder screen.
 * Saved statuses go to Internal storage/Rainax Tube/Status.
 */
class StatusSaver(
    private val act: AppCompatActivity,
    private val so: PageSocialBinding,
    /** Opens Android's folder screen at [initial] (the activity owns the result). */
    private val pickFolder: (Uri) -> Unit,
    /** Asks for file access (All files access on Android 11+, storage on Android 10). */
    private val askFileAccess: () -> Unit
) {
    class Item(val uri: Uri, val name: String, val mime: String, val modified: Long) {
        val isVideo get() = mime.startsWith("video/") || name.endsWith(".mp4", true)
    }

    /** 0 = WhatsApp, 1 = WhatsApp Business, 2 = statuses you saved. */
    private var tab = 0
    private var items: List<Item> = emptyList()
    private var loadJob: Job? = null
    private val adapter = Adapter()

    private val thumbs = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val decoder get() = DECODER

    fun setup() {
        so.statusList.layoutManager = GridLayoutManager(act, 3)
        so.statusList.adapter = adapter
        so.statusApps.setOnCheckedStateChangeListener { _, ids ->
            tab = when (ids.firstOrNull()) { R.id.statusWab -> 1; R.id.statusSaved -> 2; else -> 0 }
            refresh()
        }
        so.statusAllow.setOnClickListener { askFileAccess() }
        so.statusPickFolder.setOnClickListener { pickFolder(initialUri(tab)) }
        so.statusRefresh.setOnClickListener { refresh() }
        so.statusSaveAll.setOnClickListener { saveAll() }
        so.statusAutoSwitch.isSaveEnabled = false                  // always from the saved choice, never replayed
        so.statusAutoSwitch.isChecked = AppPrefs.statusAuto(act)
        so.statusAutoRow.setOnClickListener { so.statusAutoSwitch.toggle() }
        so.statusAutoSwitch.setOnCheckedChangeListener { _, on ->
            if (on == AppPrefs.statusAuto(act)) return@setOnCheckedChangeListener
            StatusAutoSave.setEnabled(act, on)
            if (on) {
                Notify.show(act, "Auto-save on: new statuses are saved to Rainax Tube/Status")
                StatusAutoSave.runSoon(act) { n -> if (n > 0) Notify.show(act, if (n == 1) "Saved 1 status" else "Saved $n statuses"); refresh() }
            } else Notify.show(act, "Auto-save off")
        }
    }

    /** WhatsApp chosen from the apps grid: show its statuses. */
    fun focus() {
        if (tab == 2) so.statusApps.check(R.id.statusWa) else refresh()
    }

    // ---------- the folder ----------

    private fun pkg(t: Int) = if (t == 1) WAB else WA

    private fun initialUri(t: Int): Uri {
        val path = if (t == 1) "Android/media/$WAB/WhatsApp Business/Media/.Statuses" else "Android/media/$WA/WhatsApp/Media/.Statuses"
        return DocumentsContract.buildDocumentUri(STORAGE, "primary:$path")
    }

    /** RAINAX may read files directly (All files access, or storage permission on Android 10). */
    private fun fileAccess(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(act, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The app's status folder on the phone (new place first, then the old one), or null. */
    @Suppress("DEPRECATION")
    private fun statusDir(t: Int): java.io.File? {
        val root = Environment.getExternalStorageDirectory()
        val paths = if (t == 1) listOf("Android/media/$WAB/WhatsApp Business/Media/.Statuses", "WhatsApp Business/Media/.Statuses")
        else listOf("Android/media/$WA/WhatsApp/Media/.Statuses", "WhatsApp/Media/.Statuses")
        return paths.map { java.io.File(root, it) }.firstOrNull { it.isDirectory }
    }

    private fun fileList(dir: java.io.File): List<Item> =
        (dir.listFiles() ?: error("Folder can't be read")).filter { f ->
            f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "mp4", "3gp")
        }.map { f ->
            val ext = f.extension.lowercase()
            val mime = when (ext) { "mp4" -> "video/mp4"; "3gp" -> "video/3gpp"; "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            Item(Uri.fromFile(f), f.name, mime, f.lastModified())
        }.sortedByDescending { it.modified }

    private fun installed(t: Int) = runCatching { act.packageManager.getPackageInfo(pkg(t), 0); true }.getOrDefault(false)

    /** The folder picked on Android's screen. Finds ".Statuses" inside it when a folder above it was picked. */
    fun onFolderPicked(tree: Uri?) {
        tree ?: return
        if (tab == 2) return
        val t = tab
        act.lifecycleScope.launch {
            val doc = withContext(Dispatchers.IO) { findStatuses(tree) }
            if (doc == null) {
                Notify.show(act, "That isn't the status folder. Tap Allow access, then tap “Use this folder” without changing folders.", long = true)
                return@launch
            }
            runCatching { act.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            AppPrefs.setStatusFolder(act, pkg(t), tree.toString(), doc)
            if (tab == t) refresh()
        }
    }

    private fun findStatuses(tree: Uri): String? = runCatching {
        val top = DocumentsContract.getTreeDocumentId(tree)
        if (top.endsWith(".Statuses")) return@runCatching top
        // picked WhatsApp, Media, or the app's folder: look up to three levels down
        var level = listOf(top)
        repeat(3) {
            val next = mutableListOf<String>()
            for (id in level) for ((child, name, mime) in children(tree, id)) {
                if (mime != DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (name == ".Statuses") return@runCatching child
                if (name in listOf("WhatsApp", "WhatsApp Business", "Media", WA, WAB)) next += child
            }
            level = next
        }
        null
    }.getOrNull()

    private fun children(tree: Uri, docId: String): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        act.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { c -> while (c.moveToNext()) out += Triple(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty()) }
        return out
    }

    // ---------- listing ----------

    fun refresh() {
        // auto-save on: anything new is saved first (in the background), then the list shows it ticked
        StatusAutoSave.runSoon(act) { n -> if (n > 0) { savedNames = savedNames + AppPrefs.savedStatuses(act); adapter.notifyDataSetChanged() } }
        loadJob?.cancel()
        val t = tab
        val direct = t != 2 && fileAccess()
        val folder = if (t == 2 || direct) null else AppPrefs.statusFolder(act, pkg(t))
        val granted = direct || (folder != null && act.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder.first && it.isReadPermission })
        so.statusAsk.isVisible = t != 2 && !granted
        so.statusRefresh.isVisible = t == 2 || granted
        if (t != 2 && !granted) {
            val appName = if (t == 1) "WhatsApp Business" else "WhatsApp"
            so.statusAskText.text = if (android.os.Build.VERSION.SDK_INT >= 30) {
                "WhatsApp hides statuses from the normal photo permission. Tap Allow access and turn on " +
                    "“Allow access to manage all files” for RAINAX Tube (asked only once)." +
                    if (!installed(t)) "\n($appName isn't on this phone.)" else ""
            } else {
                "Tap Allow access and allow storage, so RAINAX can read the statuses you've seen in $appName."
            }
            so.statusPickFolder.isVisible = true
            show(emptyList(), null)
            so.statusSub.text = "Save photos and videos from statuses you've seen"
            return
        }
        so.statusLoading.isVisible = items.isEmpty()
        loadJob = act.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                // what is already in Rainax Tube/Status (also after reinstalling the app, when its memory is empty)
                if (t != 2) savedNames = runCatching { savedOnDisk() }.getOrDefault(savedNames)
                runCatching {
                    when {
                        t == 2 -> savedList()
                        direct -> statusDir(t)?.let { fileList(it) } ?: emptyList()
                        else -> statusList(Uri.parse(folder!!.first), folder.second)
                    }
                }.getOrNull()
            }
            if (tab != t) return@launch
            so.statusLoading.isVisible = false
            when {
                list == null && t != 2 && !direct -> {
                    // the folder moved, or the permission was taken back in Settings: ask again
                    AppPrefs.setStatusFolder(act, pkg(t), null, null)
                    refresh()
                }
                list.isNullOrEmpty() -> show(
                    emptyList(),
                    when {
                        t == 2 -> "Statuses you save show up here."
                        !installed(t) -> "${if (t == 1) "WhatsApp Business" else "WhatsApp"} isn't on this phone."
                        else -> "No statuses yet. Watch some statuses in ${if (t == 1) "WhatsApp Business" else "WhatsApp"}, then tap ⟳."
                    }
                )
                else -> show(list, null)
            }
        }
    }

    private fun show(list: List<Item>, emptyText: String?) {
        items = list
        adapter.notifyDataSetChanged()
        so.statusList.isVisible = list.isNotEmpty()
        so.statusEmpty.isVisible = emptyText != null
        so.statusEmpty.text = emptyText.orEmpty()
        val unsaved = list.count { !isSaved(it) }
        so.statusSaveAll.isVisible = tab != 2 && unsaved > 1
        so.statusSaveAll.text = "Save all ($unsaved)"
        if (list.isNotEmpty()) {
            val photos = list.count { !it.isVideo }
            val videos = list.size - photos
            so.statusSub.text = listOfNotNull(
                photos.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" },
                videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" }
            ).joinToString(" • ") + if (tab == 2) " saved" else ""
        }
    }

    private fun statusList(tree: Uri, docId: String): List<Item> {
        val out = mutableListOf<Item>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        act.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1).orEmpty()
                val mime = c.getString(2).orEmpty()
                val media = mime.startsWith("image/") || mime.startsWith("video/") ||
                    name.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp", "mp4")
                if (!media || name.startsWith(".")) continue
                out += Item(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, mime, c.getLong(3))
            }
        } ?: error("Folder can't be read")
        return out.sortedByDescending { it.modified }
    }

    /** Statuses saved by RAINAX (Rainax Tube/Status, or Downloads/Rainax Tube/Status without file access). */
    private fun savedList(): List<Item> {
        val direct = FileStore.dir(FileStore.STATUS)
        val fromFolder = if (FileStore.canWriteDirect(act) && direct.isDirectory) runCatching { fileList(direct) }.getOrDefault(emptyList()) else emptyList()
        val out = fromFolder.toMutableList()
        act.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_MODIFIED),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$SAVE_PATH%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                out += Item(uri, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3) * 1000)
            }
        }
        return out.distinctBy { baseName(it.name) }.sortedByDescending { it.modified }
    }

    // ---------- saving ----------

    /** Status file names already in Rainax Tube/Status (a "name (2).jpg" copy counts as "name.jpg"). */
    @Volatile private var savedNames: Set<String> = emptySet()

    private fun baseName(name: String): String {
        val ext = name.substringAfterLast('.', "")
        val stem = name.substringBeforeLast('.').replace(Regex(" \\(\\d+\\)$"), "")
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }

    /**
     * Blocking. Names of the statuses already saved. Exact extra copies ("name (2).mp4", same size as "name.mp4")
     * made by saving twice are removed.
     */
    private fun savedOnDisk(): Set<String> {
        val names = HashSet<String>()
        val dir = FileStore.dir(FileStore.STATUS)
        if (FileStore.canWriteDirect(act) && dir.isDirectory) {
            val files = dir.listFiles().orEmpty().filter { it.isFile }
            val byName = files.associateBy { it.name }
            files.forEach { f ->
                val base = baseName(f.name)
                val original = byName[base]
                if (base != f.name && original != null && original.length() == f.length()) {
                    if (f.delete()) runCatching {
                        android.media.MediaScannerConnection.scanFile(act.applicationContext, arrayOf(f.absolutePath), null, null)
                    }
                } else names += base
            }
        }
        runCatching {
            act.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$SAVE_PATH%"), null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names += baseName(it) } }
        }
        return names
    }

    private fun isSaved(item: Item) = tab == 2 || item.name in savedNames || item.name in AppPrefs.savedStatuses(act)

    private fun save(item: Item, quiet: Boolean = false, done: () -> Unit = {}) {
        act.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { copy(item) }.isSuccess }
            if (ok) { AppPrefs.addSavedStatus(act, item.name); savedNames = savedNames + item.name }
            if (!quiet) Notify.show(act, if (ok) "Saved to Rainax Tube/Status" else "Couldn't save this status")
            adapter.notifyDataSetChanged()
            show(items, if (items.isEmpty()) so.statusEmpty.text.toString() else null)
            done()
        }
    }

    private fun saveAll() {
        val todo = items.filter { !isSaved(it) }
        if (todo.isEmpty()) return
        so.statusSaveAll.isEnabled = false
        act.lifecycleScope.launch {
            var ok = 0
            for (item in todo) {
                val saved = withContext(Dispatchers.IO) { runCatching { copy(item) }.isSuccess }
                if (saved) { ok++; AppPrefs.addSavedStatus(act, item.name); savedNames = savedNames + item.name }
            }
            so.statusSaveAll.isEnabled = true
            adapter.notifyDataSetChanged()
            show(items, null)
            Notify.show(act, "Saved $ok of ${todo.size} to Rainax Tube/Status")
        }
    }

    /** Blocking. Copies a status into Rainax Tube/Status (so it stays after WhatsApp deletes it). */
    private fun copy(item: Item) {
        // already there (saved before, maybe before reinstalling): don't make a second copy
        if (FileStore.canWriteDirect(act) && java.io.File(FileStore.dir(FileStore.STATUS), item.name).exists()) {
            savedNames = savedNames + item.name
            return
        }
        val mime = item.mime.ifBlank { if (item.isVideo) "video/mp4" else "image/jpeg" }
        FileStore.saveWith(act, item.name, mime, FileStore.STATUS) { out ->
            act.contentResolver.openInputStream(item.uri)!!.use { it.copyTo(out, 1 shl 16) }
        }
    }

    // ---------- looking ----------

    private fun open(item: Item) {
        if (item.isVideo) {
            PlayerActivity.open(act, listOf(item.uri.toString()), listOf("Status"), 0)
            return
        }
        // a photo: full screen, with Save
        val dialog = android.app.Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val box = android.widget.FrameLayout(act)
        val image = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        box.addView(image, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val d = act.resources.displayMetrics.density
        fun roundButton(icon: Int, label: String, gravity: Int, onTap: () -> Unit) = ImageButton(act).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_status_save)
            contentDescription = label
            val p = (12 * d).toInt()
            setPadding(p, p, p, p)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { onTap() }
            box.addView(this, android.widget.FrameLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt(), gravity).apply {
                val m = (20 * d).toInt()
                setMargins(m, (36 * d).toInt(), m, (36 * d).toInt())
            })
        }
        roundButton(R.drawable.ic_close, "Close", android.view.Gravity.TOP or android.view.Gravity.START) { dialog.dismiss() }
        if (!isSaved(item)) {
            roundButton(R.drawable.ic_download, "Save", android.view.Gravity.BOTTOM or android.view.Gravity.END) {
                save(item) { dialog.dismiss() }
            }
        }
        dialog.setContentView(box)
        dialog.show()
        val px = act.resources.displayMetrics.widthPixels
        decoder.execute {
            val bmp = runCatching { decodeImage(item.uri, px) }.getOrNull()
            image.post { if (bmp != null) image.setImageBitmap(bmp) }
        }
    }

    private fun decodeImage(uri: Uri, maxPx: Int): Bitmap? {
        val r = act.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    private fun thumbOf(item: Item): Bitmap? {
        if (!item.isVideo) return decodeImage(item.uri, 360)
        runCatching { act.contentResolver.loadThumbnail(item.uri, Size(360, 360), null) }.getOrNull()?.let { return it }
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(act, item.uri)
            mmr.getScaledFrameAtTime(1_000_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_status, parent, false)
            v.clipToOutline = true
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val v = holder.itemView
            val thumb = v.findViewById<ImageView>(R.id.statusThumb)
            val save = v.findViewById<ImageButton>(R.id.statusSave)
            v.findViewById<View>(R.id.statusPlay).isVisible = item.isVideo
            val saved = isSaved(item)
            save.isVisible = tab != 2
            save.setImageResource(if (saved) R.drawable.ic_check else R.drawable.ic_download)
            save.contentDescription = if (saved) "Saved" else "Save"
            save.setOnClickListener { if (!saved) save(item) }
            v.setOnClickListener { open(item) }
            val key = item.uri.toString()
            thumb.tag = key
            val cached = thumbs.get(key)
            if (cached != null) { thumb.setImageBitmap(cached); return }
            thumb.setImageDrawable(null)
            decoder.execute {
                val bmp = runCatching { thumbOf(item) }.getOrNull() ?: return@execute
                thumb.post {
                    thumbs.put(key, bmp)
                    if (thumb.tag == key) thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    companion object {
        const val WA = "com.whatsapp"
        const val WAB = "com.whatsapp.w4b"
        private const val STORAGE = "com.android.externalstorage.documents"
        /** One set of picture threads for the app (a new set per screen rebuild piled up after theme changes). */
        private val DECODER = java.util.concurrent.Executors.newFixedThreadPool(3)
        private val SAVE_PATH = "${Environment.DIRECTORY_DOWNLOADS}/${FileStore.ROOT}/${FileStore.STATUS}/"
    }
}
