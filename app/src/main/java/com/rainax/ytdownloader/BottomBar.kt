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
    NavTab(R.id.nav_music, R.drawable.ic_music_note, "Music"),      // v19.4: Music in place of Subs (Subscriptions: side menu)
    NavTab(R.id.nav_downloads, R.drawable.ic_nav_you, "You")
)

@Composable
internal fun BottomBar(ui: NavUi, pick: (Int) -> Unit) {
    // v19.2: every tab gets the floating bar (icons only, round "+"), on the page colour
    Box(Modifier.fillMaxWidth().background(LocalRx.current.bg)) { FloatingTabBar(ui, pick) }
}

@Composable
private fun Bounce(ui: NavUi, id: Int): Float {
    val s = remember { Animatable(1f) }
    LaunchedEffect(ui.tapCount) {
        if (ui.tapCount > 0 && ui.tapped == id) { s.animateTo(0.86f, tween(90)); s.animateTo(1f, tween(180)) }
    }
    return s.value
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
                if (ui.done) Box(Modifier.size(9.dp).background(Color.White, CircleShape))
                else if (ui.progress in 0..100) androidx.compose.material3.CircularProgressIndicator(
                    progress = { ui.progress / 100f }, modifier = Modifier.size(28.dp), color = gold, trackColor = track, strokeWidth = 3.dp
                )
                else androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp), color = gold, trackColor = track, strokeWidth = 3.dp)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(ui.title, color = Color.White, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                Text(ui.sub, Modifier.widthIn(max = 170.dp), color = androidx.compose.ui.res.colorResource(R.color.rx_cinema_text3), fontSize = 10.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Box(
                Modifier.padding(start = 12.dp).height(34.dp).clip(CircleShape).background(Color.White).clickable { ui.onAction() }
                    .padding(horizontal = 14.dp).alpha(if (ui.actionOn) 1f else 0.35f),
                contentAlignment = Alignment.Center
            ) {
                Text(ui.action, color = Color.Black, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 0.em)
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
                val color = rx.text
                Box(
                    Modifier.weight(1f).fillMaxSize().scale(Bounce(ui, t.id))
                        .clickable(remember { MutableInteractionSource() }, null) { pick(t.id) }
                        .semantics { contentDescription = (if (t.id == R.id.nav_subs) "Subscriptions" else t.label) + if (on) ", chosen" else "" },
                    contentAlignment = Alignment.Center
                ) {
                    // icons only (v19.2): the chosen one sits in a round black (white in dark) circle
                    Box(Modifier.size(48.dp).background(if (on) rx.accent else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        val pic = ui.avatar
                        if (t.id == R.id.nav_downloads && pic != null) {
                            Box(Modifier.size(if (on) 34.dp else 28.dp).clip(CircleShape)) {
                                RxNetImage(pic, Modifier.fillMaxSize(), circle = true, widthPx = 160)
                            }
                        } else {
                            Image(painterResource(t.icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(if (on) rx.onAccent else rx.text2))
                        }
                    }
                    if (t.id == R.id.nav_downloads && ui.badge > 0) {
                        Box(
                            Modifier.align(Alignment.Center).offset(x = 14.dp, y = (-14).dp).size(18.dp)
                                .background(if (on) Color.White else rx.accent, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (ui.badge > 99) "99+" else ui.badge.toString(), color = if (on) rx.accent else Color.White, fontSize = 9.sp, lineHeight = 9.sp,
                                fontFamily = ModernFonts.family, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // the round "+": download from a link
        Box(
            Modifier.padding(start = 10.dp).size(56.dp).scale(Bounce(ui, R.id.nav_social))
                .shadow(10.dp, CircleShape, ambientColor = Color(0x55E6002E), spotColor = Color(0x55E6002E))
                .background(RxRed, CircleShape).clip(CircleShape)
                .clickable { pick(R.id.nav_social) }.semantics { contentDescription = "Add: download from a link" },
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.ic_nav_plus), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(Color.White))
        }
    }
}
