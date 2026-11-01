package rainax.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Clickable with the hand pointer. */
fun Modifier.click(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.pointerHoverIcon(PointerIcon.Hand).clickable(enabled = enabled, onClick = onClick)

/** Clickable without any ripple (scrims, panels that only swallow clicks). */
@Composable
fun Modifier.quiet(onClick: () -> Unit): Modifier =
    this.clickable(remember { MutableInteractionSource() }, null, onClick = onClick)

@Composable
fun RedButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (enabled) RxRed else RxRed.copy(alpha = 0.4f))
            .click(enabled, onClick).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun SoftButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(p.soft).click(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(17.dp), tint = p.text)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = p.text, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, size: Dp = 36.dp, bg: Color = LocalPal.current.soft, tint: Color = LocalPal.current.text, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(bg).click(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.5f), tint = tint)
    }
}

/** Segmented control; the chosen part is red with white words. */
@Composable
fun Seg(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(p.soft).padding(3.dp)) {
        options.forEachIndexed { i, s ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) RxRed else Color.Transparent)
                    .click { onSelect(i) }.padding(vertical = 7.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(s, color = if (on) Color.White else p.text, fontSize = 13.sp, maxLines = 1,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val p = LocalPal.current
    Box(modifier.height(height).clip(CircleShape).background(p.soft)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(RxRed))
    }
}

@Composable
fun Spinner(size: Dp = 30.dp) {
    CircularProgressIndicator(Modifier.size(size), color = RxRed, strokeWidth = 3.dp)
}

@Composable
fun Message(title: String, sub: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    val p = LocalPal.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Text(title, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (sub != null) {
            Spacer(Modifier.height(6.dp))
            Text(sub, color = p.text2, fontSize = 13.sp)
        }
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            RedButton(action, Ic.Retry, onClick = onAction)
        }
    }
}

/** A titled white card of rows, like the phone app's grouped settings. */
@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Text(title.uppercase(), color = p.text2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface), content = content)
}

@Composable
fun SettingRow(title: String, sub: String?, trailing: @Composable () -> Unit = {}) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = p.text2, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}

@Composable
fun Line() {
    Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp).background(LocalPal.current.divider))
}

@Composable
fun Tag(text: String, red: Boolean = false) {
    val p = LocalPal.current
    Text(text, color = if (red) Color.White else p.text2, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (red) RxRed else p.soft).padding(horizontal = 6.dp, vertical = 2.dp))
}

fun Modifier.outlined(on: Boolean, color: Color, radius: Dp): Modifier =
    if (on) this.border(1.5.dp, color, RoundedCornerShape(radius)) else this

// ---------- small helpers ----------

fun friendly(e: Throwable): String = when (e) {
    is java.net.UnknownHostException, is java.net.ConnectException -> "No internet connection."
    is java.net.SocketTimeoutException -> "The connection is slow. Try again."
    is org.schabi.newpipe.extractor.exceptions.ReCaptchaException -> "YouTube asked for a check. Wait a little and try again."
    else -> (e.cause?.takeIf { it is java.io.IOException }?.let { "No internet connection." })
        ?: e.message?.take(140) ?: "Something went wrong."
}

fun plain(html: String): String = html
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
    .trim()

fun browse(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

fun chooseFolder(current: File): File? {
    val ch = javax.swing.JFileChooser(current).apply {
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose where downloads are saved"
    }
    return if (ch.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) ch.selectedFile else null
}

fun statusText(t: Task): String = when (t.state) {
    Status.Queued -> "Waiting"
    Status.Downloading -> "${(t.progress * 100).toInt()}%"
    Status.Joining -> if (t.choice.toMp3) "Making MP3…" else "Joining…"
    Status.Paused -> "Paused"
    Status.Failed -> "Failed"
    Status.Done -> "Done"
}

fun Task.active() = state == Status.Queued || state == Status.Downloading || state == Status.Joining
