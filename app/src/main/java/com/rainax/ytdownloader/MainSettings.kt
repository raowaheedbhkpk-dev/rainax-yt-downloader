package com.rainax.ytdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rainax.ytdownloader.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The Settings tab (part of MainActivity).
// These are MainActivity's own functions, kept in their own file so each part stays small.

internal fun MainActivity.setupSettings() {
    // The Settings page is a Compose screen now (SettingsScreen.kt): every value comes from AppPrefs when it
    // is drawn and is saved at once, so nothing can put an old switch or chip back after a theme change.
    st.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    st.setContent { RxTheme { SettingsScreen(this) } }
}

/** The folder line, "Allow all files access" and the last MP3 note on Settings are read again. */
internal fun MainActivity.renderFolder() {
    settingsTick.intValue++
}

internal fun MainActivity.nudgeService() {
    val needs = TaskRepository.tasks.value.any {
        it.status == Status.QUEUED || it.status == Status.WAITING || it.status == Status.RUNNING
    }
    if (needs) DownloadService.send(this, DownloadService.ACTION_PUMP)
}

/** 0 follow the phone, 1 light, 2 dark: saved, and every screen redraws in it. */
internal fun MainActivity.chooseTheme(mode: Int) {
    val old = AppPrefs.themeMode(this)
    if (mode == old) return
    AppPrefs.setThemeMode(this, mode)
    StyleSync.themeChanged()
    RainaxApp.applyTheme(mode)      // recreates the screen with the new colours
    // safety net: if this phone didn't redraw the screen in the new mode, do it now
    window.decorView.postDelayed({ StyleSync.recheckMode() }, 700)
}

/**
 * Home's theme button: a small popup with System / Light / Dark.
 */
internal fun MainActivity.showThemePopup(anchor: View) {
    // A small window under the button. It is a ComponentDialog (not a PopupWindow): a dialog gives its Compose
    // content the screen's lifecycle by itself. In a PopupWindow, Compose looked for it on the popup's own
    // window, found none and the app crashed (v18.6).
    val d = resources.displayMetrics.density
    val dialog = androidx.activity.ComponentDialog(this)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val view = androidx.compose.ui.platform.ComposeView(this)
    val mode = AppPrefs.themeMode(this)
    view.setContent {
        RxTheme(glass = true) {
            ThemeMenu(mode, pick = { m -> dialog.dismiss(); chooseTheme(m) })
        }
    }
    dialog.setContentView(view)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.let { w ->
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        w.setDimAmount(0.12f)
        // a small menu: no blur behind it (the other boxes and sheets keep theirs)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            w.attributes = w.attributes.apply { blurBehindRadius = 0 }
        }
        // right under the button, at the right edge (like the old drop-down)
        // Home's round button tells where it is (the view passed in is the whole Home screen there)
        val at = IntArray(2)
        anchor.getLocationOnScreen(at)
        val btn = ThemeAnchor.rect?.takeIf { anchor.height > 200 * d }
            ?: android.graphics.Rect(at[0], at[1], at[0] + anchor.width, at[1] + anchor.height)
        val statusTop = androidx.core.view.ViewCompat.getRootWindowInsets(anchor)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top ?: 0
        w.setGravity(android.view.Gravity.TOP or android.view.Gravity.END)
        w.attributes = w.attributes.apply {
            x = (resources.displayMetrics.widthPixels - btn.right).coerceAtLeast((12 * d).toInt())
            y = (btn.bottom + (6 * d).toInt() - statusTop).coerceAtLeast(0)
        }
    }
    dialog.show()
}

/**
 * Android 13+: offers to put the "Download link" tile in the phone's quick settings (Android's own small
 * question, Add / Don't add). Asked once; on older phones a short tip says where to add it.
 */
internal fun MainActivity.offerClipTile() {
    val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    if (prefs.getBoolean("clip_tile_offered", false)) return
    prefs.edit().putBoolean("clip_tile_offered", true).apply()
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val sbm = getSystemService(android.app.StatusBarManager::class.java) ?: return
        runCatching {
            sbm.requestAddTileService(
                android.content.ComponentName(this, ClipTileService::class.java),
                "Download link",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_download),
                ContextCompat.getMainExecutor(this)
            ) { }
        }
    } else {
        android.widget.Toast.makeText(this, "Tip: add the “Download link” tile to your quick settings (pull down twice, tap ✎)",
            android.widget.Toast.LENGTH_LONG).show()
    }
}
