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
 * Home's video lists, drawn with Jetpack Compose (they replaced VideoAdapter on Home): the magazine (Cover story,
 * Top 5, Editor's picks, Your week, chapters with posters, the cinema, More stories), the Top pick, big cards,
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

    /** The first video as a big movie poster with Play and Download ("Top pick"). */
    var hero = false
        set(value) { if (field != value) { field = value; changed() } }

    /** The first tab as a magazine. */
    var magazine = false
        set(value) { if (field != value) { field = value; changed() } }

    /** Magazine: the shelf goes right under the Cover story instead of after Top 5. */
    var headerEarly = false
        set(value) { if (field != value) { field = value; if (magazine) changed() } }

    var chapters: List<Pair<String, String>> = emptyList()
    var onNeedChapter: ((String) -> Unit)? = null
    var onChapterMore: ((String) -> Unit)? = null
    var weekStats: (() -> WeekStats)? = null
    internal var week: WeekStats? = null
        private set
    var moviesId: String? = null
    internal val chapterItems = HashMap<String, List<VideoItem>>()
    private val chapterAsked = HashSet<String>()

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
        if (magazine) chapterItems.keys.toList().forEach { k -> chapterItems[k] = chapterItems[k].orEmpty().filterNot(test) }
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

    fun setChapter(id: String, list: List<VideoItem>) {
        chapterItems[id] = keep(list)
        if (magazine) changed()
    }

    fun clearChapters() {
        chapterItems.clear()
        chapterAsked.clear()
        if (magazine) changed()
    }

    /** A chapter (or the cinema) came into view: load its videos once. */
    internal fun needChapter(id: String) {
        if (chapterItems[id] == null && chapterAsked.add(id)) onNeedChapter?.invoke(id)
    }

    /** Redraw without changes (watched bars, picks). */
    fun redraw() { version++ }

    /** What sits at each place in the list. */
    sealed class Slot {
        abstract val key: String
        class Header(override val key: String) : Slot()
        class Footer(override val key: String) : Slot()
        class Video(val item: VideoItem, val style: Int, override val key: String) : Slot()
        class Section(val label: String, val title: String, override val key: String) : Slot()
        class Chart(val item: VideoItem, val rank: Int, override val key: String) : Slot()
        class Grid(val items: List<VideoItem>, override val key: String) : Slot()
        class Chapter(val label: String, val title: String, val id: String, override val key: String) : Slot()
        class Movies(val label: String, val id: String, override val key: String) : Slot()
        class Week(override val key: String) : Slot()
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
        // magazine: a channel among the first videos moves after the first nine videos
        if (magazine && n >= 9 && items.take(9).any { it.isChannel } && items.count { !it.isChannel } >= 9) {
            val vids = items.filter { !it.isChannel }
            val first = vids.take(9)
            val rest = items.filter { it !in first }
            items.clear(); items.addAll(first); items.addAll(rest)
        }
        if (magazine && n >= 9 && items.take(9).none { it.isChannel }) {
            var chapterNo = 0
            fun label(t: String): String { chapterNo++; return String.format(java.util.Locale.US, "%02d — %s", chapterNo, t.uppercase()) }
            video(0, COVER)
            if (header != null && headerEarly) raw += "h" to { k -> Slot.Header(k) }
            label("Today").let { l -> raw += "s:$l" to { k -> Slot.Section(l, "Top 5 right now", k) } }
            for (r in 1..5) { val it = items[r]; raw += "c$r" to { k -> Slot.Chart(it, r, k) } }
            if (header != null && !headerEarly) raw += "h" to { k -> Slot.Header(k) }
            label("Editor's picks").let { l -> raw += "s:$l" to { k -> Slot.Section(l, "Worth your time", k) } }
            val g = items.subList(6, 9).toList()
            raw += "g6" to { k -> Slot.Grid(g, k) }
            week = weekStats?.invoke()?.takeIf { it.videos > 0 || it.downloads > 0 }
            if (week != null) raw += "w" to { k -> Slot.Week(k) }
            var next = 9
            chapters.forEachIndexed { ci, (title, id) ->
                val got = chapterItems[id]
                if (got == null || got.isNotEmpty()) { val l = label(title); raw += "ch:$id" to { k -> Slot.Chapter(l, title, id, k) } }
                // a pull quote after the first chapter, then the cinema
                if (ci == 0 && next < n && !items[next].isChannel) video(next++, WIDE)
                if (ci == 0) moviesId?.let { mid ->
                    val m = chapterItems[mid]
                    if (m == null || m.isNotEmpty()) { val l = label("Movies"); raw += "m:$mid" to { k -> Slot.Movies(l, mid, k) } }
                }
            }
            if (next < n) label("More stories").let { l -> raw += "s:$l" to { k -> Slot.Section(l, "Keep watching", k) } }
            for (i in next until n) video(i)
        } else {
            if (header != null) raw += "h" to { k -> Slot.Header(k) }
            for (i in 0 until n) video(i, if (hero && big && i == 0 && !items[0].isPlaylist && !items[0].isShort) HERO else ITEM)
        }
        if (loadingMore) raw += "f" to { k -> Slot.Footer(k) }
        // every key once (the same video twice in a list must not crash it)
        val seen = HashSet<String>()
        slots = raw.mapIndexed { i, (k, make) -> make(if (seen.add(k)) k else "$k#$i") }
    }

    companion object {
        const val ITEM = 1
        const val CHANNEL = 3
        const val HERO = 4
        const val COVER = 5
        const val WIDE = 9
    }
}

/** Your week on RAINAX (the magazine's small stats card). */
class WeekStats(val watchedMs: Long, val videos: Int, val downloads: Int, val topChannel: String?)

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
    /** Grey placeholders while the first page loads; true = in the magazine's shape. */
    var skeleton by mutableStateOf<Boolean?>(null)
        private set
    /** Room at the bottom (the picks bar). */
    var bottomPad by mutableIntStateOf(16)
    /** Pull to refresh is loading the new list (the film countdown shows). */
    var refreshing by mutableStateOf(false)
    /** The Music tab's soft glow at the top, in the playing song's colour (null = none). */
    var glow by mutableStateOf<Int?>(null)
    internal var riseKey by mutableIntStateOf(0)
    internal var toTop by mutableIntStateOf(0)
    internal var restore by mutableStateOf<Pair<Int, Int>?>(null)
    internal var list: LazyListState? = null

    /** The list came near its end: load the next page. */
    var onNearEnd: () -> Unit = {}

    /** Autoplay in feed for this list. */
    val preview = ComposePreviewList(view)

    fun show(m: FeedModel) {
        skeleton = null
        music = null
        if (model !== m) model = m
    }

    fun showMusic(m: MusicHome) {
        skeleton = null
        if (music !== m) music = m
    }

    fun showSkeleton(magazine: Boolean) { skeleton = magazine }

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
    val sk = host.skeleton
    if (sk != null) { FeedSkeleton(sk); return }
    val music = host.music
    if (music != null) {
        val ms = rememberLazyListState()
        host.list = ms
        LaunchedEffect(host.toTop) { if (host.toTop > 0) ms.scrollToItem(0) }
        MusicHomeScreen(music, ms, host.bottomPad)
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
    host.preview.padBottom = with(LocalDensity.current) { host.bottomPad.dp.roundToPx() }

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
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = host.bottomPad.dp)) {
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
        is FeedModel.Slot.Video -> when (s.style) {
            FeedModel.CHANNEL -> ChannelRowView(s.item) { m.onOpen(it) }
            // (the modern look keeps the first video a normal card: no film poster)
            FeedModel.HERO -> if (LocalModern.current) VideoCard(s.item, m.onOpen, m.onDownload, m.onMore, host.preview, m.onPick, m.isPicked)
                else HeroCard(m, s.item)
            FeedModel.COVER -> CoverCard(m, s.item)
            FeedModel.WIDE -> WideCard(m, s.item)
            else -> if (m.isBig) VideoCard(s.item, m.onOpen, m.onDownload, m.onMore, host.preview, m.onPick, m.isPicked)
            else SmallVideoRow(m, s.item)
        }
        is FeedModel.Slot.Section -> SectionHead(s.label, s.title, null)
        is FeedModel.Slot.Chart -> ChartRow(m, s.item, s.rank)
        is FeedModel.Slot.Grid -> EditorsGrid(m, s.items)
        is FeedModel.Slot.Week -> m.week?.let { WeekCard(it) }
        is FeedModel.Slot.Chapter -> ChapterRow(m, s)
        is FeedModel.Slot.Movies -> MoviesBand(m, s)
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
        text, modifier.rxDrawableBg(if (live) R.drawable.bg_live else R.drawable.bg_duration)
            .padding(horizontal = if (small) 4.dp else 6.dp, vertical = 1.dp),
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
        Box(Modifier.weight(watched.coerceIn(0.01f, 1f)).height(3.dp).background(rx.accent))
        if (watched < 1f) Box(Modifier.weight(1f - watched.coerceIn(0.01f, 0.99f)).height(3.dp))
    }
}

private fun metaShort(item: VideoItem): String = listOfNotNull(
    item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + if (item.seconds < 0) " watching" else " views" },
    item.uploaded?.takeIf { it.isNotBlank() }
).joinToString(" · ")

@Composable
private fun RoundButton(icon: Int, label: String, tint: Color, size: Int, pad: Int, bg: Int = R.drawable.bg_circle_btn, onClick: () -> Unit) {
    val modern = LocalModern.current
    Box(
        Modifier.size(size.dp).then(if (modern) Modifier.clip(CircleShape).background(LocalRx.current.card) else Modifier.rxDrawableBg(bg))
            .clickable(onClick = onClick).semantics { contentDescription = label }.padding(pad.dp)
    ) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(tint))
    }
}

/** "01 — TODAY" and a big heading (with MORE when [onMore] is set). */
@Composable
private fun SectionHead(label: String, title: String, onMore: (() -> Unit)?) {
    val rx = LocalRx.current
    val modern = LocalModern.current
    Column(Modifier.fillMaxWidth().padding(start = if (modern) 18.dp else 16.dp, end = 16.dp, top = 26.dp, bottom = 10.dp)) {
        Text(label, color = rx.gold, fontSize = if (modern) 12.sp else 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = if (modern) 0.05.em else 0.25.em, fontFamily = rxBodyFont())
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rxHeadCase(title), color = rx.text, fontSize = if (modern) 22.sp else 24.sp, fontFamily = rxHeadFont(),
                fontWeight = if (modern) FontWeight.ExtraBold else FontWeight.Normal)
            Box(Modifier.padding(horizontal = 12.dp).weight(1f).height(1.dp).background(rx.divider))
            if (onMore != null) {
                Text("MORE", Modifier.height(36.dp).clickable(onClick = onMore).padding(horizontal = 4.dp, vertical = 10.dp),
                    color = rx.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.15.em)
            }
        }
    }
}

// ---------- rows ----------

/** Search results: picture on the left, title and channel, + / Download / ⋮. */
@Composable
private fun SmallVideoRow(m: FeedModel, item: VideoItem) {
    val rx = LocalRx.current
    val view = LocalView.current
    val modern = LocalModern.current
    Row(Modifier.fillMaxWidth().openAndHold(m, item, view).padding(horizontal = if (modern) 16.dp else 12.dp, vertical = if (modern) 9.dp else 7.dp)) {
        Box(Modifier.size(168.dp, 94.dp).clip(rxShape(14)).then(if (modern) Modifier.background(rx.card) else Modifier.rxDrawableBg(R.drawable.bg_thumb))) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 360)
            Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.BottomEnd).padding(5.dp))
            WatchBar(item, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = rx.text, fontSize = 14.sp, fontWeight = if (modern) FontWeight.Bold else FontWeight.SemiBold, fontFamily = rxBodyFont(),
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
            RoundButton(R.drawable.ic_download, "Download", rx.accent, 38, 8) { m.onDownload(item) }
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
@Composable
private fun HeroCard(m: FeedModel, item: VideoItem) {
    val view = LocalView.current
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Box(Modifier.fillMaxWidth().height(440.dp).background(Color.Black).openAndHold(m, item, view)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 1080)
            Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_hero_scrim))
            Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.TopEnd).padding(12.dp))
            WatchBar(item, Modifier.align(Alignment.BottomStart))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
                Text("TOP PICK", Modifier.rxDrawableBg(R.drawable.bg_hero_tag).padding(horizontal = 8.dp, vertical = 3.dp),
                    color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.15.em)
                Text(item.title.uppercase(), Modifier.padding(top = 10.dp), color = Color.White, fontSize = 32.sp, lineHeight = 33.sp,
                    fontFamily = RxFonts.display, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.padding(end = 8.dp).size(22.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                        RxNetImage(item.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 96)
                    }
                    Text(videoMeta(item), Modifier.weight(1f), color = colorResource(R.color.rx_on_picture_strong), fontSize = 14.sp,
                        fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val menu = m.onMore
                    if (menu != null) {
                        Box(Modifier.size(32.dp).clickable { menu(item, view) }, contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.ic_more), "More", Modifier.size(22.dp), colorFilter = ColorFilter.tint(Color.White))
                        }
                    }
                }
                Row(Modifier.padding(top = 16.dp).fillMaxWidth()) {
                    Button(
                        onClick = { m.onOpen(item) }, modifier = Modifier.weight(1f).height(50.dp).padding(end = 10.dp), shape = RoundedCornerShape(2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Image(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.Black))
                        Text("PLAY", Modifier.padding(start = 8.dp), letterSpacing = 0.1.em, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                    }
                    Button(
                        onClick = { m.onDownload(item) }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(2.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = Color.White)
                    ) {
                        Image(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
                        Text("DOWNLOAD", Modifier.padding(start = 8.dp), letterSpacing = 0.1.em, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                    }
                }
            }
        }
    }
}

/** The magazine's Cover story. */
@Composable
private fun CoverCard(m: FeedModel, item: VideoItem) {
    val rx = LocalRx.current
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().openAndHold(m, item, view).padding(bottom = 8.dp)) {
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb_big)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 1080)
            Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            WatchBar(item, Modifier.align(Alignment.BottomStart))
        }
        Text(listOf("Cover story", item.uploader).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), color = rx.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.title.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), color = rx.text, fontSize = 32.sp, lineHeight = 34.sp,
            fontFamily = RxFonts.display, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(metaShort(item), Modifier.weight(1f), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1)
            val menu = m.onMore
            if (menu != null) {
                Box(Modifier.size(32.dp, 40.dp).clickable { menu(item, view) }, contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_more), "More", Modifier.size(22.dp), colorFilter = ColorFilter.tint(rx.text2))
                }
            }
            Spacer(Modifier.width(4.dp))
            RoundButton(R.drawable.ic_download, "Download", Color.White, 40, 10, R.drawable.bg_mag_square) { m.onDownload(item) }
        }
    }
}

/** A pull quote: a small picture, an accent bar and the title in quotes. */
@Composable
private fun WideCard(m: FeedModel, item: VideoItem) {
    val rx = LocalRx.current
    val view = LocalView.current
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).fillMaxWidth().background(rx.card).openAndHold(m, item, view).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(124.dp, 70.dp).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb_big)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 360)
            Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.BottomEnd).padding(4.dp), small = true)
        }
        Box(Modifier.padding(start = 12.dp).width(3.dp).height(70.dp).background(rx.accent))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("“${item.title}”", color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                fontFamily = RxFonts.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(item.uploader, metaShort(item)).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(top = 4.dp),
                color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(R.drawable.ic_download, "Download", rx.accent, 36, 8, R.drawable.bg_circle_btn) { m.onDownload(item) }
    }
}

/** One place in the Top 5: number 1 filled in the accent, the others outlined. */
@Composable
private fun ChartRow(m: FeedModel, item: VideoItem, place: Int) {
    val rx = LocalRx.current
    val view = LocalView.current
    val density = LocalDensity.current.density
    Row(Modifier.fillMaxWidth().openAndHold(m, item, view).padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val first = place == 1
        Text(
            place.toString(), Modifier.width(44.dp).semantics { contentDescription = "Number $place" },
            color = if (first) rx.accentText else rx.text, fontSize = 44.sp, fontFamily = RxFonts.display,
            style = if (first) TextStyle.Default else TextStyle(drawStyle = TextStroke(width = 1.5f * density))
        )
        Box(Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb_big)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 360)
            Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.BottomEnd).padding(4.dp), small = true)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(item.uploader, metaShort(item)).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(top = 3.dp),
                color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RoundButton(R.drawable.ic_download, "Download", rx.accent, 40, 10, R.drawable.bg_circle_btn) { m.onDownload(item) }
    }
}

/** Editor's picks: one tall story and two small ones. */
@Composable
private fun EditorsGrid(m: FeedModel, list: List<VideoItem>) {
    Row(Modifier.fillMaxWidth().height(312.dp).padding(horizontal = 16.dp)) {
        list.getOrNull(0)?.let { GridTile(m, it, Modifier.weight(1.25f).fillMaxHeight().padding(end = 6.dp), big = true) }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            list.getOrNull(1)?.let { GridTile(m, it, Modifier.weight(1f).fillMaxWidth(), big = false) }
            Spacer(Modifier.height(6.dp))
            list.getOrNull(2)?.let { GridTile(m, it, Modifier.weight(1f).fillMaxWidth(), big = false) }
        }
    }
}

@Composable
private fun GridTile(m: FeedModel, item: VideoItem, modifier: Modifier, big: Boolean) {
    val view = LocalView.current
    Box(modifier.clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb_big).openAndHold(m, item, view)) {
        RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = if (big) 720 else 480)
        Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_hero_scrim))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(if (big) 12.dp else 9.dp)) {
            Text(item.uploader.uppercase(), color = colorResource(R.color.rx_cinema_gold), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 0.18.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (big) Text(item.title.uppercase(), Modifier.padding(top = 3.dp), color = Color.White, fontSize = 20.sp, lineHeight = 22.sp,
                fontFamily = RxFonts.display, maxLines = 4, overflow = TextOverflow.Ellipsis)
            else Text(item.title, Modifier.padding(top = 3.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(videoBadge(item).takeIf { it.isNotBlank() }, item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " views" })
                .joinToString(" · ")
            Text(meta, Modifier.padding(top = 4.dp), color = colorResource(R.color.rx_cinema_text2), fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1)
        }
    }
}

/** Your week: hours watched, videos, downloads, and your top channel. */
@Composable
private fun WeekCard(w: WeekStats) {
    val rx = LocalRx.current
    val minutes = w.watchedMs / 60_000
    val (num, label) = if (minutes >= 60) String.format(java.util.Locale.US, "%.1f", minutes / 60.0) to "HOURS WATCHED"
    else minutes.toString() to "MINUTES WATCHED"
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp).fillMaxWidth().background(rx.card).padding(16.dp)
            .semantics { contentDescription = "Your week: $num ${label.lowercase()}, ${w.videos} videos, ${w.downloads} downloads" }
    ) {
        Text("YOUR WEEK ON RAINAX", color = rx.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.25.em)
        Box(Modifier.padding(top = 10.dp).size(44.dp, 3.dp).background(rx.accent))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            listOf(Triple(num, label, rx.text), Triple(w.videos.toString(), "VIDEOS", rx.text), Triple(w.downloads.toString(), "DOWNLOADS", rx.accentText))
                .forEach { (n, l, c) ->
                    Column(Modifier.weight(1f)) {
                        Text(n, color = c, fontSize = 32.sp, fontFamily = RxFonts.display)
                        Text(l, Modifier.padding(top = 4.dp), color = rx.text2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.15.em)
                    }
                }
        }
        if (!w.topChannel.isNullOrBlank()) {
            Text("Most watched: ${w.topChannel}", Modifier.padding(top = 14.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1)
        }
    }
}

/** A chapter: the opener and a sideways row of tall posters (its videos load when it first shows). */
@Composable
private fun ChapterRow(m: FeedModel, s: FeedModel.Slot.Chapter) {
    val rx = LocalRx.current
    val got = m.chapterItems[s.id]
    LaunchedEffect(s.id) { m.needChapter(s.id) }
    Column(Modifier.fillMaxWidth()) {
        SectionHead(s.label, s.title, m.onChapterMore?.let { f -> { f(s.id) } })
        Box(Modifier.fillMaxWidth().height(206.dp), contentAlignment = Alignment.Center) {
            if (got == null) LinearProgressIndicator(Modifier.width(120.dp), color = rx.accent, trackColor = rx.surfaceAlt)
            else LazyRow(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(got.size, key = { i -> got[i].url + "#" + i }) { i -> Poster(m, got[i]) }
            }
        }
    }
}

@Composable
private fun Poster(m: FeedModel, item: VideoItem) {
    val view = LocalView.current
    Box(Modifier.padding(horizontal = 4.dp).size(132.dp, 196.dp).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb_big).openAndHold(m, item, view)) {
        RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 480)
        Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_hero_scrim))
        Badge(videoBadge(item), item.seconds < 0, Modifier.align(Alignment.TopEnd).padding(6.dp), small = true)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(9.dp)) {
            Text(item.title, color = Color.White, fontSize = 14.sp, lineHeight = 16.sp, fontFamily = RxFonts.display, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(item.uploader, Modifier.padding(top = 4.dp), color = colorResource(R.color.rx_cinema_text2), fontSize = 10.sp, fontFamily = RxFonts.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Movies: a dark cinema band with NOW SHOWING and a row of tickets (long films first). */
@Composable
private fun MoviesBand(m: FeedModel, s: FeedModel.Slot.Movies) {
    val got = m.chapterItems[s.id]
    LaunchedEffect(s.id) { m.needChapter(s.id) }
    val gold = colorResource(R.color.rx_cinema_gold)
    // real films (over 50 minutes) first; trailers and clips only when there are too few
    val films = got.orEmpty().let { all -> all.filter { it.seconds >= 3000 }.let { f -> if (f.size >= 3) f else all } }
    Column(Modifier.padding(top = 26.dp).fillMaxWidth().background(colorResource(R.color.rx_cinema_deep)).padding(vertical = 14.dp)) {
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(6.dp).rxDrawableBg(R.drawable.bg_marquee_bulbs))
        Text(s.label, Modifier.fillMaxWidth().padding(top = 14.dp), color = colorResource(R.color.rx_on_picture2), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.3.em, textAlign = TextAlign.Center)
        Text(
            "NOW SHOWING", Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center, letterSpacing = 0.22.em,
            style = TextStyle(color = gold, fontSize = 32.sp, fontFamily = RxFonts.display,
                shadow = Shadow(colorResource(R.color.rx_cinema_gold_glow), blurRadius = 28f))
        )
        LazyRow(Modifier.padding(top = 12.dp).fillMaxWidth().height(196.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(films.size, key = { i -> films[i].url + "#" + i }) { i -> Ticket(m, films[i]) }
        }
        val more = m.onChapterMore
        if (more != null) {
            Text("ALL MOVIES  ›", Modifier.align(Alignment.CenterHorizontally).height(40.dp).clickable { more(s.id) }.padding(horizontal = 12.dp, vertical = 12.dp),
                color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.em)
        }
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp).fillMaxWidth().height(6.dp).rxDrawableBg(R.drawable.bg_marquee_bulbs))
    }
}

/** A cinema ticket: the poster, title and channel, and an ADMIT ONE stub with the running time. */
@Composable
private fun Ticket(m: FeedModel, item: VideoItem) {
    val rx = LocalRx.current
    val view = LocalView.current
    val d = LocalDensity.current.density
    Box(
        Modifier.padding(horizontal = 6.dp).size(284.dp, 188.dp)
            .drawBehind {
                drawIntoCanvas { cv ->
                    val c = cv.nativeCanvas
                    val w = size.width
                    val h = size.height
                    val x = w - 64 * d
                    val notch = 9 * d
                    val shape = android.graphics.Path().apply {
                        addRoundRect(android.graphics.RectF(0f, 0f, w, h), 3 * d, 3 * d, android.graphics.Path.Direction.CW)
                    }
                    val hole = android.graphics.Path().apply {
                        addCircle(x, 0f, notch, android.graphics.Path.Direction.CW)
                        addCircle(x, h, notch, android.graphics.Path.Direction.CW)
                    }
                    shape.op(hole, android.graphics.Path.Op.DIFFERENCE)
                    c.drawPath(shape, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1A1A.toInt() })
                    c.drawLine(x, notch + 3 * d, x, h - notch - 3 * d, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFF4A4A4A.toInt(); style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f * d
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
                    })
                }
            }
            .openAndHold(m, item, view)
            .semantics { contentDescription = item.title }
    ) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp)) {
            Box(Modifier.fillMaxWidth().height(114.dp).background(colorResource(R.color.rx_cinema_card))) {
                RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 480)
            }
            Text(item.title, Modifier.padding(top = 8.dp), color = Color.White, fontSize = 14.sp, lineHeight = 16.sp, fontFamily = RxFonts.display,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(item.uploader, item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " views" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                Modifier.padding(top = 3.dp), color = colorResource(R.color.rx_cinema_gold), fontSize = 10.sp, fontFamily = RxFonts.body, maxLines = 1)
        }
        Box(Modifier.align(Alignment.CenterEnd).width(64.dp).fillMaxHeight()) {
            Text("ADMIT ONE", Modifier.align(Alignment.Center).graphicsLayer { rotationZ = -90f }, color = rx.accentText, fontSize = 14.sp,
                fontFamily = RxFonts.display, letterSpacing = 0.3.em, maxLines = 1, softWrap = false)
            Text(videoBadge(item), Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), color = colorResource(R.color.rx_on_picture2),
                fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}
