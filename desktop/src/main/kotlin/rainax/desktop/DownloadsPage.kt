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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- downloads (C): summary tiles, table, inspector ----------

@Composable
fun DownloadsPage() {
    val p = LocalPal.current
    var selId by remember { mutableStateOf<Long?>(null) }
    val tasks = Downloads.tasks
    val sel = tasks.firstOrNull { it.id == selId } ?: tasks.firstOrNull()
    val active = tasks.count { it.active() }
    val finished = tasks.count { it.state == Status.Done }
    val speed = tasks.sumOf { it.speed }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            SoftButton("Open folder", Ic.Folder) { Downloads.openFolder() }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile("Downloading", "$active", Modifier.weight(1f), red = active > 0)
            Tile("Finished", "$finished", Modifier.weight(1f))
            Tile("Speed", if (speed > 0) "${Yt.mb(speed)}/s" else "—", Modifier.weight(1f))
            Tile("Saved in", Prefs.folder.name, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(p.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Head("Name", Modifier.weight(1f).padding(start = 76.dp))
                    Head("Quality", Modifier.width(90.dp))
                    Head("Size", Modifier.width(90.dp))
                    Head("Progress", Modifier.width(150.dp))
                    Head("Status", Modifier.width(96.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
                if (tasks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Message("No downloads yet", "Press the red download button on any video.")
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(tasks, key = { it.id }) { t -> TaskRow(t, t == sel) { selId = t.id } }
                    }
                }
            }
            if (sel != null) {
                Spacer(Modifier.width(18.dp))
                Inspector(sel, Modifier.width(320.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Head(text: String, modifier: Modifier) {
    Text(text.uppercase(), color = LocalPal.current.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier, red: Boolean = false) {
    val p = LocalPal.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(label, color = p.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (red) p.accentText else p.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TaskRow(t: Task, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().background(if (selected) p.accentSoft else p.surface).click(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NetImage(t.thumb, Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
        Spacer(Modifier.width(12.dp))
        Text(t.title, color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(t.choice.label, color = p.text, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(Yt.mb(t.total).replace("size unknown", "—"), color = p.text2, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Box(Modifier.width(150.dp).padding(end = 18.dp)) {
            Bar(t.progress.toFloat(), Modifier.fillMaxWidth(), 6.dp)
        }
        val color = when (t.state) {
            Status.Failed, Status.Downloading, Status.Joining -> p.accentText
            else -> p.text2
        }
        Text(statusText(t), color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
    }
}

@Composable
private fun Inspector(t: Task, modifier: Modifier) {
    val p = LocalPal.current
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).verticalScroll(rememberScrollState()).padding(18.dp)) {
        NetImage(t.thumb, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(14.dp))
        Text(t.title, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Bar(t.progress.toFloat(), Modifier.fillMaxWidth(), 8.dp)
        Spacer(Modifier.height(8.dp))
        val line = when (t.state) {
            Status.Downloading -> buildString {
                append("${Yt.mb(t.done)} of ${Yt.mb(t.total)}")
                if (t.speed > 0) append(" · ${Yt.mb(t.speed)}/s")
                if (t.etaSec >= 0) append(" · ${Yt.duration(t.etaSec)} left")
            }
            else -> statusText(t)
        }
        Text(line, color = p.text2, fontSize = 12.5.sp)
        t.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = p.accentText, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(16.dp))
        Info("Quality", t.choice.label)
        Info("Size", Yt.mb(t.total).replace("size unknown", "—"))
        Info("File", t.file.name)
        Info("Folder", t.file.parent ?: "")
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (t.state) {
                Status.Done -> {
                    RedButton("Play", Ic.Play, Modifier.fillMaxWidth()) { Downloads.play(t) }
                    SoftButton("Show in folder", Ic.Folder, Modifier.fillMaxWidth()) { Downloads.openFolder(t) }
                }
                Status.Queued, Status.Downloading -> SoftButton("Pause", Ic.Pause, Modifier.fillMaxWidth()) { Downloads.pause(t) }
                Status.Paused -> RedButton("Resume", Ic.Play, Modifier.fillMaxWidth()) { Downloads.resume(t) }
                Status.Failed -> RedButton("Try again", Ic.Retry, Modifier.fillMaxWidth()) { Downloads.resume(t) }
                Status.Joining -> {}
            }
            if (t.state != Status.Joining) SoftButton("Remove from list", Ic.Close, Modifier.fillMaxWidth()) { Downloads.remove(t) }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = p.text2, fontSize = 12.5.sp, modifier = Modifier.width(70.dp))
        Text(value, color = p.text, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
