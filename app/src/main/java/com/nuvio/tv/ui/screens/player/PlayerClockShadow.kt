package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.sp

internal val PLAYER_CLOCK_FONT_SIZE = 15.sp
internal val PLAYER_ENDS_AT_FONT_SIZE = 11.5.sp
internal const val PLAYER_ENDS_AT_ALPHA = 0.90f

/** Crisp subtitle-style drop shadow under the clock and "Ends at"; sizes follow [pxPerDp]. */
internal fun playerClockShadow(pxPerDp: Float): Shadow =
    Shadow(color = Color(0xB3000000), offset = Offset(pxPerDp, pxPerDp), blurRadius = pxPerDp)
