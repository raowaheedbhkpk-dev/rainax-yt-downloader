package com.rainax.ytdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Where finished files go: the default Downloads/rainax-yt-downloader folder, or a folder the user picked. */
object FileStore {

    const val DEFAULT_FOLDER = "rainax-yt-downloader"

    data class Saved(val uri: String, val mime: String, val name: String)

    /** The folder the user picked, if we still have permission to write to it. */
    fun customTree(context: Context): Uri? {
        val raw = AppPrefs.saveTree(context)
        if (raw.isEmpty()) return null
        val tree = Uri.parse(raw)
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        return if (ok) tree else null
    }

    /** Text shown in Settings. */
    fun describe(context: Context): String {
        val tree = customTree(context) ?: return "Downloads/$DEFAULT_FOLDER"
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return "Custom folder" }
        val path = id.substringAfter(':', id)
        val where = if (id.startsWith("primary:")) "Internal storage" else "SD card / other"
        return if (path.isEmpty()) where else "$where/$path"
    }

    /** Copies [file] into the chosen folder. Falls back to the default folder if that is not possible. */
    fun save(context: Context, file: File, mime: String): Saved {
        val tree = customTree(context)
        if (tree != null) {
            try {
                return saveToTree(context, tree, file, mime)
            } catch (e: Exception) {
                // the folder was removed or the permission was revoked: use the default folder
            }
        }
        return saveToDownloads(context, file, mime)
    }

    private fun saveToTree(context: Context, tree: Uri, file: File, mime: String): Saved {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, file.name)
            ?: error("Could not create file in the chosen folder")
        try {
            resolver.openOutputStream(doc)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(resolver, doc) } catch (ignored: Exception) { }
            throw e
        }
        return Saved(doc.toString(), mime, file.nameWithoutExtension)
    }

    private fun saveToDownloads(context: Context, file: File, mime: String): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(uri.toString(), mime, file.nameWithoutExtension)
    }

    /** Subtitles go next to the video. Best effort. */
    fun saveSubtitle(context: Context, file: File) {
        try {
            save(context, file, "application/x-subrip")
        } catch (e: Exception) { /* subtitles are optional */ }
    }

    /** Deletes a saved file, whichever way it was stored. True when something was removed. */
    fun delete(context: Context, uriString: String): Boolean = try {
        val uri = Uri.parse(uriString)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } else {
            context.contentResolver.delete(uri, null, null) > 0
        }
    } catch (e: Exception) {
        false
    }
}
