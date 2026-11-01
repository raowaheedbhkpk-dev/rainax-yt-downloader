package rainax.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/*
 * The phone app's look on Windows: red and white ("W1"), iOS-like greys, Plus Jakarta Sans.
 * Red fills always carry white; red words and icons on cards use [Pal.accentText] (lighter in dark).
 */

val RxRed = Color(0xFFE6002E)

class Pal(
    val bg: Color,
    val surface: Color,
    val soft: Color,
    val text: Color,
    val text2: Color,
    val divider: Color,
    val accentText: Color,
    val accentSoft: Color,
    val dark: Boolean
)

val LightPal = Pal(
    bg = Color(0xFFF5F5F7), surface = Color.White, soft = Color(0xFFEDEDF0), text = Color(0xFF1D1D1F),
    text2 = Color(0xFF6E6E73), divider = Color(0xFFE5E5EA), accentText = Color(0xFFD70029),
    accentSoft = Color(0xFFFFE8EC), dark = false
)

val DarkPal = Pal(
    bg = Color(0xFF1C1C1E), surface = Color(0xFF2C2C2E), soft = Color(0xFF3A3A3C), text = Color.White,
    text2 = Color(0xFF98989F), divider = Color(0xFF38383A), accentText = Color(0xFFFF5470),
    accentSoft = Color(0xFF4A1C24), dark = true
)

val LocalPal = staticCompositionLocalOf { LightPal }

val Jakarta = FontFamily(
    Font("fonts/rx_modern_regular.ttf", FontWeight.Normal),
    Font("fonts/rx_modern_medium.ttf", FontWeight.Medium),
    Font("fonts/rx_modern_bold.ttf", FontWeight.SemiBold),
    Font("fonts/rx_modern_bold.ttf", FontWeight.Bold),
    Font("fonts/rx_modern_extrabold.ttf", FontWeight.ExtraBold)
)

private fun rxTypography(): Typography {
    val t = Typography()
    val f = Jakarta
    return Typography(
        displayLarge = t.displayLarge.copy(fontFamily = f), displayMedium = t.displayMedium.copy(fontFamily = f),
        displaySmall = t.displaySmall.copy(fontFamily = f), headlineLarge = t.headlineLarge.copy(fontFamily = f),
        headlineMedium = t.headlineMedium.copy(fontFamily = f), headlineSmall = t.headlineSmall.copy(fontFamily = f),
        titleLarge = t.titleLarge.copy(fontFamily = f), titleMedium = t.titleMedium.copy(fontFamily = f),
        titleSmall = t.titleSmall.copy(fontFamily = f), bodyLarge = t.bodyLarge.copy(fontFamily = f),
        bodyMedium = t.bodyMedium.copy(fontFamily = f), bodySmall = t.bodySmall.copy(fontFamily = f),
        labelLarge = t.labelLarge.copy(fontFamily = f), labelMedium = t.labelMedium.copy(fontFamily = f),
        labelSmall = t.labelSmall.copy(fontFamily = f)
    )
}

/** 0 = follow Windows, 1 = light, 2 = dark. */
@Composable
fun RxDesktopTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val p = if (dark) DarkPal else LightPal
    val scheme = if (dark) darkColorScheme(
        primary = RxRed, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.soft, onSurfaceVariant = p.text2, outline = p.divider
    ) else lightColorScheme(
        primary = RxRed, onPrimary = Color.White, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.soft, onSurfaceVariant = p.text2, outline = p.divider
    )
    MaterialTheme(colorScheme = scheme, typography = rxTypography()) {
        CompositionLocalProvider(LocalPal provides p, content = content)
    }
}
