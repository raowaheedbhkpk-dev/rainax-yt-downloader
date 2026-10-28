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
        text2 = if (d) Color(0xFF98989F) else Color(0xFF8E8E93),
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
