package com.rainax.ytdownloader

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.Stroke as TextStroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/*
 * Home's video lists, drawn with Jetpack Compose (they replaced VideoAdapter on Home): big white cards,
 * search rows and channel rows. [FeedModel] keeps a list (the same rules as VideoAdapter); [FeedHost] shows one
 * of them, or grey placeholders, and is what HomeScreen talks to.
 */

/** One Home list: its videos and how they are laid out. */
class FeedModel(
    private val big: Boolean,
    internal val onOpen: (VideoItem) -> Unit,
    internal val onDownload: (VideoItem) -> Unit
) {
    private val items = mutableListOf<VideoItem>()

    /** Bumped whenever what the list shows changed (the list redraws). */
    var version by mutableIntStateOf(0)
        private set

    private fun changed() {
        rebuild()
        version++
    }

    /** A view on top (the channel header, Continue watching + Shorts). */
    var header: View? = null
        set(value) { if (field !== value) { field = value; changed() } }

    /** + button on every video: pick several, then download them together (null = no + button). */
    var onPick: ((VideoItem) -> Unit)? = null
    var isPicked: (String) -> Boolean = { false }

    /** ⋮ on every video (Save to playlist, Share, Not interested…); null = no ⋮ button. */
    var onMore: ((VideoItem, View) -> Unit)? = null

    var loadingMore = false
        set(value) { if (field != value) { field = value; changed() } }

    var filterHidden = false
    var hideShorts = false

    val count get() = items.size
    internal val isBig get() = big

    private fun keep(list: List<VideoItem>): List<VideoItem> {
        var out = if (filterHidden) list.filter { HiddenFeed.allowed(it) } else list
        if (hideShorts) out = out.filter { !it.isShort && !it.url.contains("/shorts/") }
        return out
    }

    fun submit(list: List<VideoItem>) {
        items.clear()
        items.addAll(keep(list))
        changed()
    }

    fun removeWhere(test: (VideoItem) -> Boolean) {
        items.removeAll(test)
        changed()
    }

    /** Adds the next page (skipping videos already shown). Returns how many were new. */
    fun append(list: List<VideoItem>): Int {
        val known = items.mapTo(HashSet()) { it.url }
        val fresh = keep(list).filter { known.add(it.url) }
        items.addAll(fresh)
        changed()
        return fresh.size
    }

    fun all(): List<VideoItem> = items.toList()

    /** Redraw without changes (watched bars, picks). */
    fun redraw() { version++ }

    /** What sits at each place in the list. */
    sealed class Slot {
        abstract val key: String
        class Header(override val key: String) : Slot()
        class Footer(override val key: String) : Slot()
        class Video(val item: VideoItem, val style: Int, override val key: String) : Slot()
    }

    var slots: List<Slot> = emptyList()
        private set

    private fun rebuild() {
        val raw = ArrayList<Pair<String, (String) -> Slot>>()
        val n = items.size
        fun video(i: Int, normal: Int = ITEM) {
            val it = items[i]
            val style = if (it.isChannel) CHANNEL else normal
            raw += "v$style:${it.url}" to { k -> Slot.Video(it, style, k) }
        }
        if (header != null) raw += "h" to { k -> Slot.Header(k) }
        for (i in 0 until n) video(i)
        if (loadingMore) raw += "f" to { k -> Slot.Footer(k) }
        // every key once (the same video twice in a list must not crash it)
        val seen = HashSet<String>()
        slots = raw.mapIndexed { i, (k, make) -> make(if (seen.add(k)) k else "$k#$i") }
    }

    companion object {
        const val ITEM = 1
        const val CHANNEL = 3
    }
}

/** Up next / Mix: a sheet with the small video rows (the same rows as search results). */
@Composable
internal fun UpNextSheet(title: String, sub: String, m: FeedModel) {
    val rx = LocalRx.current
    @Suppress("UNUSED_VARIABLE") val v = m.version
    val list = m.all()
    Column(Modifier.fillMaxWidth().background(rx.surface)) {
        Text(title, Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 2.dp), color = rx.text, fontSize = 20.sp,
            fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(sub, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
        LazyColumn(
            Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * 0.6f).dp)
                .nestedScroll(androidx.compose.ui.platform.rememberNestedScrollInteropConnection())
        ) {
            items(list.size) { i -> SmallVideoRow(m, list[i]) }
        }
    }
}

/** What HomeScreen shows in its list area: one [FeedModel], or grey placeholders. */
class FeedHost(val view: androidx.compose.ui.platform.ComposeView) {
    var model by mutableStateOf<FeedModel?>(null)
        private set
    /** The Music tab (YouTube Music style rows) instead of a video list. */
    var music by mutableStateOf<MusicHome?>(null)
        private set
    /** Grey placeholders while the first page loads. */
    var skeleton by mutableStateOf(false)
        private set
    /** Room at the bottom (the picks bar). */
    var bottomPad by mutableIntStateOf(16)
    /** Extra room at the bottom for Home's floating tab bar (the list passes under it). */
    var floatingBar by mutableIntStateOf(0)
    /** Pull to refresh is loading the new list (the round spinner shows). */
    var refreshing by mutableStateOf(false)
    internal var riseKey by mutableIntStateOf(0)
    internal var toTop by mutableIntStateOf(0)
    internal var restore by mutableStateOf<Pair<Int, Int>?>(null)
    internal var list: LazyListState? = null

    /** The list came near its end: load the next page. */
    var onNearEnd: () -> Unit = {}

    /** Autoplay in feed for this list. */
    val preview = ComposePreviewList(view)

    fun show(m: FeedModel) {
        skeleton = false
        music = null
        if (model !== m) model = m
    }

    fun showMusic(m: MusicHome) {
        skeleton = false
        if (music !== m) music = m
    }

    fun showSkeleton() { skeleton = true }

    fun scrollToTop() { restore = null; toTop++ }
    fun rise() { riseKey++ }
    fun redraw() { model?.redraw() }
    fun saveScroll(): Pair<Int, Int>? = list?.let { it.firstVisibleItemIndex to it.firstVisibleItemScrollOffset }
    fun restoreScroll(p: Pair<Int, Int>) { restore = p }
    fun canScrollDown() = list?.canScrollForward ?: false
    fun canScrollUp() = list?.canScrollBackward ?: false
}

@Composable
internal fun FeedScreen(host: FeedHost) {
    if (host.skeleton) { FeedSkeleton(); return }
    val music = host.music
    if (music != null) {
        val ms = rememberLazyListState()
        host.list = ms
        LaunchedEffect(host.toTop) { if (host.toTop > 0) ms.scrollToItem(0) }
        MusicHomeScreen(music, ms, host.bottomPad + host.floatingBar)
        return
    }
    val m = host.model ?: return
    @Suppress("UNUSED_VARIABLE") val v = m.version          // redraw when the list changes
    val state = rememberLazyListState()
    host.list = state
    LaunchedEffect(host.toTop) { if (host.toTop > 0) state.scrollToItem(0) }
    LaunchedEffect(host.restore) { host.restore?.let { (i, o) -> state.scrollToItem(i, o); host.restore = null } }
    // the next page when the end comes near
    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - 5 } }
    LaunchedEffect(nearEnd, m.version) { if (nearEnd && m.count > 0) host.onNearEnd() }
    // autoplay in feed: knows when the list moves and rests
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemScrollOffset }.collect { (moving, _) ->
            host.preview.setScrolling(moving)
        }
    }
    host.preview.padBottom = with(LocalDensity.current) { (host.bottomPad + host.floatingBar).dp.roundToPx() }

    val w = LocalConfiguration.current.screenWidthDp
    val cols = when { w >= 1000 -> 3; w >= 600 -> 2; else -> 1 }
    val slots = m.slots
    // tablets: plain videos side by side, everything else full width
    val rows: List<List<FeedModel.Slot>> = remember(slots, cols) {
        if (cols == 1) slots.map { listOf(it) }
        else {
            val out = ArrayList<List<FeedModel.Slot>>()
            var run = ArrayList<FeedModel.Slot>()
            fun flush() { if (run.isNotEmpty()) { run.chunked(cols).forEach { out += it }; run = ArrayList() } }
            slots.forEach { s ->
                if (s is FeedModel.Slot.Video && s.style == FeedModel.ITEM) run += s else { flush(); out += listOf(s) }
            }
            flush()
            out
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = (host.bottomPad + host.floatingBar).dp)) {
        items(rows.size, key = { i -> rows[i].joinToString("|") { it.key } }) { i ->
            val row = rows[i]
            Rise(host.riseKey, i) {
                if (row.size == 1 && cols == 1) Slot(m, host, row[0])
                else Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                    row.forEach { s -> Box(Modifier.weight(1f).padding(horizontal = 6.dp)) { Slot(m, host, s) } }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The first rows of a fresh list rise in one after another. */
@Composable
private fun Rise(key: Int, index: Int, content: @Composable () -> Unit) {
    if (index > 6 || key == 0) { content(); return }
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(index * 45L)
        anim.animateTo(1f, tween(280))
    }
    val shift = with(LocalDensity.current) { 28.dp.toPx() }
    Box(Modifier.graphicsLayer { alpha = anim.value; translationY = (1f - anim.value) * shift }) { content() }
}

@Composable
private fun Slot(m: FeedModel, host: FeedHost, s: FeedModel.Slot) {
    when (s) {
        is FeedModel.Slot.Header -> m.header?.let { h ->
            key(System.identityHashCode(h)) {
                AndroidView(factory = { (h.parent as? ViewGroup)?.removeView(h); h }, modifier = Modifier.fillMaxWidth())
            }
        }
        is FeedModel.Slot.Footer -> Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = LocalRx.current.accent, strokeWidth = 3.dp)
        }
        is FeedModel.Slot.Video -> when {
            s.style == FeedModel.CHANNEL -> ChannelRowView(s.item) { m.onOpen(it) }
            m.isBig -> VideoCard(s.item, m.onOpen, m.onDownload, m.onMore, host.preview, m.onPick, m.isPicked)
            else -> SmallVideoRow(m, s.item)
        }
    }
}

// ---------- small pieces ----------

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.openAndHold(m: FeedModel, item: VideoItem, view: View): Modifier {
    val menu = if (item.isChannel) null else m.onMore
    return this.combinedClickable(onClick = { m.onOpen(item) }, onLongClick = menu?.let { f -> { f(item, view) } })
}

@Composable
private fun Badge(text: String, live: Boolean, modifier: Modifier, small: Boolean = false) {
    if (text.isEmpty()) return
    Text(
        text, modifier.background(if (live) RxRed else Color(0xBF000000), RoundedCornerShape(6.dp))
            .padding(horizontal = if (small) 5.dp else 7.dp, vertical = 2.dp),
        color = Color.White, fontSize = if (small) 10.sp else 12.sp, fontWeight = FontWeight.Bold
    )
}

@Composable
private fun WatchBar(item: VideoItem, modifier: Modifier) {
    val ctx = LocalContext.current
    val watched = if (item.isPlaylist || item.isChannel) -1f else WatchHistory.progress(ctx, item.url)
    if (watched <= 0f) return
    val rx = LocalRx.current
    Row(modifier.fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
        Box(Modifier.weight(watched.coerceIn(0.01f, 1f)).height(3.dp).background(RxRed))
        if (watched < 1f) Box(Modifier.weight(1f - watched.coerceIn(0.01f, 0.99f)).height(3.dp))
    }
}

@Composable
private fun RoundButton(icon: Int, label: String, tint: Color, size: Int, pad: Int, bg: Int = R.drawable.bg_circle_btn, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).then(Modifier.clip(CircleShape).background(if (tint == Color.White) RxRed else LocalRx.current.card))
            .clickable(onClick = onClick).semantics { contentDescription = label }.padding(pad.dp)
    ) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(tint))
    }
}

// ---------- rows ----------

/** Search results: picture on the left, title and channel, + / Download / ⋮. */
@Composable
private fun SmallVideoRow(m: FeedModel, item: VideoItem) {
    val rx = LocalRx.current
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().openAndHold(m, item, view).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Box(Modifier.size(168.dp, 94.dp).clip(rxShape(14)).then(Modifier.background(rx.card))) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 360)
            Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            WatchBar(item, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = rxBodyFont(),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(videoMeta(item), Modifier.padding(top = 4.dp), color = rx.text2, fontSize = 12.sp, fontFamily = rxBodyFont(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.align(Alignment.Bottom), verticalAlignment = Alignment.CenterVertically) {
            val picker = m.onPick
            if (picker != null && !item.isPlaylist && !item.isChannel && item.seconds >= 0) {
                val on = m.isPicked(item.url)
                Spacer(Modifier.width(4.dp))
                RoundButton(if (on) R.drawable.ic_check else R.drawable.ic_add, if (on) "Selected. Tap to unselect" else "Select for download",
                    if (on) rx.accent else rx.text, 34, 8) { picker(item) }
            }
            Spacer(Modifier.width(6.dp))
            RoundButton(R.drawable.ic_download, "Download", Color.White, 38, 8) { m.onDownload(item) }
            val menu = if (item.isChannel) null else m.onMore
            if (menu != null) {
                Box(Modifier.padding(start = 2.dp).size(28.dp, 38.dp).clickable { menu(item, view) }.semantics { contentDescription = "More" },
                    contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_more), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(rx.text2))
                }
            }
        }
    }
}

/** A channel (search results, subscriptions): picture, name, subscribers. */
@Composable
private fun ChannelRowView(item: VideoItem, onOpen: (VideoItem) -> Unit) {
    val rx = LocalRx.current
    Row(Modifier.fillMaxWidth().clickable { onOpen(item) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), circle = true, widthPx = 160)
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(item.title, color = rx.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = rxBodyFont(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = item.uploaded ?: listOfNotNull(
                item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " subscribers" },
                item.count.takeIf { it > 0 }?.let { "$it videos" }
            ).joinToString(" • ")
            Text(meta, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = rxBodyFont(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Image(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(20.dp).graphicsLayer { rotationZ = -90f }, colorFilter = ColorFilter.tint(rx.text2))
    }
}

/** Top pick: the first video as a tall movie poster with Play and Download. */
