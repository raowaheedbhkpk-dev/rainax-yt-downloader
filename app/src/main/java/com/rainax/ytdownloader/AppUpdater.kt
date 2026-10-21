package com.rainax.ytdownloader

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
 * The app checks the latest release and, when it is newer, downloads the APK by itself in the background while
 * you keep using the app. A small pill above the bottom bar shows the progress, then "vX READY · INSTALL";
 * tapping Install opens Android's installer (MainActivity.renderUpdatePill).
 * (Works when the repository / its releases are public. Offline, the app keeps working.)
 */
object AppUpdater {
    /** The PUBLIC releases repository (APK files only): updates are read from it. The source code repository is private. */
    const val REPO = "raowaheedbhkpk-dev/rainax-tube-releases"

    private class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    private var pendingApk: File? = null
    private var job: Job? = null
    private var required: Release? = null          // newer version found: downloads by itself

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
                    RxDialogBuilder(activity, R.style.ThemeOverlay_Rainax_Dialog)
                        .setTitle("Couldn't check for updates")
                        .setMessage("Check your internet connection. Updates are read from the app's GitHub Releases page, which must be public.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                !isNewer(rel.version, current) || rel.version == AppPrefs.badRelease(activity) ->
                    if (manual) toast(activity, "You have the latest version (v$current)")
                else -> {
                    required = rel
                    startBackground(activity, rel, manual)
                }
            }
        }
    }

    /**
     * Back in the app (from settings or the installer): continue the waiting install, or make sure the newer
     * version is downloading (the pill shows it).
     */
    fun resumeInstall(activity: AppCompatActivity) {
        val f = pendingApk
        if (f != null && activity.packageManager.canRequestPackageInstalls() && f.exists()) {
            pendingApk = null
            install(activity, f)
            return
        }
        val rel = required ?: return
        if (!isNewer(rel.version, currentVersion(activity))) {        // already updated
            required = null
            return
        }
        if (job?.isActive != true) startBackground(activity, rel, manual = false)
    }

    /** A newer version: download it quietly unless it is already downloading or downloaded. */
    private fun startBackground(activity: AppCompatActivity, rel: Release, manual: Boolean) {
        val t = updateTask(rel.version)
        val ready = t?.status == Status.DONE && t.fileUri?.let { File(it).exists() } == true
        when {
            ready -> if (manual) toast(activity, "v${rel.version} is ready. Tap Install above the bottom bar")
            downloading(rel) -> if (manual) toast(activity, "v${rel.version} is downloading")
            else -> download(activity, rel, quiet = !manual)
        }
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
                    // the file must come from GitHub itself, over https
                    val link = a.optString("browser_download_url")
                    if (!link.startsWith("https://github.com/$REPO/releases/download/", ignoreCase = true)) continue
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

    /** Prefix of the download task's format; the version follows ("app:update:8.9.20"). */
    const val TASK_PREFIX = "app:update:"

    private fun updateTask(version: String): DownloadTask? =
        TaskRepository.tasks.value.firstOrNull { it.format == TASK_PREFIX + version }

    /**
     * The update downloads in the Downloads tab like any other file: you see its progress, can pause and
     * resume it, and after a network error or a restart it continues where it stopped.
     */
    private fun download(activity: AppCompatActivity, rel: Release, quiet: Boolean) {
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
        if (!quiet) toast(activity, "v${rel.version} is downloading. You can keep using the app")
    }

    /** The update download finished (or its row was tapped): check the file, then open the installer. */
    fun installDownloaded(activity: AppCompatActivity, file: File, version: String) {
        if (!file.exists()) {
            toast(activity, "The update file is gone. Downloading it again…")
            updateTask(version)?.let { TaskRepository.remove(it.id) }
            required?.let { download(activity, it, quiet = false) }
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
            ApkCheck.OK -> install(activity, file)
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
                RxDialogBuilder(activity, R.style.ThemeOverlay_Rainax_Dialog)
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
                toast(activity, "The update file was damaged. Downloading it again…")
                required?.let { download(activity, it, quiet = true) }
            }
        }
    }

    /** True while the update is downloading (the app stays usable meanwhile). */
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
            RxDialogBuilder(activity, R.style.ThemeOverlay_Rainax_Dialog)
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
                .setNegativeButton("Not now", null)
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
        Notify.show(activity, msg)
}
