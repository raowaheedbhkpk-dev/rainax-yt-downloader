package com.rainax.ytdownloader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/*
 * The Settings tab, built with Jetpack Compose (it replaced page_settings.xml).
 *
 * Every value is read straight from AppPrefs when the screen is made and saved the moment it changes. There is
 * no "put the old screen back" step any more, so nothing can replay an old switch or chip on top of the saved
 * choice: the cause of the settings that seemed to reset themselves after a theme or colour change.
 * The look is the same Cinema Pro page as before: big title with an accent bar, quick tiles, the appearance
 * card, gold chapter labels and one clean row per option.
 */

/** One option row: a title, a short line, and what sits at its end. */
private class SetOpt(
    val title: String,
    val sub: String? = null,
    val end: SetEnd,
    val visible: Boolean = true,
    /** The little black icon box at the row's start (S1 layout). */
    val icon: Int = 0
)

private sealed class SetEnd {
    class Toggle(val on: Boolean, val set: (Boolean) -> Unit) : SetEnd()
    class Choice(val options: List<Pair<String, Int>>, val chosen: Int, val pick: (Int) -> Unit) : SetEnd()
    class Open(val go: () -> Unit) : SetEnd()
    object Plain : SetEnd()
    class Note(val text: String) : SetEnd()
}

private class SetChapter(val label: String, val rows: List<SetOpt>)

@Composable
internal fun SettingsScreen(act: MainActivity) {
    val rx = LocalRx.current
    val view = LocalView.current

    // every value straight from the saved settings (read again each time the page is shown or the app resumes,
    // in case one was changed somewhere else, e.g. Wi-Fi only from the download list)
    val tick = act.settingsTick.intValue
    var wifi by remember(tick) { mutableStateOf(AppPrefs.wifiOnly(act)) }
    var bgPlay by remember(tick) { mutableStateOf(AppPrefs.backgroundPlay(act)) }
    var sponsor by remember(tick) { mutableStateOf(AppPrefs.sponsorBlock(act)) }
    var clip by remember(tick) { mutableStateOf(AppPrefs.clipDetect(act)) }
    var opening by remember(tick) { mutableStateOf(AppPrefs.openingTitle(act)) }
    var autoRetry by remember(tick) { mutableStateOf(AppPrefs.autoRetry(act)) }
    var autoClear by remember(tick) { mutableStateOf(AppPrefs.autoClear(act)) }
    var resume by remember(tick) { mutableStateOf(AppPrefs.resumeVideos(act)) }
    var historyPaused by remember(tick) { mutableStateOf(AppPrefs.historyPaused(act)) }
    var parallel by remember(tick) { mutableStateOf(AppPrefs.maxParallel(act).let { if (it in 1..4) it else 2 }) }
    var autoplay by remember(tick) { mutableStateOf(AppPrefs.feedAutoplay(act).let { if (it in 0..2) it else 2 }) }
    var themeMode by remember(tick) { mutableStateOf(AppPrefs.themeMode(act)) }
    var query by remember { mutableStateOf("") }
    var allAccents by remember { mutableStateOf(false) }

    // folder, "All files access" and the last MP3 note change outside this screen (MainActivity.renderFolder)
    val folder = remember(tick) { FileStore.describe(act) }
    val needsAllFiles = remember(tick) { act.needsAllFiles() }
    val mp3 = remember(tick) { AppPrefs.lastMp3Report(act) }
    val version = remember {
        runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    val chapters = listOf(
        SetChapter("GENERAL", listOf(
            SetOpt("Wi-Fi only", "Downloads wait for Wi-Fi", SetEnd.Toggle(wifi) {
                wifi = it; AppPrefs.setWifiOnly(act, it); act.nudgeService(); Haptics.tick(view)
            }, icon = R.drawable.ic_set_wifi),
            SetOpt("Background play", "Keeps playing with the screen off", SetEnd.Toggle(bgPlay) {
                bgPlay = it; AppPrefs.setBackgroundPlay(act, it); Haptics.tick(view)
            }, icon = R.drawable.ic_set_headphones),
            SetOpt("Skip sponsors", "Jumps over sponsored parts", SetEnd.Toggle(sponsor) {
                sponsor = it; AppPrefs.setSponsorBlock(act, it); Haptics.tick(view)
            }, icon = R.drawable.ic_set_skip),
            SetOpt("Detect copied links", "Offers to download a link you copy", SetEnd.Toggle(clip) {
                clip = it; AppPrefs.setClipDetect(act, it); Haptics.tick(view)
                if (it) act.offerClipTile()       // copied links while RAINAX is closed: the quick settings tile
            }, icon = R.drawable.ic_set_link)
        )),
        SetChapter("STARTUP", listOf(
            SetOpt("Opening title", "A one-second RAINAX title card when the app starts",
                SetEnd.Toggle(opening) { opening = it; AppPrefs.setOpeningTitle(act, it) }, icon = R.drawable.ic_play_box)
        )),
        SetChapter("DOWNLOADS", listOf(
            SetOpt("Simultaneous downloads", "How many files download at the same time",
                SetEnd.Choice(listOf("1" to 1, "2" to 2, "3" to 3, "4" to 4), parallel) {
                    parallel = it; AppPrefs.setMaxParallel(act, it); act.nudgeService()
                }, icon = R.drawable.ic_download),
            SetOpt("Auto-retry failed downloads", "Tries again after 1, 3 and 10 minutes, and when the internet comes back",
                SetEnd.Toggle(autoRetry) { autoRetry = it; AppPrefs.setAutoRetry(act, it) }, icon = R.drawable.ic_refresh),
            SetOpt("Auto-clear finished list", "Finished items leave the list after 7 days (the files stay)",
                SetEnd.Toggle(autoClear) { autoClear = it; AppPrefs.setAutoClear(act, it) }, icon = R.drawable.ic_playlist),
            SetOpt("Downloads stop when the screen is off?", "Let RAINAX run in the background",
                SetEnd.Open { act.openBatterySettings() }, icon = R.drawable.ic_set_battery),
            SetOpt("Last MP3", null, SetEnd.Note("Last MP3: $mp3"), visible = mp3.isNotBlank())
        )),
        SetChapter("PLAYBACK", listOf(
            SetOpt("Autoplay in feed", "The video you stop on plays silently in the list",
                SetEnd.Choice(listOf("Always" to 2, "Wi-Fi only" to 1, "Off" to 0), autoplay) {
                    autoplay = it; AppPrefs.setFeedAutoplay(act, it); FeedAutoplay.refresh()
                }, icon = R.drawable.ic_play),
            SetOpt("Continue where you left off", "Videos start again where you stopped",
                SetEnd.Toggle(resume) { resume = it; AppPrefs.setResumeVideos(act, it) }, icon = R.drawable.ic_history)
        )),
        SetChapter("STORAGE", listOf(
            SetOpt("Saved in", folder, SetEnd.Plain, icon = R.drawable.ic_download),
            SetOpt("Allow all files access", "Needed to save in your Downloads folder",
                SetEnd.Open { act.openAllFilesSettings(thenInstallAsk = false) }, visible = needsAllFiles, icon = R.drawable.ic_lock_open),
            SetOpt("Private folder", "Hidden videos behind your PIN", SetEnd.Open { PrivateActivity.open(act) }, icon = R.drawable.ic_lock)
        )),
        SetChapter("PRIVACY", listOf(
            SetOpt("Pause watch history", "Nothing you watch is saved, here or on your YouTube account",
                SetEnd.Toggle(historyPaused) {
                    historyPaused = it
                    AppPrefs.setHistoryPaused(act, it)
                    act.message(if (it) "Watch history paused" else "Watch history is on")
                }, icon = R.drawable.ic_history),
            SetOpt("Clear search history", null, SetEnd.Open {
                RxDialogBuilder(act, R.style.ThemeOverlay_Rainax_Dialog)
                    .setTitle("Clear search history?")
                    .setMessage("Your recent searches will be removed from this phone.")
                    .setPositiveButton("Clear") { _, _ ->
                        AppPrefs.clearSearchHistory(act)
                        Notify.show(act, "Search history cleared")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, icon = R.drawable.ic_search),
            SetOpt("Bring back hidden videos", "Videos and channels you said Not interested to", SetEnd.Open {
                val n = HiddenFeed.count()
                HiddenFeed.clear(act)
                act.message(if (n > 0) "Hidden videos and channels can show again" else "Nothing is hidden")
            }, icon = R.drawable.ic_refresh),
            SetOpt("Clear watch history", "Continue watching and resume points", SetEnd.Open { act.askClearWatchHistory() }, icon = R.drawable.ic_history)
        )),
        SetChapter("ABOUT", listOf(
            SetOpt("Check for app update", null, SetEnd.Open { AppUpdater.check(act, manual = true) }, icon = R.drawable.ic_refresh),
            SetOpt("Rate us on APKPure", "A good review helps a lot", SetEnd.Open { AppShare.rate(act) }, icon = R.drawable.ic_play_box),
            SetOpt("Share RAINAX Tube", "Send the app to a friend", SetEnd.Open { AppShare.share(act) }, icon = R.drawable.ic_share)
        ))
    )

    val q = query.trim()
    val searching = q.isNotEmpty()
    fun matches(o: SetOpt) = !searching || o.title.contains(q, true) || (o.sub?.contains(q, true) == true) ||
        (o.end is SetEnd.Choice && o.end.options.any { it.first.contains(q, true) })

    Column(
        Modifier.fillMaxSize().background(rx.bg).verticalScroll(rememberScrollState()).padding(bottom = 120.dp)
    ) {
        // title with the accent bar, version on the right
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Settings", color = rx.text, fontSize = 34.sp, fontFamily = RxFonts.display, lineHeight = 40.sp)
            }
            Text("v$version", color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
        }

        // search
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(40.dp)
                .background(rx.surfaceAlt, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_set_search), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text2))
            Box(Modifier.padding(start = 8.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search settings", color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body),
                    cursorBrush = SolidColor(rx.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (!searching) {
            // Appearance (S1): one segmented control, System / Light / Dark
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(rx.surface).padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text("Appearance", color = rx.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(rx.card).padding(3.dp)) {
                    listOf(0 to "System", 1 to "Light", 2 to "Dark").forEach { (m, label) ->
                        val chosen = m == themeMode
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                                .background(if (chosen) rx.accent else Color.Transparent)
                                .clickable { if (!chosen) { Haptics.tick(view); themeMode = m; act.chooseTheme(m) } }
                                .semantics { contentDescription = "$label theme" + if (chosen) ", chosen" else "" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (chosen) rx.onAccent else rx.text, fontSize = 13.sp,
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium, fontFamily = RxFonts.body)
                        }
                    }
                }
            }
        }

        var anyMatch = false
        chapters.forEach { ch ->
            val rows = ch.rows.filter { it.visible && matches(it) }
            if (rows.isEmpty()) return@forEach
            anyMatch = true
            if (!searching) {
                Text(
                    ch.label.lowercase().replaceFirstChar { it.uppercase() }, Modifier.padding(start = 32.dp, top = 24.dp, bottom = 8.dp),
                    color = rx.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body
                )
            } else Spacer(Modifier.height(12.dp))
            // iOS style: each chapter's rows sit together in one white rounded card, thin lines between them
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(rx.surface)) {
                rows.forEachIndexed { i, o ->
                    OptionRow(o)
                    if (i < rows.lastIndex && o.end !is SetEnd.Note && rows[i + 1].end !is SetEnd.Note)
                        Box(Modifier.padding(start = if (o.icon != 0) 58.dp else 16.dp).fillMaxWidth().height(0.5.dp).background(rx.divider))
                }
            }
        }
        if (searching && !anyMatch) {
            Text(
                "No setting matches that", Modifier.fillMaxWidth().padding(top = 40.dp),
                color = rx.text2, fontSize = 14.sp, textAlign = TextAlign.Center, fontFamily = RxFonts.body
            )
        }

        Text(
            "RAINAX Tube  v$version", Modifier.fillMaxWidth().padding(top = 24.dp),
            color = rx.text2, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, textAlign = TextAlign.Center
        )
        Text(
            "Only download content you have the right to download. Not affiliated with YouTube, Google or any video site.",
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp),
            color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center
        )
    }
}

/** The row's little black (white in dark) rounded icon box, like iOS Settings. */
@Composable
private fun IconBox(icon: Int) {
    val rx = LocalRx.current
    Box(Modifier.padding(end = 14.dp).size(30.dp).clip(RoundedCornerShape(8.dp)).background(rx.text), contentAlignment = Alignment.Center) {
        Image(painterResource(icon), null, Modifier.size(17.dp), colorFilter = ColorFilter.tint(rx.bg))
    }
}

/** A quick tile: filled with the accent when on, the plain surface with a thin frame when off. */
@Composable
private fun QuickTile(modifier: Modifier, icon: Int, title: String, prefix: String, on: Boolean, toggle: () -> Unit) {
    val rx = LocalRx.current
    val shape = RoundedCornerShape(12.dp)
    val fg = if (on) rx.onAccent else rx.text
    Column(
        modifier.padding(4.dp).height(80.dp)
            .background(if (on) rx.accent else rx.surface, shape)
            .clickable(onClick = toggle)
            .semantics { contentDescription = "$title, ${if (on) "on" else "off"}" }
            .padding(16.dp)
    ) {
        Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(fg))
        Spacer(Modifier.weight(1f))
        Text(title, color = fg, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(prefix + if (on) "On" else "Off", color = if (on) rx.onAccent.copy(alpha = 0.75f) else rx.text2,
            fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1)
    }
}

/** One accent ring (the same drawn ring as the full grid). */
@Composable
private fun Swatch(act: MainActivity, id: Int, cur: Int, rx: RxColors) {
    val acc = Accents.of(id)
    AndroidView(
        factory = { c ->
            val hole = android.graphics.Color.argb(
                (rx.surface.alpha * 255).toInt(), (rx.surface.red * 255).toInt(),
                (rx.surface.green * 255).toInt(), (rx.surface.blue * 255).toInt()
            )
            AccentSwatch(c, androidx.core.content.ContextCompat.getColor(c, acc.color), hole).apply {
                chosen = id == cur
                contentDescription = acc.name + if (id == cur) ", chosen" else ""
                setOnClickListener { if (id != cur) { chosen = true; act.chooseAccent(id) } }
            }
        },
        modifier = Modifier.size(44.dp)
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRow(o: SetOpt) {
    val rx = LocalRx.current
    val end = o.end
    if (end is SetEnd.Note) {
        Text(
            end.text, Modifier.fillMaxWidth().background(rx.card).padding(horizontal = 16.dp, vertical = 12.dp),
            color = rx.text2, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = RxFonts.body
        )
        return
    }
    val click: (() -> Unit)? = when (end) {
        is SetEnd.Toggle -> ({ end.set(!end.on) })          // the whole row flips the switch, not only the small switch
        is SetEnd.Open -> end.go
        else -> null
    }
    val texts: @Composable () -> Unit = {
        Text(o.title, color = rx.text, fontSize = 15.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.Medium)
        if (o.sub != null) {
            Text(o.sub, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 13.sp, lineHeight = 17.sp, fontFamily = RxFonts.body)
        }
    }
    if (end is SetEnd.Choice) {
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (o.icon != 0) IconBox(o.icon)
                Column(Modifier.weight(1f)) { texts() }
            }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                end.options.forEach { (label, value) ->
                    val sel = value == end.chosen
                    FilterChip(
                        selected = sel,
                        onClick = { if (!sel) end.pick(value) },
                        label = { Text(label, fontFamily = RxFonts.body, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = rx.card, labelColor = rx.text,
                            selectedContainerColor = rx.accent, selectedLabelColor = rx.onAccent
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = sel, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent
                        )
                    )
                }
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth()
            .let { m -> if (click != null) m.clickable(onClick = click) else m }
            .heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (o.icon != 0) IconBox(o.icon)
        Column(Modifier.weight(1f).padding(end = 12.dp)) { texts() }
        when (end) {
            is SetEnd.Toggle -> Switch(
                checked = end.on,
                onCheckedChange = { end.set(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = rx.onAccent, checkedTrackColor = rx.accent,
                    uncheckedThumbColor = Color.White, uncheckedTrackColor = rx.surfaceAlt, uncheckedBorderColor = Color.Transparent,
                    checkedBorderColor = Color.Transparent
                )
            )
            is SetEnd.Open -> Image(
                painterResource(R.drawable.ic_set_chevron), null, Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(rx.text2)
            )
            else -> {}
        }
    }
}
