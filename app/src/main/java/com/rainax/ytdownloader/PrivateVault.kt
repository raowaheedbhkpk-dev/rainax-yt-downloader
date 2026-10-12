package com.rainax.ytdownloader

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Private folder: videos and music moved here live in the app's own storage (other apps, the gallery and file
 * managers can't see them), behind a PIN, a pattern or a password.
 * Only a salted, slow hash of the secret is kept (PBKDF2), never the secret itself.
 */
object PrivateVault {

    const val PIN = "pin"
    const val PATTERN = "pattern"
    const val PASSWORD = "password"

    private const val ITERATIONS = 120_000
    private const val MAX_TRIES = 5
    private const val LOCKOUT_MS = 30_000L

    class Item(
        val id: String,
        val title: String,
        val ext: String,
        val mime: String,
        val format: String,
        val url: String,
        val size: Long,
        val addedAt: Long
    ) {
        val isAudio get() = format.startsWith("audio") || mime.startsWith("audio")
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("private_lock", Context.MODE_PRIVATE)
    private fun root(c: Context) = File(c.applicationContext.filesDir, "private").apply { mkdirs() }
    private fun thumbs(c: Context) = File(root(c), "thumbs").apply { mkdirs() }
    private fun indexFile(c: Context) = File(root(c), "index.json")

    fun file(c: Context, item: Item) = File(root(c), "${item.id}.${item.ext}")
    fun thumb(c: Context, item: Item) = File(thumbs(c), "${item.id}.jpg")

    // ---------- the lock ----------

    fun hasLock(c: Context) = prefs(c).getString("hash", null) != null
    fun lockType(c: Context): String = prefs(c).getString("type", PIN) ?: PIN

    private fun hash(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Blocking (slow on purpose). Sets a new lock of [type]. */
    fun setLock(c: Context, type: String, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val h = hash(secret, salt, ITERATIONS)
        prefs(c).edit()
            .putString("type", type)
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(h, Base64.NO_WRAP))
            .putInt("iter", ITERATIONS)
            .putInt("fails", 0)
            .putLong("lockedUntil", 0)
            .commit()
    }

    /** Milliseconds to wait after too many wrong tries (0 = may try now). */
    fun waitMs(c: Context): Long = (prefs(c).getLong("lockedUntil", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Blocking (slow on purpose). True when [secret] opens the folder. Too many wrong tries: a 30-second wait. */
    fun check(c: Context, secret: String): Boolean {
        if (waitMs(c) > 0) return false
        val p = prefs(c)
        val salt = Base64.decode(p.getString("salt", null) ?: return false, Base64.NO_WRAP)
        val want = Base64.decode(p.getString("hash", null) ?: return false, Base64.NO_WRAP)
        val ok = java.security.MessageDigest.isEqual(hash(secret, salt, p.getInt("iter", ITERATIONS)), want)
        if (ok) {
            p.edit().putInt("fails", 0).putLong("lockedUntil", 0).apply()
        } else {
            val fails = p.getInt("fails", 0) + 1
            val e = p.edit().putInt("fails", fails)
            if (fails >= MAX_TRIES) e.putInt("fails", 0).putLong("lockedUntil", System.currentTimeMillis() + LOCKOUT_MS)
            e.apply()
        }
        return ok
    }

    /** Forgot the lock: everything in the folder is deleted, and the lock removed. */
    fun reset(c: Context) {
        root(c).deleteRecursively()
        prefs(c).edit().clear().commit()
    }

    // ---------- the files ----------

    @Synchronized
    fun items(c: Context): List<Item> = runCatching {
        val arr = JSONArray(indexFile(c).readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(o.getString("id"), o.optString("title"), o.optString("ext", "mp4"), o.optString("mime"),
                o.optString("format"), o.optString("url"), o.optLong("size"), o.optLong("addedAt"))
        }.filter { file(c, it).exists() }.sortedByDescending { it.addedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun save(c: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().put("id", i.id).put("title", i.title).put("ext", i.ext).put("mime", i.mime)
                .put("format", i.format).put("url", i.url).put("size", i.size).put("addedAt", i.addedAt))
        }
        val tmp = File(indexFile(c).path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile(c))) { indexFile(c).delete(); tmp.renameTo(indexFile(c)) }
    }

    private fun extFor(mime: String?, uri: String): String {
        val fromName = uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when {
            mime == "audio/mpeg" -> "mp3"
            mime == "audio/mp4" -> "m4a"
            mime == "audio/webm" || mime == "audio/ogg" -> "webm"
            mime == "video/webm" -> "webm"
            mime?.startsWith("video/") == true -> "mp4"
            fromName in listOf("mp4", "m4a", "mp3", "webm", "mkv", "opus", "ogg", "3gp") -> fromName
            else -> "mp4"
        }
    }

    /** Blocking. Moves a finished download into the folder (it leaves the downloads list, Library and gallery). */
    fun moveIn(c: Context, t: DownloadTask) {
        val src = t.fileUri ?: error("No file")
        val mime = t.mime ?: if (t.isAudio) "audio/mp4" else "video/mp4"
        val item = Item(
            java.util.UUID.randomUUID().toString(), t.title.ifBlank { "Video" }, extFor(t.mime, src), mime,
            t.format, t.url, 0, System.currentTimeMillis()
        )
        val dest = file(c, item)
        try {
            c.contentResolver.openInputStream(Uri.parse(src))!!.use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        t.thumbPath?.let { p -> runCatching { File(p).copyTo(thumb(c, item), overwrite = true) } }
        val saved = Item(item.id, item.title, item.ext, item.mime, item.format, item.url, dest.length(), item.addedAt)
        save(c, items(c) + saved)
        FileStore.delete(c, src)                   // the visible copy goes away
        TaskRepository.remove(t.id)
    }

    /** Blocking. Puts a file back in Rainax Tube (and in the downloads list and Library). */
    fun moveOut(c: Context, item: Item) {
        val f = file(c, item)
        val saved = FileStore.saveWith(c, NativeDownloader.safeName(item.title, item.ext), item.mime) { out ->
            f.inputStream().use { it.copyTo(out, 1 shl 16) }
        }
        val thumbCopy = thumb(c, item).takeIf { it.exists() }?.let { t ->
            runCatching { File(c.filesDir, "thumbs").apply { mkdirs() }.let { d -> t.copyTo(File(d, "${item.id}.jpg"), true).path } }.getOrNull()
        }
        TaskRepository.addAll(listOf(
            DownloadTask(
                id = java.util.UUID.randomUUID().toString(), url = item.url, title = item.title,
                format = item.format.ifBlank { if (item.isAudio) "audio:m4a" else "video:0" }, thumbPath = thumbCopy,
                status = Status.DONE, progress = 100, message = "Completed", retries = 0,
                fileUri = saved.uri, mime = item.mime, createdAt = System.currentTimeMillis()
            )
        ))
        remove(c, item)
    }

    /** Deletes a private file for good. */
    fun remove(c: Context, item: Item) {
        file(c, item).delete()
        thumb(c, item).delete()
        save(c, items(c).filter { it.id != item.id })
    }
}
