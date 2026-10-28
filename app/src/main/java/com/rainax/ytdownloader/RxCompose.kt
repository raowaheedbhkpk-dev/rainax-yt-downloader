package com.rainax.ytdownloader

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The bridge between RAINAX's theme and Jetpack Compose screens (the app moves to Compose one screen at a
 * time). Colours are read from the SAME place the old XML screens use: the screen's theme (accent, AMOLED
 * black...) and the light/dark colour resources. So a Compose screen always matches the rest of the app, and
 * Settings › Theme / Accent need nothing new.
 */
@Immutable
class RxColors(
    val bg: Color,
    val surface: Color,
    val card: Color,
    val text: Color,
    val text2: Color,
    val divider: Color,
    val accent: Color,
    val accentText: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val outline: Color,
    val surfaceAlt: Color,
    val gold: Color,
    val dark: Boolean
) {
    companion object {
        fun of(c: Context): RxColors {
            fun attr(id: Int, fallback: Int): Color {
                val tv = android.util.TypedValue()
                if (!c.theme.resolveAttribute(id, tv, true)) return Color(androidx.core.content.ContextCompat.getColor(c, fallback))
                return Color(if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(c, tv.resourceId) else tv.data)
            }
            fun res(id: Int) = Color(androidx.core.content.ContextCompat.getColor(c, id))
            return RxColors(
                bg = attr(R.attr.rxBg, R.color.rx_bg),
                surface = attr(R.attr.rxSurface, R.color.rx_surface),
                card = attr(R.attr.rxCard, R.color.rx_card),
                text = res(R.color.rx_text),
                text2 = res(R.color.rx_text2),
                divider = res(R.color.rx_divider),
                accent = Color(Ui.accent(c)),
                accentText = Color(Ui.accentText(c)),
                accentContainer = attr(R.attr.rxAccentContainer, R.color.rx_card),
                onAccent = res(R.color.rx_on_primary),
                outline = attr(R.attr.rxOutline, R.color.rx_divider),
                surfaceAlt = attr(R.attr.rxSurfaceAlt, R.color.rx_card),
                gold = res(R.color.rx_gold),
                dark = Ui.isDark(c)
            )
        }
    }
}

val LocalRx = staticCompositionLocalOf<RxColors> { error("RxTheme missing") }

/** RAINAX's own fonts: the tall display face for headings, the body face for text. */
object RxFonts {
    val display = FontFamily(
        Font(R.font.rx_display, FontWeight.Normal),
        Font(R.font.rx_display_semibold, FontWeight.SemiBold)
    )
    val body = FontFamily(
        Font(R.font.rx_body_regular, FontWeight.Normal),
        Font(R.font.rx_body_semibold, FontWeight.SemiBold),
        Font(R.font.rx_body_bold, FontWeight.Bold)
    )
}

/**
 * Any drawable as a picture, including the layer-list / shape drawables Compose's painterResource can't
 * read (it only takes vectors and PNG/WebP). Drawn once at [sizeDp] with the screen's theme (accent).
 */
@Composable
fun rxDrawable(id: Int, sizeDp: Int): androidx.compose.ui.graphics.ImageBitmap {
    val ctx = LocalContext.current
    return remember(ctx, id, sizeDp) {
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, id)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        if (d != null) {
            val cv = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, px, px)
            d.draw(cv)
        }
        bmp.asImageBitmap()
    }
}

/**
 * Paints any Android drawable (layer-lists, shapes...) behind a composable, stretched to its size. For the
 * small drawn pictures the old screens already have, e.g. the theme previews.
 */
@Composable
fun Modifier.rxDrawableBg(id: Int): Modifier {
    val ctx = LocalContext.current
    val d = remember(ctx, id) { androidx.core.content.ContextCompat.getDrawable(ctx, id) } ?: return this
    return this.drawBehind {
        drawIntoCanvas { cv ->
            d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            d.draw(cv.nativeCanvas)
        }
    }
}

/** Wrap every Compose screen in this: Material 3 in RAINAX's colours and fonts. */
@Composable
fun RxTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val rx = remember(ctx) { RxColors.of(ctx) }
    val scheme = if (rx.dark) darkColorScheme(
        primary = rx.accent, onPrimary = rx.onAccent, primaryContainer = rx.accentContainer,
        background = rx.bg, onBackground = rx.text, surface = rx.surface, onSurface = rx.text,
        surfaceVariant = rx.card, onSurfaceVariant = rx.text2, outline = rx.divider
    ) else lightColorScheme(
        primary = rx.accent, onPrimary = rx.onAccent, primaryContainer = rx.accentContainer,
        background = rx.bg, onBackground = rx.text, surface = rx.surface, onSurface = rx.text,
        surfaceVariant = rx.card, onSurfaceVariant = rx.text2, outline = rx.divider
    )
    CompositionLocalProvider(LocalRx provides rx) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
