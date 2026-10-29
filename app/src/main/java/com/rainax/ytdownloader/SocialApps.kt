package com.rainax.ytdownloader

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri

/**
 * The apps the Social page works with. Each shows the real app's own icon, read from the phone when that app is
 * installed (like Android's share menu does); otherwise a square tag in the app's colours with a short name.
 * Also tells which app a pasted link is from.
 */
object SocialApps {

    class App(
        val name: String,
        val short: String,
        val colors: IntArray,
        /** Android apps to open (first one installed wins). */
        val packages: List<String>,
        /** Opened in the browser when the app isn't installed. */
        val web: String,
        /** Link addresses that belong to this app. */
        val hosts: List<String>,
        /** What can be saved from it (shown when a link from it is pasted). */
        val what: String
    )

    val WHATSAPP = App(
        "WhatsApp", "WA", intArrayOf(0xFF25D366.toInt(), 0xFF128C7E.toInt()),
        listOf(StatusSaver.WA, StatusSaver.WAB), "https://www.whatsapp.com", emptyList(), "Statuses"
    )

    val ALL = listOf(
        App("YouTube", "YT", intArrayOf(0xFFFF3355.toInt(), 0xFFCC0000.toInt()), listOf("com.google.android.youtube"),
            "https://m.youtube.com", listOf("youtube.com", "youtu.be"), "Videos up to 8K, Shorts, MP3, playlists"),
        App("TikTok", "TT", intArrayOf(0xFF25F4EE.toInt(), 0xFF111111.toInt(), 0xFFFE2C55.toInt()),
            listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), "https://www.tiktok.com",
            listOf("tiktok.com"), "No watermark, with sound"),
        App("Instagram", "IG", intArrayOf(0xFFFEDA75.toInt(), 0xFFFA7E1E.toInt(), 0xFFD62976.toInt(), 0xFF962FBF.toInt(), 0xFF4F5BD5.toInt()),
            listOf("com.instagram.android"), "https://www.instagram.com", listOf("instagram.com", "instagr.am"), "Reels and video posts"),
        App("Facebook", "FB", intArrayOf(0xFF4C8DF6.toInt(), 0xFF1259D6.toInt()), listOf("com.facebook.katana", "com.facebook.lite"),
            "https://m.facebook.com", listOf("facebook.com", "fb.watch", "fb.com"), "Public videos and Reels"),
        App("X", "X", intArrayOf(0xFF2B2B2B.toInt(), 0xFF000000.toInt()), listOf("com.twitter.android"),
            "https://x.com", listOf("x.com", "twitter.com"), "Videos and GIFs from posts"),
        WHATSAPP,
        App("SoundCloud", "SC", intArrayOf(0xFFFF8800.toInt(), 0xFFFF3300.toInt()), listOf("com.soundcloud.android"),
            "https://soundcloud.com", listOf("soundcloud.com"), "Tracks as MP3"),
        App("Bandcamp", "BC", intArrayOf(0xFF3DC5E8.toInt(), 0xFF1A8FB0.toInt()), listOf("com.bandcamp.android"),
            "https://bandcamp.com", listOf("bandcamp.com"), "Tracks and albums")
    )

    /** The app a link is from, or null. */
    fun of(link: String): App? {
        val host = runCatching { Uri.parse(link.trim()).host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.") }
            .getOrDefault("")
        if (host.isEmpty()) return null
        return ALL.firstOrNull { app -> app.hosts.any { host == it || host.endsWith(".$it") } }
    }

    /** The installed app's own icon from the phone, or null when none of its packages is installed. */
    fun icon(context: Context, app: App): android.graphics.drawable.Drawable? {
        val pm = context.packageManager
        for (p in app.packages) {
            runCatching { pm.getApplicationIcon(p) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** The tag (app not installed): the app's colours as a soft diagonal blend, with soft round corners. */
    fun badge(app: App, context: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, if (app.colors.size == 1) intArrayOf(app.colors[0], app.colors[0]) else app.colors).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4 * context.resources.displayMetrics.density
            if (app.short == "X") {
                // black badge: a thin ring keeps it visible on the dark theme
                val d = context.resources.displayMetrics.density
                setStroke((1.2f * d).toInt().coerceAtLeast(1), 0x40FFFFFF)
            }
        }

    /** Opens the app (to copy a link there), else its website. */
    fun open(context: Context, app: App): Boolean {
        val pm = context.packageManager
        for (p in app.packages) {
            val launch = runCatching { pm.getLaunchIntentForPackage(p) }.getOrNull() ?: continue
            return runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }
}
