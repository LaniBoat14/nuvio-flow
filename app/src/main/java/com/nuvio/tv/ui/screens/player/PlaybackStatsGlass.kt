package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * The glass panel behind the playback stats HUD, matching how Arc draws it during playback:
 * a translucent smoked body, a faint top reflection, a lit outer edge and a thin inner rim.
 * It has no blur pass, so it costs nothing extra on top of the decoded video.
 */
internal fun Modifier.playbackStatsGlass(shape: Shape): Modifier =
    clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(Color(0xFF353B40).copy(alpha = 0.38f), Color(0xFF101418).copy(alpha = 0.38f))
            ),
            shape
        )
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val stroke = Stroke(1.dp.toPx())
            val rimInset = 1.5.dp.toPx()
            val innerOutline = if (size.minDimension > rimInset * 2) {
                shape.createOutline(
                    Size(size.width - rimInset * 2, size.height - rimInset * 2),
                    layoutDirection,
                    this
                )
            } else null
            val reflection = Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.14f),
                0.38f to Color.White.copy(alpha = 0.025f),
                0.72f to Color.Transparent,
                1f to Color.White.copy(alpha = 0.06f)
            )
            val edge = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.50f),
                    Color.White.copy(alpha = 0.03f),
                    Color.White.copy(alpha = 0.23f)
                )
            )
            val innerRim = Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.13f), Color.Transparent, Color.Black.copy(alpha = 0.18f))
            )
            onDrawWithContent {
                drawOutline(outline, reflection)
                drawContent()
                drawOutline(outline, edge, style = stroke)
                if (innerOutline != null) translate(rimInset, rimInset) {
                    drawOutline(innerOutline, innerRim, style = stroke)
                }
            }
        }
