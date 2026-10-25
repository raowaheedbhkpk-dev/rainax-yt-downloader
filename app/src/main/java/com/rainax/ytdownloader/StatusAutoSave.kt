package com.rainax.ytdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat

/**
 * Auto-save WhatsApp statuses (Settings on the Social page). Every status you view in WhatsApp or WhatsApp
 * Business is copied to Rainax Tube/Status before WhatsApp deletes it after 24 hours: when RAINAX opens, and
 * about every 30 minutes in the background (Android decides the exact time to save battery). A quiet
 * notification says how many new ones were saved. It uses the same access the status saver already has
 * (All files access, or the status folder you picked); nothing leaves the phone.
 */
object StatusAutoSave {

    private const val JOB_ID = 7101
    private const val CHANNEL = "status_auto"
    private const val NOTE_ID = 7102
    private const val WA = "com.whatsapp"
    private const val WAB = "com.whatsapp.w4b"

    private class Found(val uri: Uri, val name: String, val mime: String)

    private val lock = Any()
    @Volatile private var lastRun = 0L

    // ---------- turning it on and off ----------

    fun setEnabled(c: Context, on: Boolean) {
        AppPrefs.setStatusAuto(c, on)
        if (on) schedule(c) else cancel(c)
    }

    /** Keeps the background check booked (called at app start; does nothing when it is off or already booked). */
    fun ensureScheduled(c: Context) {
        if (!AppPrefs.statusAuto(c)) return
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) == null) schedule(c)
    }

    private fun schedule(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, StatusAutoJob::class.java))
            .setPeriodic(30 * 60 * 1000L, 10 * 60 * 1000L)
            .setPersisted(true)                                   // keeps going after the phone restarts
            .setRequiresBatteryNotLow(true)
            .build()
        runCatching { js.schedule(job) }
    }

    private fun cancel(c: Context) {
        c.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    // ---------- the work ----------

    /** In the app: saves new statuses in the background at most once a minute. */
    fun runSoon(c: Context, then: ((Int) -> Unit)? = null) {
        if (!AppPrefs.statusAuto(c)) return
        if (System.currentTimeMillis() - lastRun < 60_000) return
        val app = c.applicationContext
        Thread {
            val n = runNow(app)
            if (then != null) android.os.Handler(android.os.Looper.getMainLooper()).post { then(n) }
        }.start()
    }

    /** Blocking. Copies every status not saved yet; returns how many were saved. */
    fun runNow(c: Context): Int = synchronized(lock) {
        lastRun = System.currentTimeMillis()
        if (!AppPrefs.statusAuto(c)) return 0
        val already = AppPrefs.savedStatuses(c)
        val dir = FileStore.dir(FileStore.STATUS)
        val direct = FileStore.canWriteDirect(c)
        var saved = 0
        for (pkg in listOf(WA, WAB)) {
            for (f in statuses(c, pkg)) {
                if (f.name in already) continue
                if (direct && java.io.File(dir, f.name).exists()) { AppPrefs.addSavedStatus(c, f.name); continue }
                val ok = runCatching {
                    FileStore.saveWith(c, f.name, f.mime, FileStore.STATUS) { out ->
                        c.contentResolver.openInputStream(f.uri)!!.use { it.copyTo(out, 1 shl 16) }
                    }
                }.isSuccess
                if (ok) { AppPrefs.addSavedStatus(c, f.name); saved++ }
            }
        }
        return saved
    }

    /** The statuses WhatsApp keeps now, read the way the status saver can (file access, else the picked folder). */
    @Suppress("DEPRECATION")
    private fun statuses(c: Context, pkg: String): List<Found> {
        val fileAccess = if (android.os.Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (fileAccess) {
            val root = Environment.getExternalStorageDirectory()
            val paths = if (pkg == WAB) listOf("Android/media/$WAB/WhatsApp Business/Media/.Statuses", "WhatsApp Business/Media/.Statuses")
            else listOf("Android/media/$WA/WhatsApp/Media/.Statuses", "WhatsApp/Media/.Statuses")
            val folder = paths.map { java.io.File(root, it) }.firstOrNull { it.isDirectory } ?: return emptyList()
            return folder.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith(".") }.mapNotNull { f ->
                mimeOf(f.name, null)?.let { Found(Uri.fromFile(f), f.name, it) }
            }
        }
        val (tree, doc) = AppPrefs.statusFolder(c, pkg) ?: return emptyList()
        val treeUri = Uri.parse(tree)
        val out = ArrayList<Found>()
        runCatching {
            c.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, doc),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(1).orEmpty()
                    if (name.startsWith(".")) continue
                    val mime = mimeOf(name, cur.getString(2)) ?: continue
                    out += Found(DocumentsContract.buildDocumentUriUsingTree(treeUri, cur.getString(0)), name, mime)
                }
            }
        }
        return out
    }

    /** Photos and videos only (WhatsApp's folder also holds hidden helper files). */
    private fun mimeOf(name: String, given: String?): String? {
        if (given != null && (given.startsWith("image/") || given.startsWith("video/"))) return given
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "3gp" -> "video/3gpp"
            else -> null
        }
    }

    /** A quiet note: "3 new WhatsApp statuses saved". Tapping it opens the Social page. */
    fun notifySaved(c: Context, n: Int) {
        if (n <= 0) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Saved WhatsApp statuses", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            c, NOTE_ID,
            Intent(c, MainActivity::class.java).putExtra(EXTRA_OPEN_STATUS, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val note = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(Ui.accent(c))
            .setContentTitle(if (n == 1) "1 new WhatsApp status saved" else "$n new WhatsApp statuses saved")
            .setContentText("In Rainax Tube/Status")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTE_ID, note) }
    }

    const val EXTRA_OPEN_STATUS = "rainax_open_status"
}

/** The background check Android runs about every 30 minutes while auto-save is on. */
class StatusAutoJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val n = runCatching { StatusAutoSave.runNow(applicationContext) }.getOrDefault(0)
            StatusAutoSave.notifySaved(applicationContext, n)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true       // try again at the next time
}
