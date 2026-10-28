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
 * "Modern Minimalism": the new look, on Home first. Black and white only (no accent colour), a clean geometric
 * face (Plus Jakarta Sans, SIL Open Font License), soft rounded cards and pill buttons, more air. Screens inside
 * [RxModern] get it; the rest of the app keeps the classic look.
 */

/** True inside [RxModern]. */
val LocalModern = staticCompositionLocalOf { false }

object ModernFonts {
    val family = FontFamily(
        Font(R.font.rx_modern_regular, FontWeight.Normal),
        Font(R.font.rx_modern_medium, FontWeight.Medium),
        Font(R.font.rx_modern_bold, FontWeight.SemiBold),
        Font(R.font.rx_modern_bold, FontWeight.Bold),
        Font(R.font.rx_modern_extrabold, FontWeight.ExtraBold)
    )
}

/** The black-and-white colours (white page in light, true black in dark). */
fun monoColors(rx: RxColors): RxColors {
    val d = rx.dark
    val text = if (d) Color(0xFFFAFAFA) else Color(0xFF0A0A0A)
    val bg = if (d) Color(0xFF000000) else Color(0xFFFFFFFF)
    val soft = if (d) Color(0xFF161616) else Color(0xFFF4F4F5)
    return RxColors(
        bg = bg,
        surface = bg,
        card = soft,
        text = text,
        text2 = if (d) Color(0xFFA1A1AA) else Color(0xFF6B6B70),
        divider = if (d) Color(0xFF232323) else Color(0xFFEDEDEF),
        accent = text,
        accentText = text,
        accentContainer = soft,
        onAccent = bg,
        outline = if (d) Color(0xFF2A2A2A) else Color(0xFFE4E4E7),
        surfaceAlt = soft,
        gold = if (d) Color(0xFFA1A1AA) else Color(0xFF6B6B70),
        dark = d
    )
}

/** Everything inside draws in the modern black-and-white look. */
@Composable
fun RxModern(content: @Composable () -> Unit) {
    val rx = LocalRx.current
    val mono = remember(rx) { monoColors(rx) }
    CompositionLocalProvider(LocalRx provides mono, LocalModern provides true, content = content)
}

/** Text face: the modern one inside [RxModern], RAINAX's body face elsewhere. */
@Composable
fun rxBodyFont(): FontFamily = if (LocalModern.current) ModernFonts.family else RxFonts.body

/** Heading face: the modern one (in sentence case) inside [RxModern], the tall film face elsewhere. */
@Composable
fun rxHeadFont(): FontFamily = if (LocalModern.current) ModernFonts.family else RxFonts.display

/** Headings: as written in the modern look, in capitals in the classic one. */
@Composable
fun rxHeadCase(s: String): String = if (LocalModern.current) s else s.uppercase()

/** Cards and pictures: soft rounded corners in the modern look, nearly square in the classic one. */
@Composable
fun rxShape(modern: Int = 16): Shape = if (LocalModern.current) RoundedCornerShape(modern.dp) else RoundedCornerShape(2.dp)
