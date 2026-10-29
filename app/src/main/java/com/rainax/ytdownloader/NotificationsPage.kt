package com.rainax.ytdownloader

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The bell: YouTube's real notifications, laid out like the YouTube app — unread dot, channel picture,
 * "For you" / video title / "2 weeks ago", the video's picture and a ••• menu. All / Mentions on top.
 * It never shows feed videos in place of notifications.
 */
class NotificationsPage(
    private val act: AppCompatActivity,
    private val open: (VideoItem) -> Unit,
    private val download: (VideoItem) -> Unit
) {
    private val dialog = androidx.activity.ComponentDialog(act, R.style.Theme_Rainax_Page)
    private val view = androidx.compose.ui.platform.ComposeView(act)
    private val all = mutableListOf<YtAccount.Notif>()
    private var next: String? = null
    private var job: Job? = null

    // what the page shows
    private var shown by mutableStateOf<List<YtAccount.Notif>>(emptyList())
    private var mentionsOnly by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var message by mutableStateOf<Triple<String, String, Boolean>?>(null)
    /** Bumped when one was marked read (the dots redraw). */
    private var readTick by mutableIntStateOf(0)
    private val snack = androidx.compose.material3.SnackbarHostState()

    fun show() {
        view.setContent { RxTheme { Screen() } }
        dialog.setContentView(view)
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.setOnDismissListener { job?.cancel() }
        dialog.show()
        load(reset = true)
    }

    private fun load(reset: Boolean) {
        if (!reset && next == null) return
        job?.cancel()
        if (reset) {
            next = null
            if (all.isEmpty()) loading = true
            message = null
        }
        val token = if (reset) null else next
        job = act.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { YtAccount.inbox(token) } }
            loading = false
            refreshing = false
            r.onSuccess { page ->
                if (reset) all.clear()
                val have = all.map { it.id }.toHashSet()
                all += page.items.filter { have.add(it.id) }
                next = page.next?.takeIf { page.items.isNotEmpty() }
                render()
            }.onFailure { e ->
                if (all.isEmpty()) message = Triple("Couldn't load notifications", friendlyError(e.message ?: "Check your internet and try again"), true)
                else say("Couldn't load more notifications")
            }
        }
    }

    private fun render() {
        val list = if (mentionsOnly) all.filter { it.mention } else all.toList()
        shown = list
        message = when {
            loading || list.isNotEmpty() -> null
            mentionsOnly -> Triple("No mentions", "Replies to your comments and @mentions show here.", false)
            else -> Triple("No notifications yet", "New videos from your channels, replies and likes show here.", false)
        }
    }

    /** A short message on this page (the app's message bar would sit behind it). */
    private fun say(text: String) {
        act.lifecycleScope.launch { snack.showSnackbar(text) }
    }

    private fun itemOf(n: YtAccount.Notif): VideoItem? {
        val url = n.url ?: return null
        val isChannel = n.videoId == null
        return VideoItem(
            url = url, title = if (isChannel) n.heading else n.body.ifBlank { n.heading }, uploader = if (isChannel) "" else n.heading.takeIf { it != "For you" }.orEmpty(),
            thumb = if (isChannel) n.avatar else n.thumb, seconds = 0, views = -1, uploaded = null,
            avatar = n.avatar, isChannel = isChannel
        )
    }

    private fun tap(n: YtAccount.Notif) {
        markRead(act, n.id)
        readTick++
        val item = itemOf(n)
        if (item == null) { say("This notification can't be opened in RAINAX"); return }
        dialog.dismiss()
        open(item)
    }

    /** The ••• menu of one notification: label and what it does. */
    private fun menu(n: YtAccount.Notif): List<Pair<String, () -> Unit>> {
        val item = itemOf(n)
        val out = mutableListOf<Pair<String, () -> Unit>>()
        if (item != null && !item.isChannel) out += "Download" to { dialog.dismiss(); download(item) }
        if (item != null) out += "Share" to {
            runCatching {
                act.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.url), "Share"))
            }
            Unit
        }
        if (!isRead(act, n)) out += "Mark as read" to { markRead(act, n.id); readTick++; Unit }
        return out
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val rx = LocalRx.current
        Box(Modifier.fillMaxSize().background(rx.bg).systemBarsPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dialog.dismiss() }) {
                        Image(painterResource(R.drawable.ic_back), "Back", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
                    }
                    Text("Notifications", Modifier.weight(1f).padding(start = 8.dp), color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.display)
                    IconButton(onClick = { load(reset = true) }) {
                        Image(painterResource(R.drawable.ic_refresh), "Refresh", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
                    }
                }
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RxChip("All", selected = !mentionsOnly) { mentionsOnly = false; render() }
                    RxChip("Mentions", selected = mentionsOnly) { mentionsOnly = true; render() }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val list = rememberLazyListState()
                    val nearEnd by remember {
                        derivedStateOf {
                            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            last >= list.layoutInfo.totalItemsCount - 4
                        }
                    }
                    LaunchedEffect(nearEnd, shown.size) { if (nearEnd && shown.isNotEmpty() && next != null && job?.isActive != true) load(reset = false) }
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = { refreshing = true; load(reset = true) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        @Suppress("UNUSED_VARIABLE") val tick = readTick
                        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp)) {
                            items(shown, key = { it.id }) { n -> NotifRow(n) }
                        }
                    }
                    if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = rx.accent)
                    val m = message
                    if (m != null) {
                        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(96.dp).rxDrawableBg(R.drawable.bg_welcome_circle), contentAlignment = Alignment.Center) {
                                Image(painterResource(R.drawable.ic_bell), null, Modifier.size(44.dp), colorFilter = ColorFilter.tint(rx.accent))
                            }
                            Text(m.first, Modifier.padding(top = 18.dp), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.display, textAlign = TextAlign.Center)
                            Text(m.second, Modifier.padding(top = 6.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center)
                            if (m.third) {
                                Button(
                                    onClick = { load(reset = true) }, modifier = Modifier.padding(top = 16.dp), shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = rx.accentContainer, contentColor = rx.text)
                                ) { Text("Try again", fontFamily = RxFonts.body, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }

    @Composable
    private fun NotifRow(n: YtAccount.Notif) {
        val rx = LocalRx.current
        Row(
            Modifier.fillMaxWidth().clickable { tap(n) }.padding(start = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(Modifier.padding(top = 22.dp).size(6.dp).alpha(if (isRead(act, n)) 0f else 1f).rxDrawableBg(R.drawable.bg_notif_dot))
            Box(Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                RxNetImage(n.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 160)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                Text(n.heading, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.body.isNotBlank()) Text(n.body, Modifier.padding(top = 2.dp), color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.time.isNotBlank()) Text(n.time, Modifier.padding(top = 3.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
            }
            if (n.thumb != null) {
                Box(Modifier.size(124.dp, 70.dp).rxDrawableBg(R.drawable.bg_thumb)) {
                    RxNetImage(n.thumb, Modifier.fillMaxSize(), widthPx = 360)
                }
            }
            var open by remember { mutableStateOf(false) }
            Box {
                Box(Modifier.size(36.dp, 40.dp).clickable { open = true }, contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_more), "More", Modifier.size(22.dp), colorFilter = ColorFilter.tint(rx.text))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu(n).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label, fontFamily = RxFonts.body) }, onClick = { open = false; action() })
                    }
                }
            }
        }
    }

    companion object {
        private const val PREFS = "notif_read"
        private const val MAX_KEEP = 400

        /** Read on YouTube, or tapped here (kept on the phone). */
        fun isRead(c: Context, n: YtAccount.Notif): Boolean = n.read || readIds(c).contains(n.id)

        private fun readIds(c: Context): Set<String> =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("ids", emptySet()).orEmpty()

        private fun markRead(c: Context, id: String) {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ids = LinkedHashSet(p.getStringSet("ids", emptySet()).orEmpty())
            ids += id
            while (ids.size > MAX_KEEP) ids.remove(ids.first())
            p.edit().putStringSet("ids", ids).apply()
        }
    }
}
