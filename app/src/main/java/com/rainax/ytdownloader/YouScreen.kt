package com.rainax.ytdownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The You tab, built with Jetpack Compose (it replaced page_play.xml, item_task.xml, item_library.xml and the
 * download list adapters). The logic (pause, resume, select, delete, playlists...) is still in MainLibrary.kt
 * and LibraryScreen.kt; this file only draws what [YouState] holds and calls those functions.
 */

/** Everything the You page shows. MainActivity changes it; the page redraws by itself. */
class YouState {
    var tasks by mutableStateOf<List<DownloadTask>>(emptyList())
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())

    var signedIn by mutableStateOf(false)
    var name by mutableStateOf("You")
    var handle by mutableStateOf("Not signed in")
    var avatar by mutableStateOf<String?>(null)

    var meter by mutableStateOf<StorageMeter.Usage?>(null)

    /** Library chip: 0 Downloads, 1 Music, 2 Videos, 3 Playlists. */
    var libMode by mutableIntStateOf(0)
    var libQuery by mutableStateOf("")
    var openPlaylistId by mutableStateOf<String?>(null)
    /** Bumped when a playlist is made, renamed, changed or deleted. */
    var playlistsTick by mutableIntStateOf(0)

    /** Top of the selection buttons in the window (px), for the floating mini player; -1 = not shown. */
    var actionsTop = -1f
}

private val thumbCache = LruCache<String, Bitmap>(80)

/** A download's saved picture, read from disk off the main thread (cached). */
@Composable
private fun rememberThumb(path: String?): ImageBitmap? {
    val bmp by produceState<Bitmap?>(path?.let { thumbCache.get(it) }, path) {
        if (path == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
        }?.also { thumbCache.put(path, it) }
    }
    return remember(bmp) { bmp?.asImageBitmap() }
}

/** A picture from the internet through RAINAX's own loader (same cache as the rest of the app). */
@Composable
private fun NetImage(url: String?, modifier: Modifier, circle: Boolean = false, widthPx: Int = 360) {
    AndroidView(
        factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { v -> Img.load(v, url, circle = circle, widthPx = widthPx) },
        modifier = modifier
    )
}

@Composable
internal fun YouScreen(act: MainActivity, s: YouState) {
    val rx = LocalRx.current
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            YouHeader(act, s)
            LibraryChips(act, s)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.libMode == 0) DownloadsList(act, s) else LibraryList(act, s)
            }
        }
        if (s.selecting) {
            SelectionBar(act, s, Modifier.align(Alignment.TopCenter))
            SelectionActions(act, s, Modifier.align(Alignment.BottomCenter))
        } else {
            s.actionsTop = -1f
        }
    }
}

// ---------- header ----------

@Composable
private fun YouHeader(act: MainActivity, s: YouState) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.signedIn) {
                OutlinedButton(
                    onClick = { act.onAccountClick() },
                    modifier = Modifier.height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, rx.outline),
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("Accounts", color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body)
                    Image(painterResource(R.drawable.ic_chevron_down), null, Modifier.padding(start = 6.dp).size(18.dp), colorFilter = ColorFilter.tint(rx.text))
                }
            }
            Spacer(Modifier.weight(1f))
            HeaderIcon(R.drawable.ic_bell, "Notifications") { act.home.openNotifications() }
            HeaderIcon(R.drawable.ic_search, "Search") { act.goHome(); act.b.root.post { act.home.openSearch() } }
            HeaderIcon(R.drawable.ic_gear, "Settings") { act.showTab(2) }
        }
        Row(
            Modifier.fillMaxWidth().height(88.dp).clickable { act.onAccountClick() }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar), contentAlignment = Alignment.Center) {
                val a = s.avatar
                if (a != null) NetImage(a, Modifier.size(72.dp), circle = true, widthPx = 160)
                else Image(painterResource(R.drawable.ic_person), null, Modifier.size(52.dp), colorFilter = ColorFilter.tint(rx.text2))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(s.name, color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.handle, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            if (s.signedIn) {
                OutlinedButton(
                    onClick = { act.askSignOut() },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, rx.outline)
                ) { Text("Sign out", color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = { act.signIn.launch(android.content.Intent(act, SignInActivity::class.java)) },
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                ) {
                    Image(painterResource(R.drawable.ic_person), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                    Text("Sign in to YouTube", Modifier.padding(start = 8.dp), fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun HeaderIcon(icon: Int, label: String, onClick: () -> Unit) {
    val rx = LocalRx.current
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
    }
}

/** Downloads / Music / Videos / Playlists, then shortcuts (Private folder, History, Liked, Watch later). */
@Composable
private fun LibraryChips(act: MainActivity, s: YouState) {
    val rx = LocalRx.current
    Row(
        Modifier.fillMaxWidth().height(52.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("Downloads", "Music", "Videos", "Playlists").forEachIndexed { i, label ->
            RxChip(label, selected = s.libMode == i) { act.library.setMode(i) }
        }
        RxChip("Private folder", selected = false, icon = R.drawable.ic_lock) { PrivateActivity.open(act) }
        RxChip("History", selected = false) {
            act.openHomeList("History", if (YtAccount.isSignedIn(act)) "acc:FEhistory" else "local:history")
        }
        RxChip("Liked videos", selected = false) { act.openHomeList("Liked videos", "acc:VLLL") }
        RxChip("Watch later", selected = false) { act.openHomeList("Watch later", "acc:VLWL") }
    }
}

@Composable
internal fun RxChip(label: String, selected: Boolean, icon: Int? = null, onClick: () -> Unit) {
    val rx = LocalRx.current
    // a white pill, black (white in dark) when chosen
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = ModernFonts.family, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp) },
        leadingIcon = if (icon != null) {
            { Image(painterResource(icon), null, Modifier.size(16.dp), colorFilter = ColorFilter.tint(if (selected) rx.onAccent else rx.text)) }
        } else null,
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = rx.surface, labelColor = rx.text,
            selectedContainerColor = rx.accent, selectedLabelColor = rx.onAccent
        ),
        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
            borderColor = Color.Transparent, selectedBorderColor = Color.Transparent)
    )
}

// ---------- downloads ----------

@Composable
private fun DownloadsList(act: MainActivity, s: YouState) {
    val rx = LocalRx.current
    val all = s.tasks
    if (all.isEmpty()) { EmptyDownloads(act); return }

    val active = remember(all) {
        val order = HashMap<String, Int>(all.size).apply { all.forEachIndexed { i, t -> put(t.id, all.size - i) } }
        // downloading now always on top, then the queue in the order it will download, then paused and failed
        all.filter { it.status != Status.DONE }.sortedWith(
            compareBy<DownloadTask> {
                when (it.status) { Status.RUNNING -> 0; Status.WAITING -> 1; Status.QUEUED -> 2; Status.PAUSED -> 3; else -> 4 }
            }.thenBy { order[it.id] ?: 0 }
        )
    }
    val done = remember(all) { all.filter { it.status == Status.DONE }.sortedByDescending { it.createdAt } }
    val playable = remember(done) {
        done.filter { it.fileUri != null && act.isPlayable(it) && !it.format.startsWith(AppUpdater.TASK_PREFIX) }
    }
    // tablets: finished downloads in 2-3 columns
    val w = LocalConfiguration.current.screenWidthDp
    val cols = when { w >= 1000 -> 3; w >= 600 -> 2; else -> 1 }

    val list = rememberLazyListState()
    // a new download on top: show it, unless you scrolled far down to look at something else
    val topId = active.firstOrNull()?.id
    LaunchedEffect(topId) { if (topId != null && list.firstVisibleItemIndex <= 3) list.scrollToItem(0) }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 140.dp)) {
        if (playable.isNotEmpty()) {
            item(key = "collection") { CollectionShelf(act, s, playable) }
        }
        if (active.isNotEmpty()) {
            item(key = "activeHead") {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading (${active.size})", Modifier.weight(1f).padding(start = 8.dp), color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
                        if (active.any { it.status == Status.FAILED }) {
                            TextButton(onClick = { TaskRepository.removeFailed(); act.message("Removed failed downloads") }) {
                                Text("Clear failed", color = colorResource(R.color.rx_error), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
                            }
                        }
                        TextButton(onClick = { act.selectAll(done = false) }) {
                            Text("Select all", color = rx.accentText, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Tip: tap Select all, or press and hold a download, then Pause, Resume or Cancel the selected ones",
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = rx.text2, fontSize = 13.sp, fontFamily = RxFonts.body
                    )
                }
            }
            items(active, key = { it.id }) { t -> TaskRow(act, s, t) }
        }
        if (done.isNotEmpty()) {
            item(key = "doneHead") {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Text("Downloaded (${done.size})", Modifier.padding(start = 8.dp, bottom = 6.dp), color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("Select all", Modifier.weight(1f)) { act.selectAll(done = true) }
                        SmallButton("Remove all", Modifier.weight(1f)) { act.removeAllDone() }
                        SmallButton("Delete all", Modifier.weight(1f), danger = true) { act.deleteAllDone() }
                    }
                }
            }
            if (cols == 1) {
                items(done, key = { it.id }) { t -> TaskRow(act, s, t) }
            } else {
                val rows = done.chunked(cols)
                items(rows, key = { r -> "row:" + r.first().id }) { r ->
                    Row(Modifier.fillMaxWidth()) {
                        r.forEach { t -> Box(Modifier.weight(1f)) { TaskRow(act, s, t) } }
                        repeat(cols - r.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallButton(label: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    val rx = LocalRx.current
    val red = colorResource(R.color.rx_error)
    if (danger) {
        OutlinedButton(onClick = onClick, modifier = modifier.height(40.dp), shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, rx.outline), contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(label, color = red, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    } else {
        Button(onClick = onClick, modifier = modifier.height(40.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = rx.accentContainer, contentColor = rx.accentText),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(label, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyDownloads(act: MainActivity) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxSize().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(rx.surface), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_download), null, Modifier.size(38.dp), colorFilter = ColorFilter.tint(rx.text))
        }
        Text("No downloads yet", Modifier.padding(top = 18.dp), color = rx.text, fontSize = 22.sp, fontFamily = RxFonts.display, textAlign = TextAlign.Center)
        Text(
            "Tap Download on any video and it shows up here. You can also share a TikTok, Instagram or Facebook link to RAINAX.",
            Modifier.padding(start = 40.dp, end = 40.dp, top = 8.dp), color = rx.text2, fontSize = 14.sp,
            fontFamily = RxFonts.body, textAlign = TextAlign.Center
        )
        Button(
            onClick = { act.selectNav(R.id.nav_home) },
            modifier = Modifier.padding(top = 24.dp).height(48.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
        ) {
            Image(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
            Text("Explore videos", Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
        }
    }
}

/** Your storage (a strip of film: videos in the accent, MP3 in gold) and the shelf of the newest downloads. */
@Composable
private fun CollectionShelf(act: MainActivity, s: YouState, playable: List<DownloadTask>) {
    val rx = LocalRx.current
    val key = playable.joinToString(",") { it.id }
    LaunchedEffect(key) {
        val u = withContext(Dispatchers.IO) { StorageMeter.measure(act.applicationContext, playable) }
        s.meter = u
    }
    val u = s.meter
    // v19.6: storage in one white rounded card with a plain round bar; the collection as rounded posters
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(rx.surface).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Your storage", Modifier.weight(1f), color = rx.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
                if (u != null) Text("${StorageMeter.short(u.videoBytes + u.audioBytes)} used", color = rx.text2, fontSize = 13.sp, fontFamily = RxFonts.body)
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(rx.surfaceAlt)) {
                if (u != null) {
                    val all = (u.videoBytes + u.audioBytes + u.freeBytes).coerceAtLeast(1L).toFloat()
                    if (u.videoBytes > 0) Box(Modifier.weight((u.videoBytes / all).coerceAtLeast(0.01f)).fillMaxHeight().background(rx.accent))
                    if (u.audioBytes > 0) Box(Modifier.weight((u.audioBytes / all).coerceAtLeast(0.01f)).fillMaxHeight().background(rx.accent.copy(alpha = 0.45f)))
                    Spacer(Modifier.weight((u.freeBytes / all).coerceAtLeast(0.0001f)))
                }
            }
            if (u != null) {
                Text(
                    "Videos ${StorageMeter.short(u.videoBytes)}  ·  MP3 ${StorageMeter.short(u.audioBytes)}  ·  Free ${StorageMeter.short(u.freeBytes)}",
                    Modifier.padding(top = 8.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body
                )
            }
        }
        Text("My collection", Modifier.padding(start = 4.dp, top = 22.dp), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.display)
        LazyRow(Modifier.fillMaxWidth().padding(top = 12.dp).height(168.dp), contentPadding = PaddingValues(horizontal = 0.dp)) {
            items(playable.sortedByDescending { it.createdAt }.take(12), key = { it.id }) { t -> ShelfCase(act, t) }
        }
    }
}

/** One poster in My collection (rounded picture, kind in the corner, the title over it). Tap plays the file. */
@Composable
private fun ShelfCase(act: MainActivity, t: DownloadTask) {
    val rx = LocalRx.current
    val thumb = rememberThumb(t.thumbPath?.takeIf { java.io.File(it).exists() })
    Box(
        Modifier.padding(end = 10.dp).size(108.dp, 156.dp).clip(RoundedCornerShape(14.dp)).background(rx.surfaceAlt)
            .clickable { act.openFile(t) }.semantics { contentDescription = "Play ${t.title}" }
    ) {
        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else NetImage(t.thumbUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_hero_scrim))
        val kind = when {
            t.isAudio -> if (t.format.contains("mp3") || t.mime == "audio/mpeg") "MP3" else "Audio"
            t.format.substringAfter(':').toIntOrNull()?.let { it >= 720 } == true -> "HD"
            else -> "Video"
        }
        Text(
            kind, Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xBF000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold
        )
        Text(
            t.title, Modifier.align(Alignment.BottomStart).padding(8.dp),
            color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body,
            maxLines = 3, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Last status seen per download, so the check mark pops exactly when one finishes. */
private val lastStatus = HashMap<String, Status>()

/** One download: picture, title, what it's doing, progress or live numbers, and its buttons. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(act: MainActivity, s: YouState, t: DownloadTask) {
    val rx = LocalRx.current
    val view = LocalView.current
    val checked = t.id in s.selected
    val selecting = s.selecting
    val red = colorResource(R.color.rx_error)

    // a download that just finished: the check pops over the picture for a moment
    val justDone = remember(t.id, t.status) {
        val before = lastStatus.put(t.id, t.status)
        t.status == Status.DONE && before != null && before != Status.DONE
    }
    var showCheck by remember(t.id, justDone) { mutableStateOf(justDone) }
    if (showCheck) LaunchedEffect(t.id, justDone) { kotlinx.coroutines.delay(1700); showCheck = false }

    Column(
        Modifier.padding(horizontal = 4.dp, vertical = 5.dp).fillMaxWidth()
            .background(rx.card, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { if (selecting) act.toggleSelect(t) else act.openOrWatch(t) },
                onLongClick = { act.startSelect(t) }
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                Checkbox(
                    checked = checked, onCheckedChange = { act.toggleSelect(t) },
                    colors = CheckboxDefaults.colors(checkedColor = rx.accent, checkmarkColor = rx.onAccent, uncheckedColor = rx.text2)
                )
            }
            Box(Modifier.size(84.dp, 64.dp).background(rx.surfaceAlt), contentAlignment = Alignment.Center) {
                val thumb = rememberThumb(t.thumbPath)
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Image(painterResource(if (t.isAudio) R.drawable.ic_audio else R.drawable.ic_video), null, Modifier.size(28.dp),
                    colorFilter = ColorFilter.tint(rx.text2))
                if (t.status == Status.RUNNING) {
                    // projector: the reel turns while this one downloads
                    val spin by rememberInfiniteTransition(label = "reel").animateFloat(
                        0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "reelAngle"
                    )
                    Column(Modifier.fillMaxSize().background(colorResource(R.color.rx_scrim)), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Image(painterResource(R.drawable.ic_film_reel), null, Modifier.size(26.dp).rotate(spin), colorFilter = ColorFilter.tint(Color.White))
                        Text(if (t.progress in 1..100) "${t.progress}%" else "···", Modifier.padding(top = 3.dp),
                            color = Color.White, fontSize = 14.sp, fontFamily = RxFonts.display)
                    }
                }
                if (showCheck) {
                    Box(Modifier.size(34.dp).rxDrawableBg(R.drawable.bg_dl_check).padding(8.dp)) {
                        Image(painterResource(R.drawable.ic_dl_check), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(LocalRx.current.onAccent))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(t.title.ifBlank { t.url }, color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val live = if (t.status == Status.RUNNING) LiveStats.get(t.id) else null
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val pill: Pair<String, Color>? = when (t.status) {
                        // red only for what needs you (downloading, failed); waiting states in grey, paused in amber
                        Status.RUNNING -> "Downloading" to rx.accentText
                        Status.QUEUED -> "Queued" to rx.text2
                        Status.PAUSED -> "Paused" to (if (rx.dark) Color(0xFFFFB340) else Color(0xFFB25E00))
                        Status.WAITING -> "Waiting" to rx.text2
                        Status.FAILED -> "Failed" to rx.accentText
                        Status.DONE -> null
                    }
                    if (pill != null && live == null) {
                        Text(pill.first, Modifier.padding(end = 8.dp).background(pill.second.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp), color = pill.second, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                    }
                    Text(
                        if (live != null) qualityLabel(t) else statusText(t), Modifier.weight(1f),
                        color = if (t.status == Status.FAILED && live == null) red else rx.text2,
                        fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!selecting) {
                IconButton(onClick = { if (t.status == Status.DONE) act.openOrWatch(t) else act.onTaskAction(t) }) {
                    val icon = when (t.status) {
                        Status.RUNNING, Status.QUEUED, Status.WAITING -> R.drawable.ic_pause
                        Status.PAUSED, Status.DONE -> R.drawable.ic_play
                        Status.FAILED -> R.drawable.ic_refresh
                    }
                    val label = when (t.status) {
                        Status.RUNNING, Status.QUEUED, Status.WAITING -> "Pause"
                        Status.PAUSED -> "Resume"
                        Status.FAILED -> "Retry"
                        Status.DONE -> "Open"
                    }
                    Image(painterResource(icon), label, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.accentText))
                }
                if (t.status == Status.DONE) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }) {
                            Image(painterResource(R.drawable.ic_more), "More", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            act.taskMenu(t).forEach { (label, action) ->
                                DropdownMenuItem(text = { Text(label, fontFamily = RxFonts.body) }, onClick = { open = false; action() })
                            }
                        }
                    }
                } else {
                    IconButton(onClick = { act.onTaskClose(t, view) }) {
                        Image(painterResource(R.drawable.ic_close), "Cancel", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
                    }
                }
            }
        }
        val live = if (t.status == Status.RUNNING) LiveStats.get(t.id) else null
        if (live != null) {
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(rx.divider))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                val sp = LiveStats.split(live.bytesPerSec)
                Stat("Speed", if (live.bytesPerSec > 1) sp.first to (sp.second + "/s") else "—" to "", Modifier.weight(1f))
                Stat("Time left", LiveStats.eta(live.etaSec) to "", Modifier.weight(1f))
                val got = LiveStats.split(live.done.toDouble())
                val size = if (live.total > 0) {
                    val all = LiveStats.split(live.total.toDouble())
                    got.first to "/ ${all.first} ${all.second}"
                } else got
                Stat("Size", size, Modifier.weight(1f))
            }
        }
        val showProgress = t.status == Status.RUNNING || t.status == Status.QUEUED || t.status == Status.PAUSED || t.status == Status.WAITING
        if (showProgress) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { (t.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.weight(1f).height(4.dp),
                    color = rx.accent, trackColor = rx.surfaceAlt, gapSize = 0.dp, drawStopIndicator = {}
                )
                if (t.progress in 1..100) {
                    Text("${t.progress}%", Modifier.padding(start = 8.dp), color = rx.accentText, fontSize = 14.sp, fontFamily = RxFonts.display, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** "2.4" big with " MB/s" smaller and grey after it. */
@Composable
private fun Stat(label: String, value: Pair<String, String>, modifier: Modifier) {
    val rx = LocalRx.current
    Column(modifier) {
        Text(label, color = rx.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            buildAnnotatedString {
                append(value.first)
                if (value.second.isNotEmpty()) {
                    append(' ')
                    withStyle(SpanStyle(fontSize = 12.sp, color = rx.text2)) { append(value.second) }
                }
            },
            Modifier.padding(top = 2.dp), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.display, fontWeight = FontWeight.SemiBold
        )
    }
}

private fun statusText(t: DownloadTask): String = when (t.status) {
    Status.DONE -> {
        val kind = when {
            t.format.startsWith(AppUpdater.TASK_PREFIX) -> "App update  •  Tap to install"
            t.isAudio -> "Audio"
            else -> "Video"
        }
        // a freshly made MP3 also says how long making it took
        val note = if (t.message.startsWith("MP3 made")) "  •  ${t.message}" else ""
        "$kind  •  ${android.text.format.DateUtils.getRelativeTimeSpanString(t.createdAt)}$note"
    }
    else -> t.message.ifBlank { t.status.name.lowercase().replaceFirstChar { it.uppercase() } }
}

/** What is being saved: "720p video", "Best quality video", "M4A audio", "App update". */
private fun qualityLabel(t: DownloadTask): String {
    val f = t.format
    return when {
        f.startsWith(AppUpdater.TASK_PREFIX) -> "App update"
        f.startsWith("audio:") -> f.removePrefix("audio:").substringBefore(':').uppercase().ifBlank { "Audio" } + " audio"
        f.startsWith("video:") -> {
            val q = f.removePrefix("video:").substringBefore(':').toIntOrNull() ?: 0
            if (q > 0) "${q}p video" else "Best quality video"
        }
        t.isAudio -> "Audio"
        else -> "Video"
    }
}

// ---------- selection ----------

@Composable
private fun SelectionBar(act: MainActivity, s: YouState, modifier: Modifier) {
    val rx = LocalRx.current
    Row(modifier.fillMaxWidth().height(64.dp).background(rx.surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { act.exitSelection() }) {
            Image(painterResource(R.drawable.ic_close), "Close", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
        }
        Text("${s.selected.size} selected", Modifier.weight(1f), color = rx.text, fontSize = 18.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { act.toggleSelectAll() }) {
            Text("All", color = rx.accentText, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SelectionActions(act: MainActivity, s: YouState, modifier: Modifier) {
    val rx = LocalRx.current
    val red = colorResource(R.color.rx_error)
    val sel = s.tasks.filter { it.id in s.selected }
    Row(
        modifier.fillMaxWidth().background(rx.surface).padding(horizontal = 8.dp)
            .onGloballyPositioned { s.actionsTop = it.positionInWindow().y }
    ) {
        if (sel.any { it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING }) {
            ActionButton(R.drawable.ic_pause, "Pause", rx.text, Modifier.weight(1f)) { act.pauseSelected() }
        }
        if (sel.any { it.status == Status.PAUSED || it.status == Status.FAILED }) {
            ActionButton(R.drawable.ic_play, "Resume", rx.text, Modifier.weight(1f)) { act.resumeSelected() }
        }
        if (sel.any { it.status != Status.DONE }) {
            ActionButton(R.drawable.ic_close, "Cancel", red, Modifier.weight(1f)) { act.cancelSelected() }
        }
        if (sel.any { it.status == Status.DONE }) {
            ActionButton(R.drawable.ic_remove, "Remove", rx.text, Modifier.weight(1f)) { act.removeSelected() }
            ActionButton(R.drawable.ic_delete, "Delete", red, Modifier.weight(1f)) { act.deleteSelected() }
        }
    }
}

@Composable
private fun ActionButton(icon: Int, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(color))
        Text(label, Modifier.padding(top = 2.dp), color = color, fontSize = 12.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- Music / Videos / Playlists ----------

@Composable
private fun LibraryList(act: MainActivity, s: YouState) {
    val rx = LocalRx.current
    val lib = act.library
    @Suppress("UNUSED_VARIABLE") val tick = s.playlistsTick          // redraw when a playlist changes
    val openP = lib.openPlaylist()
    val showPlaylists = s.libMode == 3 && openP == null
    val files = lib.currentFiles()
    val playlists = if (showPlaylists) LocalPlaylists.all(act).filter { s.libQuery.isEmpty() || it.name.contains(s.libQuery, true) } else emptyList()

    Column(Modifier.fillMaxSize()) {
        if (openP != null) {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { lib.back() }) {
                    Image(painterResource(R.drawable.ic_back), "Back", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
                }
                Text(openP.name, Modifier.weight(1f), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                PlaylistMenuButton(act, openP)
            }
        }
        // search
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().height(44.dp)
                .background(rx.surface, RoundedCornerShape(12.dp)).border(1.dp, rx.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_search), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text2))
            Box(Modifier.weight(1f).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                if (s.libQuery.isEmpty()) Text(if (showPlaylists) "Search playlists" else "Search your downloads", color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                BasicTextField(
                    value = s.libQuery, onValueChange = { s.libQuery = it }, singleLine = true,
                    textStyle = TextStyle(color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body),
                    cursorBrush = SolidColor(rx.accent), modifier = Modifier.fillMaxWidth()
                )
            }
            if (s.libQuery.isNotEmpty()) {
                IconButton(onClick = { s.libQuery = "" }, modifier = Modifier.size(32.dp)) {
                    Image(painterResource(R.drawable.ic_close), "Clear", Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.text2))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showPlaylists) {
                Button(
                    onClick = { lib.newPlaylist() }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                ) {
                    Image(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                    Text("New playlist", Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
                }
            } else {
                val on = files.isNotEmpty()
                Button(
                    onClick = { lib.playList(files, 0, shuffle = false) }, enabled = on,
                    modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                ) {
                    Image(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                    Text("Play all", Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { if (files.isNotEmpty()) lib.playList(files, files.indices.random(), shuffle = true) }, enabled = on,
                    modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = rx.accentContainer, contentColor = rx.accentText)
                ) {
                    Image(painterResource(R.drawable.ic_shuffle), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.text))
                    Text("Shuffle", Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
                }
            }
        }
        val empty = if (showPlaylists) playlists.isEmpty() else files.isEmpty()
        if (empty) {
            val q = s.libQuery.trim()
            Text(
                when {
                    q.isNotEmpty() -> "Nothing found for \"$q\""
                    showPlaylists -> "No playlists yet. Tap New playlist, then add songs or videos with their ⋮ button."
                    openP != null -> "This playlist is empty. Open Music or Videos and add songs with their ⋮ button."
                    s.libMode == 1 -> "No music yet. Download a song as M4A or MP3 and it shows here."
                    else -> "No videos yet. Downloaded videos show here."
                },
                Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 48.dp),
                color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 140.dp)) {
                if (showPlaylists) {
                    items(playlists, key = { "p:" + it.id }) { p -> PlaylistRow(act, p) }
                } else {
                    items(files, key = { "f:" + it.id }) { t -> FileRow(act, t, files, openP) }
                }
            }
        }
    }
}

@Composable
private fun LibCard(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val rx = LocalRx.current
    Row(
        Modifier.padding(4.dp).fillMaxWidth().background(rx.card, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun FileRow(act: MainActivity, t: DownloadTask, files: List<DownloadTask>, openP: LocalPlaylists.Playlist?) {
    val rx = LocalRx.current
    val lib = act.library
    LibCard(onClick = { lib.playList(files, files.indexOfFirst { it.id == t.id }.coerceAtLeast(0), shuffle = false) }) {
        Box(Modifier.size(if (t.isAudio) 56.dp else 96.dp, 56.dp).background(rx.surfaceAlt), contentAlignment = Alignment.Center) {
            val thumb = rememberThumb(t.thumbPath?.takeIf { java.io.File(it).exists() })
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Image(painterResource(if (t.isAudio) R.drawable.ic_audio else R.drawable.ic_video), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(t.title.ifBlank { "Download" }, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val kind = when { t.mime?.contains("mpeg") == true -> "MP3"; t.isAudio -> "Music"; else -> "Video" }
            Text(kind + "  •  " + android.text.format.DateUtils.getRelativeTimeSpanString(t.createdAt), Modifier.padding(top = 2.dp),
                color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
        }
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Image(painterResource(R.drawable.ic_more), "More", Modifier.size(22.dp), colorFilter = ColorFilter.tint(rx.text2))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                lib.fileMenu(t, openP).forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label, fontFamily = RxFonts.body) }, onClick = { open = false; action() })
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(act: MainActivity, p: LocalPlaylists.Playlist) {
    val rx = LocalRx.current
    val lib = act.library
    LibCard(onClick = { lib.openPlaylist(p) }) {
        Box(Modifier.size(56.dp).background(rx.surfaceAlt), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_playlist), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(p.name, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = lib.countIn(p)
            Text(if (n == 1) "1 item" else "$n items", Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
        }
        PlaylistMenuButton(act, p)
    }
}

@Composable
private fun PlaylistMenuButton(act: MainActivity, p: LocalPlaylists.Playlist) {
    val rx = LocalRx.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Image(painterResource(R.drawable.ic_more), "More", Modifier.size(22.dp), colorFilter = ColorFilter.tint(rx.text2))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename", fontFamily = RxFonts.body) }, onClick = { open = false; act.library.renamePlaylist(p) })
            DropdownMenuItem(text = { Text("Delete playlist", fontFamily = RxFonts.body) }, onClick = { open = false; act.library.deletePlaylist(p) })
        }
    }
}
