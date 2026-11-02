package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.roundToInt

/** White ends at the same level as [capSubtitleHighlight]. */
internal const val HDR_OVERLAY_COLOR_SCALE = 180f / 255f

/**
 * Tones down the colours of the player overlay while keeping their opacity, so bright text and icons stop glaring
 * on HDR video and the glass behind them stays as dark as before.
 */
internal fun hdrOverlayColor(argb: Int): Int {
    fun channel(shift: Int) = (((argb ushr shift) and 255) * HDR_OVERLAY_COLOR_SCALE).roundToInt()
    return (argb and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

private val hdrOverlayFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            HDR_OVERLAY_COLOR_SCALE, 0f, 0f, 0f, 0f,
            0f, HDR_OVERLAY_COLOR_SCALE, 0f, 0f, 0f,
            0f, 0f, HDR_OVERLAY_COLOR_SCALE, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

internal fun Modifier.hdrOverlayDim(enabled: Boolean): Modifier = if (!enabled) this else drawWithContent {
    val paint = Paint().apply { colorFilter = hdrOverlayFilter }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), paint)
        drawContent()
        canvas.restore()
    }
}
