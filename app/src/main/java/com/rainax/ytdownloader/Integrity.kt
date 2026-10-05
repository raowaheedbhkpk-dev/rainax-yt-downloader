package com.rainax.ytdownloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest

/**
 * Official-copy check. Every official RAINAX is signed with the owner's private key; its fingerprint is
 * built into the app (BuildConfig.KEY_SHA256, from the key in GitHub Secrets). A copy that someone
 * changed and signed again with their own key fails this check and asks the user to get the real app.
 */
object Integrity {

    /** True for the official app (or builds without a known key, e.g. local test builds). */
    fun isOfficial(context: Context): Boolean {
        val expected = BuildConfig.KEY_SHA256.lowercase()
        if (expected.isBlank() || BuildConfig.DEBUG) return true
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signing = info.signingInfo ?: return false
            val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
            certs.orEmpty().any { sha256(it.toByteArray()) == expected }
        } catch (e: Exception) {
            true        // can't read our own signature (very unusual phone): never lock out a real user
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Explains it and offers the official download page. The app can't be used. */
    fun showNotOfficial(activity: AppCompatActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Not the official RAINAX")
            .setMessage(
                "This copy of RAINAX was changed by someone else and may not be safe. " +
                    "Please uninstall it and get the official app from its download page."
            )
            .setPositiveButton("Get official RAINAX") { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${AppUpdater.REPO}/releases/latest"))
                    )
                }
                activity.finish()
            }
            .setNegativeButton("Close") { _, _ -> activity.finish() }
            .setCancelable(false)
            .show()
    }
}
