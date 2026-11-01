package rainax.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.schabi.newpipe.extractor.Page

enum class Screen { Home, Music, Downloads, Settings }

/** A list of videos for some words (Home and Music each keep one, so switching back keeps your place). */
class Feed(val title: String, val chips: List<Pair<String, String>>) {
    var query by mutableStateOf(chips.first().second)
    var chip by mutableIntStateOf(0)
    var typed by mutableStateOf("")
    val items = mutableStateListOf<Video>()
    var next: Page? = null
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var gen = 0
}

private val homeChips = listOf(
    "All" to "new videos", "Music" to "new songs", "Gaming" to "gaming", "News" to "news today",
    "Cricket" to "cricket highlights", "Podcasts" to "podcast", "Tech" to "tech review", "Comedy" to "comedy",
    "Cooking" to "recipes", "Live" to "live now"
)

private val musicChips = listOf(
    "Top hits" to "top hits songs", "Pakistani" to "pakistani songs", "Punjabi" to "punjabi songs",
    "Bollywood" to "bollywood songs", "Naat" to "naat", "Qawwali" to "qawwali", "Lo-fi" to "lofi songs",
    "Pop" to "pop music", "Rap" to "rap songs"
)

fun main() {
    runCatching { javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName()) }
    Thread { runCatching { Yt.init() } }.start()
    application {
        val state = rememberWindowState(width = 1280.dp, height = 820.dp, position = WindowPosition(Alignment.Center))
        Window(onCloseRequest = ::exitApplication, state = state, title = "RAINAX Tube", icon = painterResource("icon.png")) {
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(1000, 660) }
            App()
        }
    }
}

@Composable
fun App() {
    var theme by remember { mutableIntStateOf(Prefs.theme) }
    var screen by remember { mutableStateOf(Screen.Home) }
    var open by remember { mutableStateOf<Video?>(null) }
    var quick by remember { mutableStateOf<Video?>(null) }
    val home = remember { Feed("Home", homeChips) }
    val music = remember { Feed("Music", musicChips) }
    RxDesktopTheme(theme) {
        val p = LocalPal.current
        Box(Modifier.fillMaxSize().background(p.bg)) {
            Row(Modifier.fillMaxSize()) {
                SideBar(screen) { screen = it; open = null }
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.divider))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val v = open
                    val toDownloads = { screen = Screen.Downloads; open = null }
                    if (v != null) {
                        VideoPage(v, onBack = { open = null }, onOpen = { open = it }, onDownloads = toDownloads)
                    } else when (screen) {
                        Screen.Home -> FeedPage(home, onOpen = { open = it }, onQuick = { quick = it }, onDownloads = toDownloads)
                        Screen.Music -> FeedPage(music, onOpen = { open = it }, onQuick = { quick = it }, onDownloads = toDownloads)
                        Screen.Downloads -> DownloadsPage()
                        Screen.Settings -> SettingsPage(theme) { theme = it; Prefs.theme = it }
                    }
                }
            }
            quick?.let { QuickSheet(it, onClose = { quick = null }) }
        }
    }
}

// ---------- side bar (A) ----------

@Composable
fun SideBar(screen: Screen, onSelect: (Screen) -> Unit) {
    val p = LocalPal.current
    val active = Downloads.tasks.count { it.active() }
    Column(Modifier.width(228.dp).fillMaxHeight().background(p.surface).padding(horizontal = 14.dp, vertical = 18.dp)) {
        Row(Modifier.padding(start = 8.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(RxRed), contentAlignment = Alignment.Center) {
                Icon(Ic.Download, null, Modifier.size(20.dp), tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            Text("RAINAX", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(5.dp))
            Text("Tube", color = p.accentText, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        NavItem(Ic.Home, "Home", screen == Screen.Home) { onSelect(Screen.Home) }
        NavItem(Ic.Music, "Music", screen == Screen.Music) { onSelect(Screen.Music) }
        NavItem(Ic.Download, "Downloads", screen == Screen.Downloads, badge = active) { onSelect(Screen.Downloads) }
        Spacer(Modifier.weight(1f))
        NavItem(Ic.Settings, "Settings", screen == Screen.Settings) { onSelect(Screen.Settings) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, on: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (on) RxRed else Color.Transparent).click(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (on) Color.White else p.text)
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (on) Color.White else p.text, fontSize = 14.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
        if (badge > 0) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) Color.White else RxRed), contentAlignment = Alignment.Center) {
                Text("$badge", color = if (on) RxRed else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- chips ----------

@Composable
fun Chips(feed: Feed, onPick: (Int) -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        feed.chips.forEachIndexed { i, (name, _) ->
            val on = i == feed.chip
            Text(name, color = if (on) Color.White else p.text, fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) RxRed else p.surface)
                    .border(1.dp, if (on) RxRed else p.divider, RoundedCornerShape(18.dp))
                    .click { onPick(i) }.padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

// ---------- downloads tray (bottom of Home) ----------

@Composable
fun Tray(onDownloads: () -> Unit) {
    val p = LocalPal.current
    val active = Downloads.tasks.filter { it.active() }
    if (active.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(RxRed), contentAlignment = Alignment.Center) {
            Icon(Ic.Download, null, Modifier.size(16.dp), tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Text("${active.size} downloading", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        active.take(2).forEach { t ->
            Spacer(Modifier.width(24.dp))
            Column(Modifier.width(240.dp)) {
                Text(t.title, color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar(t.progress.toFloat(), Modifier.weight(1f), 5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(statusText(t), color = p.text2, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("See all", color = p.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).click(onClick = onDownloads).padding(8.dp))
    }
}
