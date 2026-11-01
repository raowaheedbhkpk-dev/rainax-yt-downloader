package rainax.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

// ---------- video page (B): big picture on the left, download panel and Up next on the right ----------

@Composable
fun VideoPage(v: Video, onBack: () -> Unit, onOpen: (Video) -> Unit, onDownloads: () -> Unit) {
    val p = LocalPal.current
    var tries by remember(v.url) { mutableIntStateOf(0) }
    var info by remember(v.url) { mutableStateOf<Result<StreamInfo>?>(null) }
    var started by remember(v.url) { mutableStateOf(false) }
    LaunchedEffect(v.url, tries) {
        info = null
        info = withContext(Dispatchers.IO) { runCatching { Yt.info(v.url) } }
    }
    val i = info?.getOrNull()
    val title = i?.name ?: v.title
    val thumb = i?.let { Yt.thumbOf(it) } ?: v.thumb
    val related = remember(i) { i?.let { Yt.related(it) }.orEmpty() }

    Row(Modifier.fillMaxSize()) {
        // left: picture, title, channel, description
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(28.dp)) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).click(onClick = onBack).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Ic.Back, 34.dp, onClick = onBack)
                Spacer(Modifier.width(10.dp))
                Text("Back", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().widthIn(max = 1100.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
                NetImage(thumb, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
                Box(Modifier.align(Alignment.Center).size(76.dp).clip(CircleShape).background(RxRed).click { browse(v.url) },
                    contentAlignment = Alignment.Center) {
                    Icon(Ic.Play, null, Modifier.size(36.dp), tint = Color.White)
                }
                Text("Plays in your browser", color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp))
                if (v.seconds != 0L || (i?.duration ?: 0L) > 0L) {
                    val secs = if (v.seconds != 0L) v.seconds else i?.duration ?: 0L
                    Text(Yt.duration(secs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            if (title.isNotBlank()) Text(title, color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp)
            Spacer(Modifier.height(12.dp))
            val r = info
            when {
                r == null && title.isBlank() -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { Spinner() }
                r != null && r.isFailure -> Message("Couldn't open this video", friendly(r.exceptionOrNull()!!), "Try again") { Yt.forget(v.url); tries++ }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NetImage(i?.let { Yt.avatarOf(it) } ?: v.avatar, Modifier.size(42.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(i?.uploaderName ?: v.channel, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            val subs = i?.uploaderSubscriberCount ?: -1L
                            if (subs > 0) Text("${Yt.count(subs)} subscribers", color = p.text2, fontSize = 12.5.sp)
                        }
                        val views = i?.viewCount ?: v.views
                        val meta = listOfNotNull(Yt.count(views).takeIf { it.isNotEmpty() }?.let { "$it views" }, i?.textualUploadDate ?: v.uploaded)
                            .joinToString(" · ")
                        Text(meta, color = p.text2, fontSize = 13.sp)
                    }
                    val desc = i?.description?.content?.let { plain(it) }.orEmpty()
                    if (desc.isNotBlank()) {
                        var more by remember(v.url) { mutableStateOf(false) }
                        Spacer(Modifier.height(16.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.surface).click { more = !more }.padding(16.dp)) {
                            Text(desc, color = p.text, fontSize = 13.5.sp, lineHeight = 20.sp,
                                maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            Text(if (more) "Show less" else "Show more", color = p.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        Box(Modifier.width(1.dp).fillMaxHeight().background(p.divider))

        // right: download panel + Up next
        Column(Modifier.width(390.dp).fillMaxHeight().background(p.surface).padding(20.dp)) {
            Text("Download", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            val r = info
            when {
                r == null -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Spinner() }
                r.isFailure -> Text("Formats couldn't be loaded.", color = p.text2, fontSize = 13.sp)
                else -> Picker(r.getOrThrow(), title, thumb, onStarted = { started = true }, modifier = Modifier.heightIn(max = 430.dp))
            }
            if (started) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.accentSoft).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Check, null, Modifier.size(18.dp), tint = p.accentText)
                    Spacer(Modifier.width(8.dp))
                    Text("Added to downloads", color = p.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("Open", color = p.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.click(onClick = onDownloads).padding(4.dp))
                }
            }
            if (related.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Up next", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(related) { r2 -> UpNext(r2) { onOpen(r2) } }
                }
            }
        }
    }
}

@Composable
private fun UpNext(v: Video, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).click(onClick = onClick).padding(4.dp)) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            NetImage(v.thumb, Modifier.fillMaxSize())
            if (v.seconds != 0L) {
                Text(Yt.duration(v.seconds), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (v.live) RxRed else Color.Black.copy(alpha = 0.75f)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(v.channel, color = p.text2, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val views = Yt.count(v.views)
            if (views.isNotEmpty()) Text("$views views", color = p.text2, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}
