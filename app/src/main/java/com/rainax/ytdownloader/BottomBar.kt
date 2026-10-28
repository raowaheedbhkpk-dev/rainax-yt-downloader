package com.rainax.ytdownloader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * The bottom bar drawn with Jetpack Compose (it replaced RxNavBar, the BottomNavigationView and its menu):
 * a flat strip, a short red bar above the chosen tab (it slides over), the red square "+" in the middle, a
 * running-downloads count on Library and your profile picture there when signed in.
 */

class NavUi {
    /** The chosen place (R.id.nav_home / nav_social / nav_downloads / nav_subs). */
    var selected by mutableIntStateOf(R.id.nav_home)
    var badge by mutableIntStateOf(0)
    var avatar by mutableStateOf<String?>(null)
    /** Bumped on each tap of [tapped] (a little bounce). */
    internal var tapped by mutableIntStateOf(0)
    internal var tapCount by mutableIntStateOf(0)
}

private class NavTab(val id: Int, val icon: Int, val label: String)

private val TABS = listOf(
    NavTab(R.id.nav_home, R.drawable.ic_nav_home, "Home"),
    NavTab(R.id.nav_shorts, R.drawable.ic_shorts, "Shorts"),
    NavTab(R.id.nav_social, 0, ""),                            // the "+" in the middle
    NavTab(R.id.nav_subs, R.drawable.ic_nav_subs, "Subs"),
    NavTab(R.id.nav_downloads, R.drawable.ic_nav_you, "You")
)

@Composable
internal fun BottomBar(ui: NavUi, pick: (Int) -> Unit) {
    val rx = LocalRx.current
    val ctx = LocalContext.current
    val strip = if (AppPrefs.themeMode(ctx) == 3) Color.Black else rx.bg
    Box(Modifier.fillMaxWidth().height(66.dp).background(strip)) {
        Box(Modifier.fillMaxWidth().height(0.7.dp).background(rx.divider))
        // on wide screens the buttons stay close together (a phone-like width)
        BoxWithConstraints(Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxWidth().height(66.dp)) {
            val slot = maxWidth / TABS.size
            val density = LocalDensity.current
            val index = TABS.indexOfFirst { it.id == ui.selected }.coerceAtLeast(0)
            val target = with(density) { (slot * index + slot / 2 - 14.dp).toPx() }
            val x = remember { Animatable(target) }
            LaunchedEffect(target) { x.animateTo(target, tween(320, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
            Row(Modifier.fillMaxSize()) {
                TABS.forEach { t ->
                    Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (t.id == R.id.nav_social) Fab(ui) { pick(t.id) } else Tab(ui, t) { pick(t.id) }
                    }
                }
            }
            // the red bar above the chosen tab (not above the "+", which is red itself)
            if (ui.selected != R.id.nav_social) {
                Box(Modifier.offset { IntOffset(x.value.roundToInt(), 0) }.width(28.dp).height(3.dp).background(rx.accent))
            }
        }
    }
}

@Composable
private fun Bounce(ui: NavUi, id: Int): Float {
    val s = remember { Animatable(1f) }
    LaunchedEffect(ui.tapCount) {
        if (ui.tapCount > 0 && ui.tapped == id) { s.animateTo(0.86f, tween(90)); s.animateTo(1f, tween(180)) }
    }
    return s.value
}

@Composable
private fun Tab(ui: NavUi, t: NavTab, onClick: () -> Unit) {
    val rx = LocalRx.current
    val on = ui.selected == t.id
    val color = if (on) rx.text else rx.text2
    val label = t.label
    Column(
        Modifier.fillMaxSize().scale(Bounce(ui, t.id))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .semantics { contentDescription = if (t.id == R.id.nav_subs) "Subscriptions" else label }
            .padding(top = 9.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            val pic = ui.avatar
            if (t.id == R.id.nav_downloads && pic != null) {
                // signed in: your round profile picture, with a ring on the chosen tab
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (on) rx.text else Color.Transparent).padding(if (on) 1.5.dp else 0.dp)) {
                    RxNetImage(pic, Modifier.fillMaxSize().clip(CircleShape), circle = true, widthPx = 160)
                }
            } else {
                Image(painterResource(t.icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
            }
            if (t.id == R.id.nav_downloads && ui.badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp).size(16.dp).background(rx.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (ui.badge > 99) "99+" else ui.badge.toString(), color = Color.White, fontSize = 9.sp, lineHeight = 9.sp)
                }
            }
        }
        Text(t.label.uppercase(), Modifier.padding(top = 4.dp), color = color, fontSize = 10.sp, letterSpacing = 0.1.em,
            fontFamily = RxFonts.body, maxLines = 1)
    }
}

@Composable
private fun Fab(ui: NavUi, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).scale(Bounce(ui, R.id.nav_social)).rxDrawableBg(R.drawable.bg_nav_fab)
            .clickable(onClick = onClick).semantics { contentDescription = "Add: download from a link" },
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(R.drawable.ic_nav_plus), null, Modifier.size(24.dp))
    }
}

/** The app-update pill above the bottom bar (Compose; it replaced the views in activity_main.xml). */
class UpdatePillUi {
    var shown by mutableStateOf(false)
    var title by mutableStateOf("")
    var sub by mutableStateOf("")
    var action by mutableStateOf("Install")
    var actionOn by mutableStateOf(false)
    var done by mutableStateOf(false)
    /** 0..100, or -1 while the size is not known yet (a turning ring). */
    var progress by mutableIntStateOf(-1)
    var onAction: () -> Unit = {}
}

@Composable
internal fun UpdatePill(ui: UpdatePillUi) {
    if (!ui.shown) return
    val pop = remember { Animatable(0.8f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(220)) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.padding(bottom = 12.dp).scale(pop.value).rxDrawableBg(R.drawable.bg_update_pill)
                .clickable { ui.onAction() }
                .semantics { contentDescription = "${ui.title}. ${ui.sub}" }
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                val gold = androidx.compose.ui.res.colorResource(R.color.rx_cinema_gold)
                val track = androidx.compose.ui.res.colorResource(R.color.rx_cinema_track)
                if (ui.done) Box(Modifier.size(9.dp).background(LocalRx.current.accent))
                else if (ui.progress in 0..100) androidx.compose.material3.CircularProgressIndicator(
                    progress = { ui.progress / 100f }, modifier = Modifier.size(28.dp), color = gold, trackColor = track, strokeWidth = 3.dp
                )
                else androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = gold, trackColor = track, strokeWidth = 3.dp)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(ui.title.uppercase(), color = Color.White, fontSize = 14.sp, fontFamily = RxFonts.display, maxLines = 1)
                Text(ui.sub, Modifier.widthIn(max = 170.dp), color = androidx.compose.ui.res.colorResource(R.color.rx_cinema_text3), fontSize = 10.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Box(
                Modifier.padding(start = 12.dp).height(34.dp).background(LocalRx.current.accent).clickable { ui.onAction() }
                    .padding(horizontal = 14.dp).alpha(if (ui.actionOn) 1f else 0.35f),
                contentAlignment = Alignment.Center
            ) {
                Text(ui.action.uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 0.15.em)
            }
        }
    }
}

/**
 * Home's floating tab bar (iOS style): a rounded bar with Home, Shorts, Subs and You, and the round "+" (download
 * from a link) beside it. It floats over the list; the progressive blur sits behind it (HomePage).
 */
@Composable
internal fun FloatingTabBar(ui: NavUi, pick: (Int) -> Unit) {
    val rx = LocalRx.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).height(64.dp)
                .shadow(10.dp, RoundedCornerShape(32.dp), ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
                .background(rx.surface.copy(alpha = 0.92f), RoundedCornerShape(32.dp))
                .border(0.5.dp, rx.outline, RoundedCornerShape(32.dp))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.filter { it.id != R.id.nav_social }.forEach { t ->
                val on = ui.selected == t.id
                val color = if (on) rx.text else rx.text2
                Column(
                    Modifier.weight(1f).fillMaxSize().scale(Bounce(ui, t.id))
                        .clickable(remember { MutableInteractionSource() }, null) { pick(t.id) }
                        .semantics { contentDescription = if (t.id == R.id.nav_subs) "Subscriptions" else t.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        val pic = ui.avatar
                        if (t.id == R.id.nav_downloads && pic != null) {
                            Box(Modifier.size(26.dp).clip(CircleShape).background(if (on) rx.text else Color.Transparent).padding(if (on) 1.5.dp else 0.dp)) {
                                RxNetImage(pic, Modifier.fillMaxSize().clip(CircleShape), circle = true, widthPx = 160)
                            }
                        } else {
                            Image(painterResource(t.icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(color))
                        }
                        if (t.id == R.id.nav_downloads && ui.badge > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp).size(16.dp).background(rx.text, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(if (ui.badge > 99) "99+" else ui.badge.toString(), color = rx.bg, fontSize = 9.sp, lineHeight = 9.sp)
                            }
                        }
                    }
                    Text(t.label, Modifier.padding(top = 3.dp), color = color, fontSize = 11.sp,
                        fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                        fontFamily = ModernFonts.family, maxLines = 1)
                }
            }
        }
        // the round "+": download from a link
        Box(
            Modifier.padding(start = 10.dp).size(56.dp).scale(Bounce(ui, R.id.nav_social))
                .shadow(10.dp, CircleShape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                .background(rx.text, CircleShape).clip(CircleShape)
                .clickable { pick(R.id.nav_social) }.semantics { contentDescription = "Add: download from a link" },
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_nav_plus), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.bg))
        }
    }
}
