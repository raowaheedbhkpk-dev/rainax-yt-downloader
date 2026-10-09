package com.rainax.ytdownloader

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the GitHub Releases of this app's repository.
 * The app checks the latest release, shows what's new, downloads the APK and opens the Android installer.
 * When a newer version exists the update is REQUIRED: the app can't be used until it is installed.
 * (Works when the repository / its releases are public. Offline, the app keeps working.)
 */
object AppUpdater {
    const val REPO = "raowaheedbhkpk-dev/rainax-yt-downloader"

    private class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private var pendingApk: File? = null
    private var job: Job? = null
    private var required: Release? = null          // newer version found: must be installed
    private var dialog: AlertDialog? = null
    private var installAsked = false               // the installer was opened once this time
    private var readyApk: File? = null              // downloaded and complete: no need to download again
    private val dialogShowing get() = dialog?.isShowing == true

    /** Every time the app opens. */
    fun checkOnStart(activity: AppCompatActivity) = check(activity, manual = false)

    fun check(activity: AppCompatActivity, manual: Boolean) {
        if (job?.isActive == true) return
        job = activity.lifecycleScope.launch {
            if (manual) toast(activity, "Checking for updates…")
            val rel = withContext(Dispatchers.IO) {
                try { fetchLatest() } catch (e: Exception) { null }
            }
            if (activity.isFinishing) return@launch
            val current = currentVersion(activity)
            when {
                rel == null -> if (manual) {
                    AlertDialog.Builder(activity)
                        .setTitle("Couldn't check for updates")
                        .setMessage("Check your internet connection. Updates are read from the app's GitHub Releases page, which must be public.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                !isNewer(rel.version, current) || rel.version == AppPrefs.badRelease(activity) ->
                    if (manual) toast(activity, "You have the latest version (v$current)")
                else -> {
                    required = rel
                    offer(activity, rel, current)
                }
            }
        }
    }

    /**
     * Back in the app (from settings or the installer): continue the waiting install,
     * or ask again, because the update is required.
     */
    fun resumeInstall(activity: AppCompatActivity) {
        val f = pendingApk
        if (f != null && activity.packageManager.canRequestPackageInstalls() && f.exists()) {
            pendingApk = null
            install(activity, f)
            return
        }
        val rel = required ?: return
        val current = currentVersion(activity)
        if (!isNewer(rel.version, current)) {        // already updated
            required = null
            return
        }
        if (dialogShowing || job?.isActive == true || downloading(rel)) return
        val done = updateTask(rel.version)?.takeIf { it.status == Status.DONE }
        val file = done?.fileUri?.let { File(it) }
        if (file != null && file.exists()) {
            if (!installAsked) { installAsked = true; installDownloaded(activity, file, rel.version) }
            else offer(activity, rel, current)                // still required: ask again (Update now installs it)
            return
        }
        offer(activity, rel, current)
    }

    private fun currentVersion(activity: AppCompatActivity): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    private fun fetchLatest(): Release? {
        val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        con.connectTimeout = 15_000
        con.readTimeout = 15_000
        con.setRequestProperty("Accept", "application/vnd.github+json")
        con.setRequestProperty("User-Agent", "RAINAX-Tube")
        try {
            if (con.responseCode != 200) return null
            val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(tag, json.optString("body").trim(), a.optString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } finally {
            con.disconnect()
        }
    }

    /** True when [version] is newer than the installed app. */
    fun isNewer(activity: AppCompatActivity, version: String): Boolean = isNewer(version, currentVersion(activity))

    /** "7.10.0" > "7.9.2" (number by number). */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Required update: no "Later". The only ways out are Update or closing the app. */
    private fun offer(activity: AppCompatActivity, rel: Release, current: String) {
        if (dialogShowing || activity.isFinishing || downloading(rel)) return
        val notes = friendlyNotes(rel.notes)
        val size = if (rel.size > 0) "\n\nDownload size: ${formatSize(rel.size)}" else ""
        dialog = AlertDialog.Builder(activity)
            .setTitle("Update required: v${rel.version}")
            .setMessage("A new version of RAINAX Tube is available. Please update to keep using the app. It downloads in the Downloads tab, and you can keep using the app meanwhile.\n\nYou have v$current.\n\nWhat's new:\n$notes$size")
            .setCancelable(false)
            .setPositiveButton("Update now") { _, _ ->
                val ready = readyApk?.takeIf { it.exists() && it.name.contains(rel.version) }
                if (ready != null) install(activity, ready) else download(activity, rel)
                installAsked = false
            }
            .setNegativeButton("Close app") { _, _ -> activity.finishAffinity() }
            .show()
        // Screen rebuilt (theme change, rotation): close this dialog cleanly; the new screen asks again
        val shown = dialog
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                try { shown?.dismiss() } catch (e: Exception) { }
                if (dialog === shown) dialog = null
            }
        })
    }

    /**
     * Release notes as simple bullet lines for users: no links, no GitHub text, no markdown.
     * (Written in whatsnew.txt, one change per line.)
     */
    private fun friendlyNotes(raw: String): String {
        val lines = raw.lines()
            .map { line ->
                line.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")       // [text](link) -> text
                    .replace(Regex("https?://\\S+"), "")                 // bare links
                    .replace(Regex("[*_`#>]+"), "")                         // markdown symbols
                    .replace(Regex("\\s+by @\\S+.*$"), "")               // "by @user in ..."
                    .trim().trimStart('-', '•', ' ').trim()
            }
            .filter { it.isNotEmpty() }
            .filterNot { l ->
                val low = l.lowercase()
                low.startsWith("full changelog") || low.startsWith("what's changed") || low.startsWith("whats changed") ||
                    low.startsWith("new contributors") || low.contains("made their first contribution") ||
                    low.contains("github") || low.endsWith(":")
            }
            .take(8)
        if (lines.isEmpty()) return "• Improvements and bug fixes"
        return lines.joinToString("\n") { "• " + it.take(120) }
    }

    /** Prefix of the download task's format; the version follows ("app:update:8.9.20"). */
    const val TASK_PREFIX = "app:update:"

    /** Called when the update was added to Downloads (the app shows the Library tab). */
    var onQueued: (() -> Unit)? = null

    private fun updateTask(version: String): DownloadTask? =
        TaskRepository.tasks.value.firstOrNull { it.format == TASK_PREFIX + version }

    /**
     * The update downloads in the Downloads tab like any other file: you see its progress, can pause and
     * resume it, and after a network error or a restart it continues where it stopped.
     */
    private fun download(activity: AppCompatActivity, rel: Release) {
        TaskRepository.init(activity)
        val existing = updateTask(rel.version)
        when {
            existing?.status == Status.DONE -> {
                existing.fileUri?.let { installDownloaded(activity, File(it), rel.version); return }
            }
            existing != null -> DownloadService.send(activity, DownloadService.ACTION_RESUME, existing.id)
            else -> {
                // older update downloads are not needed any more
                val olds = TaskRepository.tasks.value.filter { it.format.startsWith(TASK_PREFIX) }.map { it.id }
                if (olds.isNotEmpty()) DownloadService.send(activity, DownloadService.ACTION_CANCEL_SEL, ids = olds.toTypedArray())
                TaskRepository.addAll(listOf(
                    DownloadTask(
                        id = java.util.UUID.randomUUID().toString(),
                        url = rel.apkUrl,
                        title = "RAINAX Tube update v${rel.version}",
                        format = TASK_PREFIX + rel.version,
                        thumbPath = null,
                        status = Status.QUEUED,
                        progress = 0,
                        message = "Queued",
                        retries = 0,
                        fileUri = null,
                        mime = "application/vnd.android.package-archive",
                        createdAt = System.currentTimeMillis()
                    )
                ))
                DownloadService.send(activity, DownloadService.ACTION_PUMP)
            }
        }
        toast(activity, "The update is downloading in Downloads. You can keep using the app.")
        onQueued?.invoke()
    }

    /** The update download finished (or its row was tapped): check the file, then open the installer. */
    fun installDownloaded(activity: AppCompatActivity, file: File, version: String) {
        if (!file.exists()) {
            toast(activity, "The update file is gone. Downloading it again…")
            updateTask(version)?.let { TaskRepository.remove(it.id) }
            required?.let { download(activity, it) }
            return
        }
        // checking the APK reads the whole file: off the main thread
        activity.lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) { verify(activity, file) }
            if (!activity.isFinishing && !activity.isDestroyed) afterCheck(activity, file, version, check)
        }
    }

    private fun afterCheck(activity: AppCompatActivity, file: File, version: String, check: ApkCheck) {
        when (check) {
            ApkCheck.OK -> { readyApk = file; install(activity, file) }
            ApkCheck.OLDER -> {
                AppPrefs.setBadRelease(activity, version)
                required = null
                file.delete()
                updateTask(version)?.let { TaskRepository.remove(it.id) }
                toast(activity, "You already have the latest version")
            }
            ApkCheck.OTHER_KEY -> {
                AppPrefs.setBadRelease(activity, version)
                required = null
                file.delete()
                updateTask(version)?.let { TaskRepository.remove(it.id) }
                AlertDialog.Builder(activity)
                    .setTitle("New version needs a fresh install")
                    .setMessage(
                        "This update is signed with a new key, so it can't install over this version. " +
                            "Uninstall RAINAX Tube, then install the new version from its download page. " +
                            "(Your downloaded files stay in your Downloads folder.)"
                    )
                    .setPositiveButton("Open download page") { _, _ ->
                        runCatching {
                            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$REPO/releases/latest")))
                        }
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
            else -> {
                file.delete()
                updateTask(version)?.let { TaskRepository.remove(it.id) }
                toast(activity, "The update file is damaged. Tap Update to download it again.")
                required?.let { offer(activity, it, currentVersion(activity)) }
            }
        }
    }

    /** True while the required update is downloading (the app stays usable meanwhile). */
    private fun downloading(rel: Release): Boolean {
        val t = updateTask(rel.version) ?: return false
        return t.status == Status.RUNNING || t.status == Status.QUEUED || t.status == Status.WAITING
    }

    private enum class ApkCheck { OK, OLDER, OTHER_KEY, BROKEN }

    /** Same app (package), same signing key, newer version. */
    @Suppress("DEPRECATION")
    private fun verify(activity: AppCompatActivity, file: File): ApkCheck = try {
        val pm = activity.packageManager
        val info = pm.getPackageArchiveInfo(file.path, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info?.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        when {
            info == null || info.packageName != activity.packageName -> ApkCheck.BROKEN
            // (some phones can't read an APK file's key: then Android's installer still checks it)
            !signers.isNullOrEmpty() && !signers.any { pm.hasSigningCertificate(activity.packageName, it.toByteArray(), android.content.pm.PackageManager.CERT_INPUT_RAW_X509) } ->
                ApkCheck.OTHER_KEY
            !isNewer(info.versionName ?: "0", currentVersion(activity)) -> ApkCheck.OLDER
            else -> ApkCheck.OK
        }
    } catch (e: Exception) {
        ApkCheck.BROKEN
    }

    private fun install(activity: AppCompatActivity, file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = file
            AlertDialog.Builder(activity)
                .setTitle("Allow app updates")
                .setMessage("To install the update, allow RAINAX to install apps. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                        )
                    } catch (e: Exception) {
                        toast(activity, "Open Settings > Apps > RAINAX and allow installing apps")
                    }
                }
                .setNegativeButton("Close app") { _, _ -> activity.finishAffinity() }
                .setCancelable(false)
                .show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(activity, "Can't open the installer on this phone")
        }
    }

    private fun toast(activity: AppCompatActivity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
