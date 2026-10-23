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
import com.rainax.ytdownloader.databinding.SheetDownloadBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The Settings tab (part of MainActivity).
// These are MainActivity's own functions, kept in their own file so each part stays small.

internal fun MainActivity.setupSettings() {
    st.wifiSwitch.isChecked = AppPrefs.wifiOnly(this)
    st.wifiSwitch.setOnCheckedChangeListener { _, on ->
        AppPrefs.setWifiOnly(this, on)
        nudgeService()
    }
    st.themeGroup.check(
        when (AppPrefs.themeMode(this)) {
            1 -> R.id.themeLight
            2 -> R.id.themeDark
            3 -> R.id.themeAmoled
            else -> R.id.themeSystem
        }
    )
    st.themeGroup.setOnCheckedStateChangeListener { _, ids ->
        chooseTheme(when (ids.firstOrNull()) {
            R.id.themeLight -> 1
            R.id.themeDark -> 2
            R.id.themeAmoled -> 3
            else -> 0
        })
    }

    AccentSwatch.fill(st.accentGrid, AppPrefs.accent(this)) { v -> chooseAccent(v) }

    st.autoRetrySwitch.isChecked = AppPrefs.autoRetry(this)
    st.autoRetrySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoRetry(this, on) }
    st.bgPlaySwitch.isChecked = AppPrefs.backgroundPlay(this)
    st.bgPlaySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setBackgroundPlay(this, on) }
    st.sponsorSwitch.isChecked = AppPrefs.sponsorBlock(this)
    st.sponsorSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setSponsorBlock(this, on) }
    st.resumeSwitch.isChecked = AppPrefs.resumeVideos(this)
    st.resumeSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setResumeVideos(this, on) }
    st.clipSwitch.isChecked = AppPrefs.clipDetect(this)
    st.clipSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setClipDetect(this, on) }
    st.openingSwitch.isChecked = AppPrefs.openingTitle(this)
    st.openingSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setOpeningTitle(this, on) }
    // the whole row is tappable (ripple + a slight press), not only the small switch
    listOf(st.wifiSwitch, st.autoClearSwitch, st.bgPlaySwitch, st.sponsorSwitch, st.resumeSwitch, st.clipSwitch, st.autoRetrySwitch, st.openingSwitch)
        .forEach { sw ->
            val row = sw.parent as? View ?: return@forEach
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            row.setBackgroundResource(tv.resourceId)
            row.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(this, R.animator.rx_press)
            row.setOnClickListener { sw.toggle() }
        }
    st.batteryBtn.setOnClickListener { openBatterySettings() }
    st.checkAppUpdateBtn.setOnClickListener { AppUpdater.check(this, manual = true) }
    st.allFilesBtn.setOnClickListener { openAllFilesSettings(thenInstallAsk = false) }
    st.privateFolderBtn.setOnClickListener { PrivateActivity.open(this) }
    st.clearSearchBtn.setOnClickListener {
        RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Clear search history?")
            .setMessage("Your recent searches will be removed from this phone.")
            .setPositiveButton("Clear") { _, _ ->
                AppPrefs.clearSearchHistory(this)
                Notify.show(this, "Search history cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    st.clearWatchBtn.setOnClickListener { askClearWatchHistory() }
    st.historyPauseSwitch.isChecked = AppPrefs.historyPaused(this)
    st.historyPauseSwitch.setOnCheckedChangeListener { _, on ->
        AppPrefs.setHistoryPaused(this, on)
        message(if (on) "Watch history paused" else "Watch history is on")
    }
    (st.historyPauseSwitch.parent as? View)?.setOnClickListener { st.historyPauseSwitch.toggle() }
    st.unhideBtn.setOnClickListener {
        val n = HiddenFeed.count()
        HiddenFeed.clear(this)
        message(if (n > 0) "Hidden videos and channels can show again" else "Nothing is hidden")
    }
    st.rateAppBtn.setOnClickListener { AppShare.rate(this) }
    st.shareAppBtn.setOnClickListener { AppShare.share(this) }

    st.autoClearSwitch.isChecked = AppPrefs.autoClear(this)
    st.autoClearSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoClear(this, on) }

    st.parallelGroup.check(
        when (AppPrefs.maxParallel(this)) {
            1 -> R.id.par1
            3 -> R.id.par3
            4 -> R.id.par4
            else -> R.id.par2
        }
    )
    st.autoplayGroup.check(
        when (AppPrefs.feedAutoplay(this)) {
            0 -> R.id.autoplayOff
            1 -> R.id.autoplayWifi
            else -> R.id.autoplayAlways
        }
    )
    st.autoplayGroup.setOnCheckedStateChangeListener { _, ids ->
        AppPrefs.setFeedAutoplay(this, when (ids.firstOrNull()) { R.id.autoplayOff -> 0; R.id.autoplayWifi -> 1; else -> 2 })
        FeedAutoplay.refresh()
    }
    st.parallelGroup.setOnCheckedStateChangeListener { _, ids ->
        val n = when (ids.firstOrNull()) {
            R.id.par1 -> 1
            R.id.par3 -> 3
            R.id.par4 -> 4
            else -> 2
        }
        AppPrefs.setMaxParallel(this, n)
        nudgeService()
    }

    renderFolder()
    val version = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        ""
    }
    st.versionText.text = "RAINAX Tube  v$version"
}

internal fun MainActivity.renderFolder() {
    st.folderPath.text = FileStore.describe(this)
    st.allFilesBtn.isVisible = needsAllFiles()
    val mp3 = AppPrefs.lastMp3Report(this)
    st.mp3Report.isVisible = mp3.isNotBlank()
    st.mp3Report.text = "Last MP3: $mp3"
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
    // (the Settings chips follow when the change came from the Home popup)
    st.themeGroup.check(when (mode) { 1 -> R.id.themeLight; 2 -> R.id.themeDark; 3 -> R.id.themeAmoled; else -> R.id.themeSystem })
    RainaxApp.applyTheme(mode)      // recreates the screen with the new colours
    // Dark <-> AMOLED keeps night mode, so rebuild the screen ourselves
    if (old == 3 || mode == 3) window.decorView.post { if (!isDestroyed) recreate() }
}

/** One of the sixteen accents: saved, every screen redraws in it, and the app icon follows later. */
internal fun MainActivity.chooseAccent(v: Int) {
    if (v == AppPrefs.accent(this)) return
    AppPrefs.setAccent(this, v)
    android.widget.Toast.makeText(this, "${Accents.of(v).name} · the app icon changes when you leave the app", android.widget.Toast.LENGTH_LONG).show()
    window.decorView.postDelayed({ if (!isDestroyed) recreate() }, 180)      // every screen in the new colour
}

/**
 * Home's sun button: a small popup with System / Light / Dark and Theme. Theme turns the popup into the
 * sixteen accent rings (with a back arrow); picking one closes it and the app redraws in that colour.
 */
internal fun MainActivity.showThemePopup(anchor: View) {
    val d = resources.displayMetrics.density
    val pb = com.rainax.ytdownloader.databinding.PopupThemeBinding.inflate(layoutInflater)
    val width = (288 * d).toInt()
    val pw = android.widget.PopupWindow(pb.root, width, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    pw.isOutsideTouchable = true
    pw.elevation = 12 * d
    val mode = AppPrefs.themeMode(this)
    pb.tpSystemCheck.visibility = if (mode == 0) View.VISIBLE else View.INVISIBLE
    pb.tpLightCheck.visibility = if (mode == 1) View.VISIBLE else View.INVISIBLE
    pb.tpDarkCheck.visibility = if (mode == 2 || mode == 3) View.VISIBLE else View.INVISIBLE
    pb.tpThemeName.text = Accents.of(AppPrefs.accent(this)).name
    pb.tpSystem.setOnClickListener { pw.dismiss(); chooseTheme(0) }
    pb.tpLight.setOnClickListener { pw.dismiss(); chooseTheme(1) }
    pb.tpDark.setOnClickListener { pw.dismiss(); if (mode != 3) chooseTheme(2) }      // AMOLED already is dark
    fun resize() {
        pb.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        pw.update(width, pb.root.measuredHeight)
    }
    pb.tpTheme.setOnClickListener {
        Haptics.tick(it)
        AccentSwatch.fill(pb.tpGrid, AppPrefs.accent(this)) { v ->
            pb.root.postDelayed({ pw.dismiss(); chooseAccent(v) }, 140)    // the ring shows as chosen for a moment
        }
        pb.tpModes.visibility = View.GONE
        pb.tpAccents.visibility = View.VISIBLE
        resize()
    }
    pb.tpBack.setOnClickListener {
        pb.tpAccents.visibility = View.GONE
        pb.tpModes.visibility = View.VISIBLE
        resize()
    }
    pw.showAsDropDown(anchor, 0, 0, android.view.Gravity.END)
}
