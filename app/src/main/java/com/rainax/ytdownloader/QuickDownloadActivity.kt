package com.rainax.ytdownloader

import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The download popup that opens right over TikTok, Instagram, YouTube... without leaving that app:
 *
 *  - Share › RAINAX in any app: the link arrives here and the download sheet slides up at once;
 *  - the "Download link" tile in the phone's quick settings (pull down from the top): reads the link you just
 *    copied and shows the same sheet.
 *
 * Android 10 and later never let an app read the clipboard in the background, so these two (both started by
 * your own tap) are how a copied link gets a popup while RAINAX is closed. Pick a quality, tap Download,
 * and you are back in the app you were watching; the download goes on in RAINAX's notification.
 */
class QuickDownloadActivity : AppCompatActivity() {

    private var waitingForClipboard = false
    private var shown = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null || !Integrity.isOfficial(this)) { finish(); return }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        if (i.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false)) {
            // the clipboard can only be read once this window has focus
            waitingForClipboard = true
            if (hasWindowFocus()) readClipboard()
            return
        }
        val text = if (i.action == Intent.ACTION_SEND) i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() else ""
        show(links(text))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && waitingForClipboard) readClipboard()
    }

    private fun readClipboard() {
        waitingForClipboard = false
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull().orEmpty()
        val urls = links(text)
        if (urls.isEmpty()) {
            Toast.makeText(this, "No video link copied. In TikTok: Share › Copy link, then tap the tile again", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        show(urls)
    }

    private fun show(urls: List<String>) {
        if (shown) return
        when {
            urls.isEmpty() -> {
                Toast.makeText(this, "No video link found in what was shared", Toast.LENGTH_LONG).show()
                finish()
            }
            // several links: the full app's sheet handles lists
            urls.size > 1 -> {
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, urls.joinToString("\n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
            else -> {
                shown = true
                QuickSheet.show(this, urls[0], null, null, onClosed = { finish() }) {
                    Toast.makeText(this, "Downloading in RAINAX", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_FROM_CLIPBOARD = "from_clipboard"

        fun links(text: String): List<String> =
            LinkText.find(text)
    }
}

/** Quick settings tile "Download link": opens the popup for the link you just copied. */
class ClipTileService : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            label = "Download link"
            if (Build.VERSION.SDK_INT >= 29) subtitle = "RAINAX"
            state = android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, QuickDownloadActivity::class.java)
            .putExtra(QuickDownloadActivity.EXTRA_FROM_CLIPBOARD, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
