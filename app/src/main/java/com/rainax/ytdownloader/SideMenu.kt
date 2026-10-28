package com.rainax.ytdownloader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * The side menu (≡ on Home) drawn with Jetpack Compose (it replaced layout_explore.xml and the
 * SwipeCloseLayout view): a floating card with the bell and ✕, your account, search, then MENU / DISCOVER /
 * GENERAL with a red bar on the open place and count badges. Closes with ✕, Back, a tap outside, or a swipe left.
 */

class MenuEntry(val icon: Int, val label: String, val section: String, val action: () -> Unit)

class SideMenuUi {
    var open by mutableStateOf(false)
    var name by mutableStateOf("Not signed in")
    var hint by mutableStateOf("Tap to sign in to YouTube")
    var avatar by mutableStateOf<String?>(null)
    var active by mutableStateOf("Home")
    var signedIn by mutableStateOf(false)
    var downloads by mutableIntStateOf(0)
    var version by mutableStateOf("")
    var entries: List<MenuEntry> = emptyList()
    /** Bumped each time the menu opens (the places rise in again). */
    internal var opened by mutableIntStateOf(0)
}

class SideMenuActions(val close: () -> Unit, val bell: () -> Unit, val profile: () -> Unit, val search: () -> Unit, val pick: (MenuEntry) -> Unit)

@Composable
internal fun SideMenu(ui: SideMenuUi, a: SideMenuActions) {
    val rx = LocalRx.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 292dp, or less on small phones (always leaves room to tap outside)
        val panelW = minOf(292.dp, maxWidth * 0.84f)
        val panelPx = with(density) { (panelW + 40.dp).toPx() }
        val shift = remember { Animatable(-panelPx) }
        LaunchedEffect(ui.open) {
            if (ui.open) shift.animateTo(0f, tween(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
            else shift.animateTo(-panelPx, tween(200))
        }
        val shown = (1f + shift.value / panelPx).coerceIn(0f, 1f)
        if (shown <= 0f && !ui.open) return@BoxWithConstraints
        // the dark layer behind (a tap closes)
        Box(
            Modifier.fillMaxSize().alpha(shown).background(Color(0x80000000))
                .clickable(remember { MutableInteractionSource() }, null) { a.close() }
        )
        // the card: drag it to the left to close (it follows your finger and springs back if let go early)
        val drag = rememberDraggableState { d -> scope.launch { shift.snapTo((shift.value + d).coerceIn(-panelPx, 0f)) } }
        Column(
            Modifier.systemBarsPadding().padding(start = 10.dp, top = 10.dp, bottom = 12.dp).width(panelW).fillMaxHeight()
                .offset { IntOffset(shift.value.roundToInt(), 0) }
                .graphicsLayer { alpha = 0.6f + 0.4f * shown }
                .background(rx.bg, RoundedCornerShape(12.dp))
                .border(1.dp, colorResource(R.color.menu_stroke), RoundedCornerShape(12.dp))
                .draggable(drag, Orientation.Horizontal, onDragStopped = { v ->
                    if (shift.value < -panelPx * 0.4f || v < -1500f) a.close()
                    else scope.launch { shift.animateTo(0f, tween(180)) }
                })
        ) {
            // header: logo, name, bell, close
            Row(Modifier.fillMaxWidth().height(62.dp).padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).rxDrawableBg(R.drawable.ic_logo))
                Text(
                    buildAnnotatedString {
                        append("RAINAX ")
                        withStyle(SpanStyle(color = rx.text2)) { append("Tube") }
                    },
                    Modifier.weight(1f).padding(start = 10.dp), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.display
                )
                Box(Modifier.size(36.dp).clip(CircleShape).clickable { a.bell() }.semantics { contentDescription = "Notifications" }.padding(8.dp)) {
                    Image(painterResource(R.drawable.ic_bell), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
                }
                Box(Modifier.padding(start = 4.dp).size(38.dp).rxDrawableBg(R.drawable.bg_menu_icon).clickable { a.close() }
                    .semantics { contentDescription = "Close menu" }.padding(10.dp)) {
                    Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
                }
            }
            // your account
            Row(Modifier.fillMaxWidth().height(60.dp).clickable { a.profile() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_menu_icon), contentAlignment = Alignment.Center) {
                    if (ui.avatar != null) RxNetImage(ui.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 120)
                    else Image(painterResource(R.drawable.ic_person), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(ui.name, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(ui.hint, color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1)
                }
                Image(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.text2))
            }
            // search
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp).fillMaxWidth().height(40.dp).rxDrawableBg(R.drawable.bg_menu_search)
                    .clickable { a.search() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_search), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.text2))
                Text("Search…", Modifier.padding(start = 10.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
            }
            // the places
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                var chapter = 0
                var last = ""
                var row = 0
                ui.entries.forEach { e ->
                    if (!ui.signedIn && e.label in SIGNED_IN_ONLY) return@forEach
                    if (e.section != last) {
                        last = e.section
                        chapter++
                        Text(e.section.lowercase().replaceFirstChar { it.uppercase() },      // Menu, Discover, General
                            Modifier.padding(start = 24.dp, top = 16.dp, end = 12.dp, bottom = 6.dp),
                            color = rx.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
                    }
                    MenuRow(e, ui, row++, a)
                }
            }
            Text(ui.version, Modifier.fillMaxWidth().padding(16.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
        }
    }
}

private val SIGNED_IN_ONLY = setOf("Sign out", "Watch later", "Liked videos")

@Composable
private fun MenuRow(e: MenuEntry, ui: SideMenuUi, index: Int, a: SideMenuActions) {
    val rx = LocalRx.current
    val on = ui.active == e.label
    // the places rise in one after another when the menu opens
    val rise = remember { Animatable(1f) }
    LaunchedEffect(ui.opened) {
        if (ui.opened > 0) {
            rise.snapTo(0f)
            kotlinx.coroutines.delay(60L + index * 18L)
            rise.animateTo(1f, tween(220))
        }
    }
    Row(
        Modifier.fillMaxWidth().height(46.dp)
            .graphicsLayer { alpha = rise.value; translationX = (1f - rise.value) * -24.dp.toPx() }
            .padding(horizontal = 10.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) rx.accent else Color.Transparent)
            .clickable { a.pick(e) }.semantics { contentDescription = e.label }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(32.dp).then(if (on) Modifier else Modifier.rxDrawableBg(R.drawable.bg_menu_icon)),
            contentAlignment = Alignment.Center
        ) {
            if (e.icon != 0) Image(painterResource(e.icon), null, Modifier.size(17.dp), colorFilter = ColorFilter.tint(if (on) rx.onAccent else rx.text))
        }
        Text(e.label, Modifier.weight(1f).padding(start = 14.dp), color = if (on) rx.onAccent else rx.text, fontSize = 14.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontFamily = RxFonts.body, maxLines = 1)
        if (e.label == "Downloads" && ui.downloads > 0) {
            Box(Modifier.size(20.dp).rxDrawableBg(R.drawable.bg_menu_badge), contentAlignment = Alignment.Center) {
                Text(if (ui.downloads > 9) "9+" else ui.downloads.toString(), color = LocalRx.current.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        } else Spacer(Modifier.width(0.dp))
    }
}
