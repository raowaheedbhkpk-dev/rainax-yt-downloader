package com.rainax.ytdownloader

import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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

/** 0 follow the phone, 1 light, 2 dark, 3 AMOLED black: saved, and every screen redraws in it. */
internal fun MainActivity.chooseTheme(mode: Int) {
    val old = AppPrefs.themeMode(this)
    if (mode == old) return
    AppPrefs.setThemeMode(this, mode)
    StyleSync.themeChanged()
    RainaxApp.applyTheme(mode)      // recreates the screen with the new colours
    // Dark <-> AMOLED keeps night mode, so rebuild the screen ourselves
    if (old == 3 || mode == 3) window.decorView.post { if (!isDestroyed) recreate() }
    // safety net: if this phone didn't redraw the screen in the new mode, do it now
    else window.decorView.postDelayed({ StyleSync.recheckMode() }, 700)
}

/** One of the sixteen accents: saved, every screen redraws in it, and the app icon follows later. */
internal fun MainActivity.chooseAccent(v: Int) {
    if (v == AppPrefs.accent(this)) return
    AppPrefs.setAccent(this, v)
    android.widget.Toast.makeText(this, "${Accents.of(v).name} · the app icon changes a minute after you leave the app", android.widget.Toast.LENGTH_LONG).show()
    window.decorView.postDelayed({ if (!isDestroyed) recreate() }, 180)      // every screen in the new colour
}

/**
 * Home's sun button: a small popup with System / Light / Dark and Theme. Theme turns the popup into the
 * sixteen accent rings (with a back arrow); picking one closes it and the app redraws in that colour.
 */
internal fun MainActivity.showThemePopup(anchor: View) {
    val d = resources.displayMetrics.density
    val width = (288 * d).toInt()
    val view = androidx.compose.ui.platform.ComposeView(this)
    // a popup is its own window: the Compose view gets the screen's lifecycle by hand
    view.setViewTreeLifecycleOwner(this)
    view.setViewTreeSavedStateRegistryOwner(this)
    view.setViewTreeViewModelStoreOwner(this)
    val pw = android.widget.PopupWindow(view, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    pw.isOutsideTouchable = true
    pw.elevation = 12 * d
    val mode = AppPrefs.themeMode(this)
    view.setContent {
        RxTheme {
            ThemeMenu(this, mode, Accents.of(AppPrefs.accent(this)).name,
                pick = { m -> pw.dismiss(); chooseTheme(m) },
                accent = { v -> pw.dismiss(); chooseAccent(v) })
        }
    }
    pw.showAsDropDown(anchor, 0, 0, android.view.Gravity.END)
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
