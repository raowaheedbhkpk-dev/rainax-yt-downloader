package com.rainax.ytdownloader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * "Rate us on APKPure" and "Share app": buttons in Settings, and one friendly question after a few
 * downloads (never again once the user rated or said no thanks).
 */
object AppShare {

    /** RAINAX Tube's page on APKPure (shared with friends). If the address ever changes, change it here only. */
    const val APKPURE_URL = "https://apkpure.com/p/com.rainax.ytdownloader"
    /** The reviews page on APKPure (Rate us opens it). */
    const val APKPURE_REVIEWS_URL = "https://apkpure.com/reviews/com.rainax.ytdownloader"
    private const val APKPURE_APP = "com.apkpure.aegon"

    private const val FIRST_ASK = 8        // downloads finished before the first question
    private const val ASK_AGAIN = 25       // "Later": asked again after this many more

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("rate_us", Context.MODE_PRIVATE)

    /** Opens the app's APKPure page (in the APKPure app when installed, else the browser). */
    fun rate(c: Context) {
        prefs(c).edit().putBoolean("done", true).apply()
        val uri = Uri.parse(APKPURE_REVIEWS_URL)
        val inApp = Intent(Intent.ACTION_VIEW, uri).setPackage(APKPURE_APP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { c.startActivity(inApp) }.isSuccess) return
        runCatching {
            c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { android.widget.Toast.makeText(c, "Open $APKPURE_REVIEWS_URL in your browser", android.widget.Toast.LENGTH_LONG).show() }
    }

    /** The phone's share menu with a short message and the download link. */
    fun share(c: Context) {
        val text = "I download YouTube, TikTok, Instagram and Facebook videos in HD with RAINAX Tube. " +
            "It also saves WhatsApp statuses. Get it free: $APKPURE_URL"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "RAINAX Tube")
            .putExtra(Intent.EXTRA_TEXT, text)
        runCatching {
            c.startActivity(Intent.createChooser(send, "Share RAINAX Tube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * [count] more downloads finished: maybe ask, once in a while, for a rating
     * (only when the app is on screen, [canAsk]; otherwise they are just counted).
     */
    fun onDownloadsDone(act: Activity, count: Int, canAsk: Boolean) {
        val p = prefs(act)
        if (p.getBoolean("done", false)) return
        val total = p.getInt("total", 0) + count
        p.edit().putInt("total", total).apply()
        if (!canAsk || total < p.getInt("next", FIRST_ASK)) return
        p.edit().putInt("next", total + ASK_AGAIN).apply()
        act.window.decorView.postDelayed({
            if (act.isFinishing || act.isDestroyed) return@postDelayed
            com.google.android.material.dialog.MaterialAlertDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
                .setTitle("Enjoying RAINAX Tube?")
                .setMessage("A 5-star rating on APKPure helps other people find the app. Sharing it with friends helps too. Thank you!")
                .setPositiveButton("Rate on APKPure") { _, _ -> rate(act) }
                .setNeutralButton("Share") { _, _ -> share(act) }
                .setNegativeButton("Later", null)
                .show()
        }, 1500)
    }
}
