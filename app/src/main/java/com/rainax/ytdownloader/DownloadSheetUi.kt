package com.rainax.ytdownloader

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/*
 * The download sheet, drawn with Jetpack Compose (it replaced sheet_download.xml and the card / row adapters).
 * The sheets that decide what it shows (MainSocial.showDownloadSheet, QuickSheet) fill a [DlSheetUi] and react
 * to [DlSheetActions]; the look is the same: picture and title, Video / Audio tabs, ticket cards with sizes and a
 * Recommended pick, More formats, and the bar with "Selected" and DOWNLOAD.
 */

/** One quality card of the download sheet, or the "More Formats" card. */
sealed class DlCard {
    data class Format(
        val choice: FormatChoice,
        val tag: String,
        val quality: String,
        val size: String,
        val recommended: Boolean,
        val selected: Boolean
    ) : DlCard()

    data class More(val sub: String) : DlCard()
}

/** Rows of "More formats" and "Subtitles/CC". */
sealed class FormatRow {
    data class Section(val text: String) : FormatRow()
    data class Choice(val choice: FormatChoice, val selected: Boolean, val detailed: Boolean) : FormatRow()
    data class Nav(val id: String, val title: String, val value: String, val iconRes: Int?) : FormatRow()
}

/** What the download sheet shows now. */
class DlSheetUi {
    var heading by mutableStateOf("Download")
    var sub by mutableStateOf("")
    var back by mutableStateOf(false)
    var showThumb by mutableStateOf(true)
    var thumbUrl by mutableStateOf<String?>(null)
    var duration by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var showTabs by mutableStateOf(false)
    var tabKind by mutableIntStateOf(KIND_VIDEO)
    /** false: the quality cards; true: the list of every format (or subtitles). */
    var listMode by mutableStateOf(false)
    var cards by mutableStateOf<List<DlCard>>(emptyList())
    var rows by mutableStateOf<List<FormatRow>>(emptyList())
    var selectedLabel by mutableStateOf("")
    var canDownload by mutableStateOf(false)
    /** Taller sheet for the long lists. */
    var tall by mutableStateOf(false)
    /** DOWNLOAD pressed: the chosen ticket is torn off. */
    var tearing by mutableStateOf(false)
    /** Bumped to let the cards rise in again (new tab, sizes arrived). */
    var riseKey by mutableIntStateOf(0)
}

class DlSheetActions(
    val close: () -> Unit,
    val back: () -> Unit,
    val tab: (Int) -> Unit,
    val pick: (FormatChoice) -> Unit,
    val more: () -> Unit,
    val nav: (String) -> Unit,
    val retry: () -> Unit,
    val download: () -> Unit
)

object DlSheet {
    /** A bottom sheet showing [ui]. The caller shows it; [view] is the sheet's view (for haptics). */
    fun create(act: AppCompatActivity, ui: DlSheetUi, a: DlSheetActions): Pair<BottomSheetDialog, ComposeView> {
        val dialog = RxSheetDialog(act)
        val view = ComposeView(act)
        view.setContent { RxTheme(glass = true) { DownloadSheet(ui, a) } }
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener {
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            dialog.findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        return dialog to view
    }

    /** DOWNLOAD pressed: the chosen ticket lifts, tilts and fades like a stub torn off, then [then] runs. */
    fun tear(ui: DlSheetUi, view: android.view.View, then: () -> Unit) {
        Haptics.confirm(view)
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || ui.listMode) { then(); return }
        ui.tearing = true
        view.postDelayed({ then() }, 260)
    }
}

@Composable
private fun DownloadSheet(ui: DlSheetUi, a: DlSheetActions) {
    val rx = LocalRx.current
    val text = colorResource(R.color.dl_text)
    val text2 = colorResource(R.color.dl_text2)
    val screenH = LocalConfiguration.current.screenHeightDp
    val sheetH = (screenH * if (ui.tall) 0.9f else 0.72f).dp

    Column(
        Modifier.fillMaxWidth().heightIn(min = sheetH).height(sheetH)
            .background(colorResource(R.color.dl_bg_top).copy(alpha = 0.94f))       // see-through, like the tab bar
            .nestedScroll(rememberNestedScrollInteropConnection())
    ) {
        // handle
        Box(Modifier.padding(top = 10.dp, bottom = 6.dp).align(Alignment.CenterHorizontally).size(36.dp, 5.dp)
            .background(colorResource(R.color.dl_handle), RoundedCornerShape(50)))

        // header: back, picture, title and a short line, close
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.back) {
                RoundIcon(R.drawable.ic_back, "Back", 11.dp, colorResource(R.color.dl_close_icon)) { a.back() }
                Spacer(Modifier.width(8.dp))
            }
            if (ui.showThumb) {
                Box(Modifier.padding(end = 14.dp).size(104.dp, 64.dp).clip(RoundedCornerShape(12.dp)).rxDrawableBg(R.drawable.bg_dl_thumb)) {
                    AndroidView(
                        factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
                        update = { v -> if (v.getTag(R.id.img_url) != ui.thumbUrl) Img.load(v, ui.thumbUrl, widthPx = 320) },
                        modifier = Modifier.fillMaxSize()
                    )
                    val d = ui.duration
                    if (d != null) {
                        Text(
                            d, Modifier.align(Alignment.BottomEnd).padding(6.dp).rxDrawableBg(R.drawable.bg_dl_duration).padding(horizontal = 6.dp, vertical = 2.dp),
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(ui.heading, color = text, fontSize = 17.sp, lineHeight = 22.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ui.sub, Modifier.padding(top = 2.dp), color = text2, fontSize = 13.sp, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            RoundIcon(R.drawable.ic_dl_close, "Close", 13.dp, null) { a.close() }
        }

        if (ui.loading) {
            LinearProgressIndicator(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().height(4.dp),
                color = rx.accent, trackColor = colorResource(R.color.dl_tabs_bg)
            )
        }
        val err = ui.error
        if (err != null) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(err, Modifier.padding(top = 14.dp), color = colorResource(R.color.rx_error), fontSize = 14.sp, fontFamily = RxFonts.body)
                OutlinedButton(
                    onClick = { a.retry() }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(12.dp)
                ) { Text("Retry", color = rx.accentText, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold) }
            }
        }

        // Video / Audio tabs
        if (ui.showTabs) {
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp).fillMaxWidth().height(44.dp)
                    .background(colorResource(R.color.dl_tabs_bg), RoundedCornerShape(12.dp)).padding(3.dp)
            ) {
                SheetTab(Modifier.weight(1f).padding(end = 2.dp), R.drawable.ic_dl_video, "Video", ui.tabKind == KIND_VIDEO) { a.tab(KIND_VIDEO) }
                SheetTab(Modifier.weight(1f).padding(start = 2.dp), R.drawable.ic_dl_audio, "Audio", ui.tabKind == KIND_AUDIO) { a.tab(KIND_AUDIO) }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!ui.listMode) {
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)
                ) {
                    itemsIndexed(ui.cards) { i, c ->
                        Rise(ui.riseKey, i) {
                            when (c) {
                                is DlCard.Format -> TicketCard(c, ui.tearing && c.selected) { a.pick(c.choice) }
                                is DlCard.More -> MoreCard(c) { a.more() }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                    items(ui.rows) { r -> FormatRowView(r, a) }
                }
            }
        }

        // the bar: what is selected, and DOWNLOAD
        Box(Modifier.fillMaxWidth().height(1.dp).background(colorResource(R.color.dl_bar_line)))
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Selected", color = text2, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = RxFonts.body)
                Text(ui.selectedLabel, Modifier.padding(top = 2.dp), color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = { if (ui.canDownload && !ui.tearing) a.download() },
                modifier = Modifier.height(52.dp).alpha(if (ui.canDownload) 1f else 0.5f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RxRed, contentColor = Color.White)
            ) {
                Image(painterResource(R.drawable.ic_dl_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
                Text("Download", Modifier.padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em, fontFamily = RxFonts.body)
            }
        }
    }
}

/** The cards rise in one after another when they first show (and after a tab change). */
@Composable
private fun Rise(key: Int, index: Int, content: @Composable () -> Unit) {
    val anim = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        kotlinx.coroutines.delay(index * 40L)
        anim.animateTo(1f, tween(260))
    }
    val shift = with(LocalDensity.current) { 24.dp.toPx() }
    Box(Modifier.graphicsLayer { alpha = anim.value; translationY = (1f - anim.value) * shift }) { content() }
}

@Composable
private fun RoundIcon(icon: Int, label: String, pad: androidx.compose.ui.unit.Dp, tint: Color?, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).rxDrawableBg(R.drawable.bg_dl_close).clickable(onClick = onClick).semantics { contentDescription = label }.padding(pad)
    ) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = tint?.let { ColorFilter.tint(it) })
    }
}

@Composable
private fun SheetTab(modifier: Modifier, icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    val rx = LocalRx.current
    val color = if (on) colorResource(R.color.dl_text) else colorResource(R.color.dl_text2)
    Row(
        modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(if (on) colorResource(R.color.dl_card_bg) else Color.Transparent).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(icon), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(color))
        Text(label, Modifier.padding(start = 8.dp), color = color, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, fontFamily = RxFonts.body)
    }
}

/** A quality card (v19.5): a white rounded card, a black ring and a round check when chosen. */
@Composable
private fun TicketCard(c: DlCard.Format, tearing: Boolean, onClick: () -> Unit) {
    val rx = LocalRx.current
    val cardBg = colorResource(R.color.dl_card_bg)
    val text = colorResource(R.color.dl_text)
    val text2 = colorResource(R.color.dl_text2)
    val shape = RoundedCornerShape(18.dp)
    val t by animateFloatAsState(if (tearing) 1f else 0f, tween(240), label = "tear")
    val lift = with(LocalDensity.current) { 26.dp.toPx() }
    Box(
        Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp).fillMaxWidth()
            .graphicsLayer { scaleX = 1f - 0.06f * t; scaleY = 1f - 0.06f * t; translationY = -lift * t; alpha = 1f - t }
    ) {
        Column(
            Modifier.fillMaxWidth().height(128.dp).clip(shape).background(cardBg)
                .border(if (c.selected) 2.dp else 0.dp, if (c.selected) rx.accent else Color.Transparent, shape)
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = listOf(c.quality, c.tag, c.size, if (c.recommended) "Recommended" else "", if (c.selected) "Selected" else "")
                        .filter { it.isNotBlank() }.joinToString(", ")
                }
                .padding(16.dp)
        ) {
            if (c.recommended) {
                Text(
                    "Recommended", Modifier.background(rx.accent, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
                    color = rx.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body
                )
            } else Text(c.tag, color = text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
            Spacer(Modifier.weight(1f))
            Text(c.quality, color = text, fontSize = 30.sp, lineHeight = 32.sp, fontFamily = RxFonts.display)
            Text(if (c.recommended) "${c.tag} · ${c.size}" else c.size, Modifier.padding(top = 4.dp), color = text2, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp).size(24.dp)
                .background(if (c.selected) rx.accent else Color.Transparent, CircleShape)
                .border(if (c.selected) 0.dp else 1.5.dp, if (c.selected) Color.Transparent else colorResource(R.color.dl_more_stroke), CircleShape)
                .padding(5.dp)
        ) {
            if (c.selected) Image(painterResource(R.drawable.ic_dl_check), null, Modifier.fillMaxSize(),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(rx.onAccent))
        }
    }
}

@Composable
private fun MoreCard(c: DlCard.More, onClick: () -> Unit) {
    val rx = LocalRx.current
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 12.dp).fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().height(128.dp).clip(shape).background(colorResource(R.color.dl_card_bg))
                .clickable(onClick = onClick).padding(16.dp)
        ) {
            Box(Modifier.size(36.dp).background(colorResource(R.color.dl_more_icon_bg), CircleShape).padding(9.dp)) {
                Image(painterResource(R.drawable.ic_dl_grid), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("More formats", color = colorResource(R.color.dl_text), fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                Image(painterResource(R.drawable.ic_dl_chevron), null, Modifier.padding(start = 4.dp).size(14.dp), colorFilter = ColorFilter.tint(rx.text))
            }
            Text(c.sub, Modifier.padding(top = 2.dp), color = colorResource(R.color.dl_text2), fontSize = 13.sp, fontFamily = RxFonts.body)
        }
    }
}

@Composable
private fun FormatRowView(r: FormatRow, a: DlSheetActions) {
    val rx = LocalRx.current
    when (r) {
        is FormatRow.Section -> Text(
            r.text, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body
        )
        is FormatRow.Choice -> {
            val c = r.choice
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable { a.pick(c) }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when (c.kind) { KIND_AUDIO -> R.drawable.ic_audio; KIND_VIDEO -> R.drawable.ic_play_box; else -> null }
                if (icon != null) Image(painterResource(icon), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(rx.text))
                Column(Modifier.weight(1f).padding(start = if (icon != null) 18.dp else 0.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title, color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body)
                        val badge = c.badge
                        if (r.detailed && badge != null) {
                            Text(badge, Modifier.padding(start = 8.dp).rxDrawableBg(R.drawable.bg_chip_small).padding(horizontal = 8.dp, vertical = 2.dp),
                                color = rx.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                        }
                    }
                    if (r.detailed && c.desc.isNotBlank()) {
                        Text(c.desc, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                    }
                }
                Text(c.size, Modifier.padding(start = 8.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                RadioButton(
                    selected = r.selected, onClick = { a.pick(c) },
                    colors = RadioButtonDefaults.colors(selectedColor = rx.accent, unselectedColor = rx.text2)
                )
            }
        }
        is FormatRow.Nav -> Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { a.nav(r.id) }.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (r.iconRes != null) {
                Image(painterResource(r.iconRes), null, Modifier.padding(end = 18.dp).size(26.dp), colorFilter = ColorFilter.tint(rx.text))
            }
            Text(r.title, Modifier.weight(1f), color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body)
            Text(r.value, color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
            Image(painterResource(R.drawable.ic_chevron), null, Modifier.padding(start = 6.dp).size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
        }
    }
}
