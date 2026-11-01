package rainax.desktop

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

fun linkVideo(url: String) = Video(url.trim(), "", "", null, null, 0, -1, null, false)

// ---------- Home / Music (A): search, chips, video grid, downloads tray ----------

@Composable
fun FeedPage(feed: Feed, onOpen: (Video) -> Unit, onQuick: (Video) -> Unit, onDownloads: () -> Unit) {
    val p = LocalPal.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun load(q: String) {
        feed.query = q
        feed.loading = true
        feed.error = null
        feed.items.clear()
        feed.next = null
        val gen = ++feed.gen
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Yt.search(q) } }
            if (gen != feed.gen) return@launch
            r.onSuccess { feed.items.addAll(it.items); feed.next = it.next }
                .onFailure { feed.error = friendly(it) }
            feed.loading = false
        }
    }

    fun more() {
        val page = feed.next ?: return
        if (feed.loading) return
        feed.loading = true
        val gen = feed.gen
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Yt.more(feed.query, page) } }
            if (gen != feed.gen) return@launch
            r.onSuccess { res ->
                feed.items.addAll(res.items.filter { n -> feed.items.none { it.url == n.url } })
                feed.next = res.next
            }
            feed.loading = false
        }
    }

    fun submit() {
        val t = feed.typed.trim()
        if (t.isEmpty()) return
        if (Yt.isLink(t)) onOpen(linkVideo(t)) else { feed.chip = -1; load(t) }
    }

    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.loading) load(feed.query) }

    Column(Modifier.fillMaxSize()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(feed.title, color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.width(560.dp).height(46.dp).clip(RoundedCornerShape(23.dp)).background(p.surface)
                    .border(1.dp, p.divider, RoundedCornerShape(23.dp)).padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Ic.Search, null, Modifier.size(18.dp), tint = p.text2)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (feed.typed.isEmpty()) Text("Search YouTube or paste a link", color = p.text2, fontSize = 14.sp)
                    BasicTextField(
                        value = feed.typed, onValueChange = { feed.typed = it }, singleLine = true,
                        textStyle = TextStyle(color = p.text, fontSize = 14.sp, fontFamily = Jakarta),
                        cursorBrush = SolidColor(RxRed),
                        modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { submit(); true } else false
                        }
                    )
                }
                if (feed.typed.isNotEmpty()) {
                    RoundIcon(Ic.Close, 28.dp, bg = Color.Transparent, tint = p.text2) { feed.typed = "" }
                    Spacer(Modifier.width(4.dp))
                }
                Row(
                    Modifier.clip(RoundedCornerShape(17.dp)).background(p.soft).click {
                        val text = clipboard.getText()?.text?.trim().orEmpty()
                        if (text.isNotEmpty()) { feed.typed = text; if (Yt.isLink(text)) onOpen(linkVideo(text)) }
                    }.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Ic.Paste, null, Modifier.size(15.dp), tint = p.text)
                    Spacer(Modifier.width(6.dp))
                    Text("Paste link", color = p.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Chips(feed) { i -> feed.chip = i; feed.typed = ""; load(feed.chips[i].second) }
        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val err = feed.error
            when {
                feed.items.isEmpty() && feed.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                feed.items.isEmpty() && err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Message("Couldn't load videos", err, "Try again") { load(feed.query) }
                }
                feed.items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Message("Nothing found", "Try other words.")
                }
                else -> {
                    val grid = rememberLazyGridState()
                    val nearEnd by remember(grid) {
                        derivedStateOf {
                            val info = grid.layoutInfo
                            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
                        }
                    }
                    LaunchedEffect(nearEnd, feed.items.size) { if (nearEnd) more() }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(250.dp), state = grid,
                        contentPadding = PaddingValues(start = 24.dp, end = 28.dp, top = 6.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(feed.items) { v -> VideoCard(v, onOpen = { onOpen(v) }, onDownload = { onQuick(v) }) }
                        if (feed.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(24.dp) }
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(grid), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp))
                }
            }
        }
        Tray(onDownloads)
    }
}

@Composable
fun VideoCard(v: Video, onOpen: () -> Unit, onDownload: () -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).click(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
            NetImage(v.thumb, Modifier.fillMaxSize())
            if (v.seconds != 0L) {
                Text(Yt.duration(v.seconds), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (v.live) RxRed else Color.Black.copy(alpha = 0.75f)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Row(Modifier.padding(start = 2.dp, end = 2.dp, top = 10.dp, bottom = 4.dp)) {
            NetImage(v.avatar, Modifier.size(34.dp).clip(CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(v.channel, color = p.text2, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(Yt.count(v.views).takeIf { it.isNotEmpty() }?.let { "$it views" }, v.uploaded).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = p.text2, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            RoundIcon(Ic.Download, 34.dp, bg = RxRed, tint = Color.White, onClick = onDownload)
        }
    }
}

// ---------- quick quality picker (the red button on a card) ----------

@Composable
fun QuickSheet(v: Video, onClose: () -> Unit) {
    val p = LocalPal.current
    var tries by remember(v.url) { mutableIntStateOf(0) }
    var info by remember(v.url) { mutableStateOf<Result<StreamInfo>?>(null) }
    LaunchedEffect(v.url, tries) {
        info = null
        info = withContext(Dispatchers.IO) { runCatching { Yt.info(v.url) } }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).quiet(onClose), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(22.dp)).background(p.surface).quiet {}.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetImage(v.thumb, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.title.ifBlank { info?.getOrNull()?.name.orEmpty() }, color = p.text, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(v.channel, color = p.text2, fontSize = 12.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                RoundIcon(Ic.Close, 32.dp, onClick = onClose)
            }
            Spacer(Modifier.height(16.dp))
            val r = info
            when {
                r == null -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Spinner() }
                r.isFailure -> Message("Couldn't open this video", friendly(r.exceptionOrNull()!!), "Try again") { Yt.forget(v.url); tries++ }
                else -> {
                    val i = r.getOrThrow()
                    Picker(i, v.title.ifBlank { i.name }, v.thumb ?: Yt.thumbOf(i), onStarted = onClose)
                }
            }
        }
    }
}

/** Video / Audio tabs, quality cards and the red Download button (sheet and video page). */
@Composable
fun Picker(info: StreamInfo, title: String, thumb: String?, onStarted: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val choices = remember(info) { Yt.choices(info) }
    var audio by remember(info) { mutableStateOf(false) }
    val list = choices.filter { it.audioOnly == audio }
    var sel by remember(info, audio) { mutableStateOf(list.firstOrNull()) }
    Column(modifier) {
        Seg(listOf("Video", "Audio"), if (audio) 1 else 0, { audio = it == 1 }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Text(if (info.duration <= 0) "Live videos can't be downloaded." else "No ${if (audio) "audio" else "video"} formats for this video.",
                color = p.text2, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { c -> ChoiceRow(c, c == sel) { sel = c } }
        }
        Spacer(Modifier.height(14.dp))
        RedButton("Download ${sel?.label.orEmpty()}", Ic.Download, Modifier.fillMaxWidth(), enabled = sel != null) {
            sel?.let { Downloads.add(title, thumb, it); onStarted() }
        }
        if (!Ffmpeg.available()) {
            Spacer(Modifier.height(8.dp))
            Text("ffmpeg is missing, so only qualities that already have sound are shown.", color = p.text2, fontSize = 11.sp)
        }
    }
}

@Composable
fun ChoiceRow(c: Choice, on: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) p.accentSoft else p.soft)
            .outlined(on, RxRed, 12.dp).click(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(2.dp, if (on) RxRed else p.text2, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(RxRed))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.label, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(c.sub, color = p.text2, fontSize = 12.sp)
        }
        val h = c.label.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        when {
            h >= 2160 -> Tag("4K", red = true)
            h >= 1440 -> Tag("2K", red = true)
            h >= 720 -> Tag("HD")
        }
    }
}
