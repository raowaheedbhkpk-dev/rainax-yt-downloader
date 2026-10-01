package com.rainax.ytdownloader

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File

/**
 * yt-dlp has no login of its own. When you sign in to YouTube in the app's browser, that session lives
 * in the WebView; for every lookup and every download attempt we hand yt-dlp the CURRENT session.
 */
object CookieHelper {

    /** Browser cookies for a URL ("a=b; c=d"). Safe to call from any thread. */
    fun cookieString(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** True when the in-app browser holds a signed-in Google / YouTube session. */
    fun isSignedIn(): Boolean {
        val c = cookieString("https://www.youtube.com") ?: return false
        return c.contains("SAPISID") || c.contains("__Secure-3PSID") || c.contains("LOGIN_INFO")
    }

    /** Writes a Netscape cookies.txt for yt-dlp. Returns its path, or null. */
    fun writeFile(context: Context, name: String, url: String, cookie: String): String? = try {
        val host = Uri.parse(url).host ?: throw IllegalStateException("no host")
        val domain = "." + host.split('.').takeLast(2).joinToString(".")
        val secure = if (url.startsWith("https")) "TRUE" else "FALSE"
        val lines = StringBuilder("# Netscape HTTP Cookie File\n")
        cookie.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { pair ->
            val n = pair.substringBefore("=")
            val v = pair.substringAfter("=")
            lines.append("$domain\tTRUE\t/\t$secure\t0\t$n\t$v\n")
        }
        val dir = File(context.filesDir, "cookies").apply { mkdirs() }
        val file = File(dir, "$name.txt")
        file.writeText(lines.toString())
        file.absolutePath
    } catch (e: Exception) {
        null
    }

    /** The current browser session for [url] as a cookies file, or null when not signed in. */
    fun fresh(context: Context, name: String, url: String): String? {
        val cookie = cookieString(url) ?: return null
        return writeFile(context, name, url, cookie)
    }
}
