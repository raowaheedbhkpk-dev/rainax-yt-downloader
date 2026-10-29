package com.rainax.ytdownloader

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * RAINAX's look (since v19.2 on every screen): black and white only (no accent colour), one clean geometric
 * face (Plus Jakarta Sans, SIL Open Font License), soft rounded cards and pill buttons, more air.
 */

object ModernFonts {
    val family = FontFamily(
        Font(R.font.rx_modern_regular, FontWeight.Normal),
        Font(R.font.rx_modern_medium, FontWeight.Medium),
        Font(R.font.rx_modern_bold, FontWeight.SemiBold),
        Font(R.font.rx_modern_bold, FontWeight.Bold),
        Font(R.font.rx_modern_extrabold, FontWeight.ExtraBold)
    )
}

/**
 * The black-and-white colours, iOS 17 style: an off-white page (#F2F2F7) with white cards in light, #1C1C1E
 * with #2C2C2E cards in dark. [RxColors.card] is the soft fill inside cards (small round buttons, placeholders).
 */
fun monoColors(rx: RxColors): RxColors {
    val d = rx.dark
    val text = if (d) Color(0xFFFFFFFF) else Color(0xFF000000)
    val bg = if (d) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
    val cardBg = if (d) Color(0xFF2C2C2E) else Color(0xFFFFFFFF)
    val soft = if (d) Color(0xFF3A3A3C) else Color(0xFFF2F2F7)
    return RxColors(
        bg = bg,
        surface = cardBg,
        card = soft,
        text = text,
        text2 = if (d) Color(0xFF98989F) else Color(0xFF6C6C70),
        divider = if (d) Color(0xFF38383A) else Color(0xFFE5E5EA),
        accent = text,
        accentText = text,
        accentContainer = soft,
        onAccent = if (d) Color(0xFF000000) else Color(0xFFFFFFFF),
        outline = if (d) Color(0xFF38383A) else Color(0xFFE5E5EA),
        surfaceAlt = if (d) Color(0xFF3A3A3C) else Color(0xFFE5E5EA),
        gold = if (d) Color(0xFFA1A1AA) else Color(0xFF6B6B70),
        dark = d
    )
}

/** The text face. */
@Composable
fun rxBodyFont(): FontFamily = ModernFonts.family

/** The heading face (the same family; headings pick a heavier weight). */
@Composable
fun rxHeadFont(): FontFamily = ModernFonts.family

/** Headings are written as they are (no capitals). */
fun rxHeadCase(s: String): String = s

/** Cards and pictures: soft rounded corners. */
fun rxShape(radius: Int = 16): Shape = RoundedCornerShape(radius.dp)

/** See-through page and surface colours (v20.2) for sheets, popups and the side menu, like the floating tab bar. */
fun glassColors(rx: RxColors): RxColors {
    val a = if (rx.dark) 0.80f else 0.82f
    return RxColors(
        bg = rx.bg.copy(alpha = a), surface = rx.surface.copy(alpha = a), card = rx.card, text = rx.text, text2 = rx.text2,
        divider = rx.divider, accent = rx.accent, accentText = rx.accentText, accentContainer = rx.accentContainer,
        onAccent = rx.onAccent, outline = rx.outline, surfaceAlt = rx.surfaceAlt, gold = rx.gold, dark = rx.dark
    )
}

/**
 * The one colour (v20.4): YouTube-style red, used sparingly: the round "+", how much of a video you watched,
 * LIVE, and the big Download button. Everything else stays black and white. (#E6002E keeps white text readable.)
 */
val RxRed = Color(0xFFE6002E)
