package com.rainax.ytdownloader

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    private var dlJob: Job? = null                  // APK download in progress
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
        if (!dialogShowing && job?.isActive != true && dlJob?.isActive != true) offer(activity, rel, current)
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
        con.setRequestProperty("User-Agent", "RAINAX-YT-Downloader")
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
        if (dialogShowing || activity.isFinishing || dlJob?.isActive == true) return
        val notes = friendlyNotes(rel.notes)
        val size = if (rel.size > 0) "\n\nDownload size: ${formatSize(rel.size)}" else ""
        dialog = AlertDialog.Builder(activity)
            .setTitle("Update required: v${rel.version}")
            .setMessage("A new version of RAINAX is available. Please update to keep using the app.\n\nYou have v$current.\n\nWhat's new:\n$notes$size")
            .setCancelable(false)
            .setPositiveButton("Update now") { _, _ ->
                val ready = readyApk?.takeIf { it.exists() && it.name.contains(rel.version) }
                if (ready != null) install(activity, ready) else download(activity, rel)
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

    private fun download(activity: AppCompatActivity, rel: Release) {
        val density = activity.resources.displayMetrics.density
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = true }
        val text = TextView(activity).apply {
            text = "Starting…"
            gravity = Gravity.END
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val p = (24 * density).toInt()
            setPadding(p, (12 * density).toInt(), p, 0)
            addView(bar)
            addView(text)
        }
        var dl: Job? = null
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Downloading v${rel.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                dl?.cancel()
                required?.let { offer(activity, it, currentVersion(activity)) }
            }
            .show()

        // screen rebuilt during the download: close the progress window cleanly
        activity.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                try { dialog.dismiss() } catch (e: Exception) { }
            }
        })
        dl = activity.lifecycleScope.launch {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "RAINAX-update-${rel.version}.apk")
            val ok = withContext(Dispatchers.IO) {
                try {
                    val con = URL(rel.apkUrl).openConnection() as HttpURLConnection
                    con.instanceFollowRedirects = true
                    con.connectTimeout = 20_000
                    con.readTimeout = 30_000
                    con.setRequestProperty("User-Agent", "RAINAX-YT-Downloader")
                    val total = con.contentLengthLong.takeIf { it > 0 } ?: rel.size
                    var done = 0L
                    con.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var last = -1
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                if (pct != last) {
                                    last = pct
                                    withContext(Dispatchers.Main) {
                                        if (pct >= 0) {
                                            bar.isIndeterminate = false
                                            bar.setProgressCompat(pct, true)
                                            text.text = "$pct%  •  ${formatSize(done)} of ${formatSize(total)}"
                                        } else text.text = formatSize(done)
                                    }
                                }
                            }
                        }
                    }
                    con.disconnect()
                    // a cut-off download would make the installer say "problem parsing the package"
                    isActive && file.length() > 0 && (total <= 0 || done == total)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            try { dialog.dismiss() } catch (e: Exception) { }
            // Safety: the downloaded APK must really be newer than this app, or we'd ask forever
            val apkVersion = if (ok) runCatching {
                activity.packageManager.getPackageArchiveInfo(file.path, 0)?.versionName
            }.getOrNull() else null
            if (ok && apkVersion != null && !isNewer(apkVersion, currentVersion(activity))) {
                AppPrefs.setBadRelease(activity, rel.version)
                required = null
                file.delete()
                toast(activity, "You already have the latest version")
            } else if (ok) {
                readyApk = file
                install(activity, file)
            } else {
                toast(activity, "Update download failed. Check your internet and try again.")
                dlJob = null
                required?.let { offer(activity, it, currentVersion(activity)) }
            }
        }
        dlJob = dl
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
