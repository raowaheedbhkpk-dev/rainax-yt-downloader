package com.rainax.ytdownloader

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/*
 * Menus drawn with Jetpack Compose (they replaced sheet_player_options.xml, item_option.xml,
 * sheet_save_playlist.xml and item_save_playlist.xml): the ⋮ / gear menus of the video page, the music
 * player and video cards, and "Save video to…".
 */

/** One row of a menu: icon (0 = none), text, a value on the right (e.g. "1080p") and a tick. */
class RxOption(
    val text: String,
    val icon: Int = 0,
    val value: String? = null,
    val checked: Boolean = false,
    val action: () -> Unit
)

/** A menu's title and rows. [fill] = the panel fills the height (the side panel over a full-screen video). */
@Composable
internal fun OptionList(title: String, opts: List<RxOption>, fill: Boolean = false, onPick: (RxOption) -> Unit) {
    val rx = LocalRx.current
    Column(
        (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).background(rx.surface)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        if (!fill) SheetHandle()
        if (title.isNotBlank()) {
            Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), color = rx.text, fontSize = 17.sp,
                fontFamily = RxFonts.body, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(
            (if (fill) Modifier.weight(1f) else Modifier).fillMaxWidth()
                .nestedScroll(rememberNestedScrollInteropConnection()).verticalScroll(rememberScrollState())
        ) {
            opts.forEach { o -> OptionRow(o) { onPick(o) } }
        }
    }
}

@Composable
private fun OptionRow(o: RxOption, onClick: () -> Unit) {
    val rx = LocalRx.current
    Row(
        Modifier.fillMaxWidth().height(50.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (o.icon != 0) {
            Image(painterResource(o.icon), null, Modifier.padding(end = 16.dp).size(22.dp), colorFilter = ColorFilter.tint(rx.text))
        }
        Text(o.text, Modifier.weight(1f), color = rx.text, fontSize = 15.sp, fontFamily = RxFonts.body, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        o.value?.let {
            Text(it, Modifier.padding(start = 12.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1)
        }
        if (o.checked) {
            Image(painterResource(R.drawable.ic_check), null, Modifier.padding(start = 12.dp).size(22.dp), colorFilter = ColorFilter.tint(rx.accent))
        }
    }
}

object RxOptions {
    /** A bottom sheet menu: tap a row and the sheet closes, then the row's action runs. */
    fun sheet(act: AppCompatActivity, title: String, opts: List<RxOption>, expanded: Boolean = false): BottomSheetDialog {
        val dialog = RxSheetDialog(act)
        val view = ComposeView(act)
        view.setContent { RxTheme { OptionList(title, opts) { o -> dialog.dismiss(); o.action() } } }
        dialog.setContentView(view)
        if (expanded) {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
        return dialog
    }
}

/** "Save video to…": the playlists with tick boxes. [list] null = still loading. */
class SavePlaylistUi {
    var list by mutableStateOf<List<YtAccount.PlaylistOption>?>(null)
    var message by mutableStateOf<String?>(null)
    /** Bumped when a tick changes (the options themselves are plain objects). */
    var tick by mutableIntStateOf(0)
}

@Composable
internal fun SavePlaylistSheet(ui: SavePlaylistUi, toggle: (YtAccount.PlaylistOption) -> Unit, newPlaylist: () -> Unit) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth().background(rx.surface).padding(bottom = 12.dp)) {
        Text("Save video to…", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
            color = rx.text, fontSize = 17.sp, fontFamily = RxFonts.body, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val list = ui.list
        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = rx.accent, trackColor = rx.surfaceAlt)
        ui.message?.let {
            Text(it, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), color = rx.text2, fontSize = 14.sp,
                fontFamily = RxFonts.body)
        }
        @Suppress("UNUSED_VARIABLE") val t = ui.tick
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false)
                .nestedScroll(rememberNestedScrollInteropConnection()).verticalScroll(rememberScrollState())
        ) {
            list.orEmpty().forEach { o -> PlaylistRow(o) { toggle(o) } }
        }
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(1.dp).background(rx.divider))
        Row(
            Modifier.padding(start = 8.dp).height(52.dp).clickable(role = Role.Button) { newPlaylist() }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text))
            Text("New playlist", Modifier.padding(start = 10.dp), color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlaylistRow(o: YtAccount.PlaylistOption, onClick: () -> Unit) {
    val rx = LocalRx.current
    val privacy = when {
        o.privacy.contains("PUBLIC", true) -> R.drawable.ic_lock_open
        o.privacy.contains("UNLISTED", true) -> R.drawable.ic_link
        else -> R.drawable.ic_lock
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(o.has, null, colors = CheckboxDefaults.colors(checkedColor = rx.accent, uncheckedColor = rx.text2, checkmarkColor = rx.onAccent))
        Text(o.title, Modifier.weight(1f).padding(start = 8.dp), color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Image(painterResource(privacy), null, Modifier.padding(end = 8.dp).size(20.dp),
            colorFilter = ColorFilter.tint(rx.text2))
    }
}
