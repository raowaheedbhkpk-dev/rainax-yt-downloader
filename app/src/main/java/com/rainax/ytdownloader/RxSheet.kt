package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp

/**
 * Every sheet in the new look (v19.5): round top corners (28dp) that really cut the content (the Compose
 * screens inside paint their own square background, which used to hide the sheet's corners), the screen
 * behind blurred (ThemeOverlay.Rainax.BottomSheet).
 */
class RxSheetDialog(context: Context) : BottomSheetDialog(context) {

    override fun onStart() {
        super.onStart()
        val sheet = findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val r = 28 * context.resources.displayMetrics.density
        sheet.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // only the top corners round: the rectangle runs past the bottom edge
                outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
            }
        }
        sheet.clipToOutline = true
        sheet.elevation = 0f
    }
}

/** The little grey bar on top of a sheet (drag to close). */
@androidx.compose.runtime.Composable
internal fun SheetHandle() {
    val rx = LocalRx.current
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.size(36.dp, 5.dp).background(rx.surfaceAlt, androidx.compose.foundation.shape.RoundedCornerShape(50))
        )
    }
}
