package com.rainax.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where finished files go: Internal storage/Rainax Tube, with Video, Audio and Status folders inside.
 * The place is fixed by the app (no folder choice). Writing there needs "All files access" (Android 11+) or the
 * storage permission (Android 10); without it, files go to Downloads/Rainax Tube/… instead.
 */
object FileStore {

    const val ROOT = "Rainax Tube"
    const val VIDEO = "Video"
    const val AUDIO = "Audio"
    const val STATUS = "Status"

    data class Saved(val uri: String, val mime: String, val name: String)

    /** The app may write anywhere in internal storage. */
    fun canWriteDirect(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Internal storage/Rainax Tube/[sub]. */
    @Suppress("DEPRECATION")
    fun dir(sub: String): File = File(File(Environment.getExternalStorageDirectory(), ROOT), sub)

    /** The folder a file of [mime] goes to. */
    fun folderFor(mime: String): String = when {
        mime.startsWith("audio/") -> AUDIO
        else -> VIDEO                     // videos, and their subtitles next to them
    }

    /** Text shown in Settings. */
    fun describe(context: Context): String =
        if (canWriteDirect(context)) "Internal storage/$ROOT" else "Downloads/$ROOT (allow All files access to use Internal storage/$ROOT)"

    /** Copies [file] into the app's folder. */
    fun save(context: Context, file: File, mime: String, folder: String = folderFor(mime)): Saved =
        saveWith(context, file.name, mime, folder) { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }

    /**
     * Creates [name] in Rainax Tube/[folder] and lets [write] fill it (e.g. joining video and sound straight into the
     * final file, with no extra copy). A file that fails half-way is removed.
     */
    fun saveWith(context: Context, name: String, mime: String, folder: String = folderFor(mime), write: (OutputStream) -> Unit): Saved {
        if (canWriteDirect(context)) {
            val started = AtomicBoolean(false)
            try {
                return saveToFolder(context, name, mime, folder) { out -> started.set(true); write(out) }
            } catch (e: Exception) {
                // the folder can't be made (very unusual): use Downloads; a failure while writing is a real error
                if (started.get()) throw e
            }
        }
        return saveToDownloads(context, name, mime, folder, write)
    }

    /** Writes straight into Internal storage/Rainax Tube/[folder], then lets the gallery and music apps see it. */
    private fun saveToFolder(context: Context, name: String, mime: String, folder: String, write: (OutputStream) -> Unit): Saved {
        val dir = dir(folder)
        if (!dir.isDirectory && !dir.mkdirs()) error("Can't make the folder $ROOT/$folder")
        val file = unique(dir, name)
        try {
            file.outputStream().use { write(it) }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        val uri = scan(context, file, mime) ?: Uri.fromFile(file)
        return Saved(uri.toString(), mime, file.nameWithoutExtension)
    }

    /** "name.mp4", or "name (2).mp4" when that exists. */
    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (f.exists()) f = File(dir, "$base ($n)$ext").also { n++ }
        return f
    }

    /** Adds the file to Android's media list (gallery, music players, Library) and returns its media address. */
    private fun scan(context: Context, file: File, mime: String): Uri? {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: Uri? = null
        android.media.MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf(mime)) { _, uri ->
            result = uri
            latch.countDown()
        }
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun saveToDownloads(context: Context, name: String, mime: String, folder: String, write: (OutputStream) -> Unit): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$ROOT/$folder")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { write(it) }
        } catch (e: Exception) {
            // never leave a hidden half-written entry behind
            try { resolver.delete(uri, null, null) } catch (ignored: Exception) { }
            throw e
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(uri.toString(), mime, name.substringBeforeLast('.'))
    }

    /** Subtitles go next to the video. Best effort. */
    fun saveSubtitle(context: Context, file: File) {
        try {
            save(context, file, "application/x-subrip", VIDEO)
        } catch (e: Exception) { /* subtitles are optional */ }
    }

    /** Deletes a saved file, whichever way it was stored. True when something was removed. */
    fun delete(context: Context, uriString: String): Boolean = try {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            File(uri.path ?: "").delete()
        } else if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (e: Exception) {
        false
    }
}
