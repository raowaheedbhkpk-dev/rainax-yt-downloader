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
    // Settings come from the saved choices only. Android's own "put the screen back as it was" (after a theme
    // or colour change rebuilds the screen) used to replay old switch and chip states on top of them, and
    // could save the wrong value; it is turned off for every switch, chip and box on this page.
    Ui.noRestore(st.root)
    st.wifiSwitch.isChecked = AppPrefs.wifiOnly(this)
    st.wifiSwitch.setOnCheckedChangeListener { _, on ->
        AppPrefs.setWifiOnly(this, on)
        paintTiles?.invoke()
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
        if (ids.isEmpty()) return@setOnCheckedStateChangeListener      // a moment with nothing chosen: never save a default
        chooseTheme(when (ids.firstOrNull()) {
            R.id.themeLight -> 1
            R.id.themeDark -> 2
            R.id.themeAmoled -> 3
            else -> 0
        })
    }

    AccentSwatch.fill(st.accentGrid, AppPrefs.accent(this), R.attr.rxSurface) { v -> chooseAccent(v) }

    st.autoRetrySwitch.isChecked = AppPrefs.autoRetry(this)
    st.autoRetrySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoRetry(this, on) }
    st.bgPlaySwitch.isChecked = AppPrefs.backgroundPlay(this)
    st.bgPlaySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setBackgroundPlay(this, on); paintTiles?.invoke() }
    st.sponsorSwitch.isChecked = AppPrefs.sponsorBlock(this)
    st.sponsorSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setSponsorBlock(this, on); paintTiles?.invoke() }
    st.resumeSwitch.isChecked = AppPrefs.resumeVideos(this)
    st.resumeSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setResumeVideos(this, on) }
    st.clipSwitch.isChecked = AppPrefs.clipDetect(this)
    st.clipSwitch.setOnCheckedChangeListener { _, on ->
        AppPrefs.setClipDetect(this, on)
        paintTiles?.invoke()
        if (on) offerClipTile()        // copied links while RAINAX is closed: the quick settings tile
    }
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
        if (ids.isEmpty()) return@setOnCheckedStateChangeListener      // a moment with nothing chosen: never save a default
        AppPrefs.setFeedAutoplay(this, when (ids.firstOrNull()) { R.id.autoplayOff -> 0; R.id.autoplayWifi -> 1; else -> 2 })
        FeedAutoplay.refresh()
    }
    st.parallelGroup.setOnCheckedStateChangeListener { _, ids ->
        if (ids.isEmpty()) return@setOnCheckedStateChangeListener      // a moment with nothing chosen: never save a default
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
    st.setVersion.text = "v$version"
    // last: the quick tiles show the switches only once every switch holds its saved value (they were drawn
    // earlier, from the layout's "off", so Background play / Skip sponsors / Detect links looked turned off
    // after every theme or colour change)
    setupSettingsLook()
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
    android.widget.Toast.makeText(this, "${Accents.of(v).name} · the app icon changes a minute after you leave the app", android.widget.Toast.LENGTH_LONG).show()
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
        AccentSwatch.fill(pb.tpGrid, AppPrefs.accent(this), R.attr.rxSurface) { v ->
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

// ---------- Settings "control center" (style A): quick tiles, theme screens, accent rings, search ----------

private fun MainActivity.themeColor(attr: Int): Int {
    val tv = android.util.TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return android.graphics.Color.GRAY
    return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
}

/** The new top of Settings: wired to the very same switches and choices as the rows below always were. */
internal fun MainActivity.setupSettingsLook() {
    val d = resources.displayMetrics.density
    val accent = Ui.accent(this)
    val text = androidx.core.content.ContextCompat.getColor(this, R.color.rx_text)
    val text2 = androidx.core.content.ContextCompat.getColor(this, R.color.rx_text2)

    // quick tiles: on = filled with the accent, off = the plain surface
    class Tile(val box: View, val icon: android.widget.ImageView, val title: android.widget.TextView,
               val state: android.widget.TextView, val sw: com.google.android.material.materialswitch.MaterialSwitch, val prefix: String)
    val tiles = listOf(
        Tile(st.qtWifi, st.qtWifiIcon, st.qtWifiTitle, st.qtWifiState, st.wifiSwitch, ""),
        Tile(st.qtBg, st.qtBgIcon, st.qtBgTitle, st.qtBgState, st.bgPlaySwitch, ""),
        Tile(st.qtSponsor, st.qtSponsorIcon, st.qtSponsorTitle, st.qtSponsorState, st.sponsorSwitch, ""),
        Tile(st.qtClip, st.qtClipIcon, st.qtClipTitle, st.qtClipState, st.clipSwitch, "Detect · ")
    )
    fun paint(t: Tile) {
        val on = t.sw.isChecked
        if (on) {
            t.box.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF),
                android.graphics.drawable.GradientDrawable().apply { setColor(accent); cornerRadius = 2 * d }, null)
        } else t.box.setBackgroundResource(R.drawable.bg_quick_tile)
        val fg = if (on) android.graphics.Color.WHITE else text
        t.title.setTextColor(fg)
        t.icon.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        t.state.setTextColor(if (on) 0xD9FFFFFF.toInt() else text2)
        t.state.text = t.prefix + if (on) "On" else "Off"
        t.box.contentDescription = "${t.title.text}, ${if (on) "on" else "off"}"
    }
    paintTiles = { tiles.forEach { paint(it) } }
    tiles.forEach { t ->
        paint(t)
        t.box.setOnClickListener {
            t.sw.toggle()                      // the switch's own listener saves it (and repaints the tiles)
            Haptics.tick(it)
        }
    }

    // theme: four little screens, the chosen one framed in the accent
    val previews = listOf(0 to st.themePrevSystem, 1 to st.themePrevLight, 2 to st.themePrevDark, 3 to st.themePrevAmoled)
    val boxes = mapOf(0 to st.themePrevSystemBox, 1 to st.themePrevLightBox, 2 to st.themePrevDarkBox, 3 to st.themePrevAmoledBox)
    val labels = mapOf(0 to st.themePrevSystemLabel, 1 to st.themePrevLightLabel, 2 to st.themePrevDarkLabel, 3 to st.themePrevAmoledLabel)
    fun paintPreviews() {
        val mode = AppPrefs.themeMode(this)
        previews.forEach { (m, _) ->
            val chosen = m == mode
            boxes[m]?.foreground = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(((if (chosen) 2.5f else 1f) * d).toInt(), if (chosen) accent else themeColor(R.attr.rxOutline))
                cornerRadius = 2 * d
            }
            labels[m]?.setTextColor(if (chosen) Ui.accentText(this) else text2)
            labels[m]?.setTypeface(null, if (chosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }
    paintPreviews()
    previews.forEach { (m, v) ->
        v.contentDescription = "${labels[m]?.text} theme"
        v.setOnClickListener {
            Haptics.tick(it)
            chooseTheme(m)
            paintPreviews()
        }
    }

    // accent: the current ring and the next few, then ALL 16 opens every ring
    fun fillMini() {
        val box = st.accentMini
        box.removeAllViews()
        val cur = AppPrefs.accent(this)
        val grid = Accents.GRID
        val start = grid.indexOf(cur).coerceAtLeast(0)
        val ids = (0 until 5).map { grid[(start + it) % grid.size] }
        val hole = themeColor(R.attr.rxSurface)
        ids.forEach { id ->
            val acc = Accents.of(id)
            val sw = AccentSwatch(this, androidx.core.content.ContextCompat.getColor(this, acc.color), hole)
            sw.chosen = id == cur
            sw.contentDescription = acc.name + if (id == cur) ", chosen" else ""
            sw.setOnClickListener { if (id != cur) { sw.chosen = true; chooseAccent(id) } }
            box.addView(sw, android.widget.LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()))
        }
    }
    fillMini()
    st.accentAll.setOnClickListener {
        val open = !st.accentGrid.isVisible
        st.accentGrid.isVisible = open
        st.accentAll.text = if (open) "LESS  ‹" else "ALL 16  ›"
    }

    // search: only the rows whose words match (the tiles and the look card step aside while searching)
    val content = (st.root as? android.view.ViewGroup)?.getChildAt(0) as? android.view.ViewGroup ?: return
    val cards = ArrayList<android.view.ViewGroup>()
    val sectionLabels = ArrayList<View>()
    for (i in 0 until content.childCount) {
        val v = content.getChildAt(i)
        if (v.tag == "settingsCard" && v is android.view.ViewGroup) cards += v
        if (v.tag == "settingsLabel") sectionLabels += v
    }
    // rows hidden at the moment the search starts (e.g. "Last MP3" with nothing to show) stay hidden
    val alwaysHidden = HashSet<View>()
    var wasSearching = false
    fun words(v: View): String = when (v) {
        is android.widget.TextView -> v.text.toString()
        is android.view.ViewGroup -> (0 until v.childCount).joinToString(" ") { words(v.getChildAt(it)) }
        else -> ""
    }
    st.settingsSearch.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) {
            val q = e?.toString()?.trim().orEmpty()
            val searching = q.isNotEmpty()
            if (searching && !wasSearching) {
                alwaysHidden.clear()
                cards.forEach { c -> for (i in 0 until c.childCount) c.getChildAt(i).let { if (it.visibility == View.GONE) alwaysHidden += it } }
            }
            wasSearching = searching
            st.quickTiles.isVisible = !searching
            st.appearanceCard.isVisible = !searching
            var any = false
            cards.forEachIndexed { ci, c ->
                var cardHas = false
                for (i in 0 until c.childCount) {
                    val row = c.getChildAt(i)
                    if (row in alwaysHidden) continue
                    if (row.javaClass == View::class.java) { row.isVisible = !searching; continue }   // the thin lines
                    val match = !searching || words(row).contains(q, ignoreCase = true)
                    row.isVisible = match
                    if (match) cardHas = true
                }
                c.isVisible = cardHas
                sectionLabels.getOrNull(ci)?.isVisible = cardHas && !searching
                if (cardHas) any = true
            }
            st.settingsNoMatch.isVisible = searching && !any
        }
    })
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
