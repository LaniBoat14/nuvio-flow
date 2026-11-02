package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.theme.accentBrush

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.util.lerp

private val LoadingEasing = CubicBezierEasing(0.333f, 0f, 0.667f, 1f)
internal const val LOADING_CYCLE_MS = 2_000L
private const val LOADING_FRAMES = 60f

internal fun loadingIndicatorFrame(elapsedMs: Long): Float =
    (elapsedMs.coerceAtLeast(0L) % LOADING_CYCLE_MS) * LOADING_FRAMES / LOADING_CYCLE_MS

/** Spins on the frame clock rather than as an animation, so it keeps turning with animations off or slowed. */
internal suspend fun runLoadingIndicatorClock(onFrame: (Float) -> Unit): Nothing {
    val startedAtMs = withFrameMillis { it }
    while (true) {
        onFrame(loadingIndicatorFrame(withFrameMillis { it } - startedAtMs))
    }
}

@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    val brush = if (color == Color.Unspecified) NuvioTheme.palette.accentBrush() else SolidColor(color)
    val frame = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runLoadingIndicatorClock { frame.floatValue = it } }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            modifier = Modifier
                .size(NuvioTheme.spacing.xxxl)
                .drawWithCache {
                    val scale = size.minDimension * 0.75f / 350f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = 150f * scale
                    val topLeft = center - Offset(radius, radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val stroke = Stroke(width = 50f * scale, cap = StrokeCap.Round)
                    onDrawBehind {
                        val currentFrame = frame.floatValue
                        val start = lerp(0f, 99f, LoadingEasing.transform(((currentFrame - 10f) / 50f).coerceIn(0f, 1f)))
                        val end = lerp(1f, 100f, LoadingEasing.transform((currentFrame / 50f).coerceIn(0f, 1f)))
                        drawArc(
                            brush = brush,
                            startAngle = -90f + currentFrame / 60f * 363f + start * 3.6f,
                            sweepAngle = (end - start) * 3.6f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = stroke
                        )
                    }
                }
        )
    }
}
