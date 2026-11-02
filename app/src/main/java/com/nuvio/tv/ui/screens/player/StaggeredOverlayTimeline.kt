package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

internal const val STAGGERED_OVERLAY_HOLD_MS = 5_000L

internal data class StaggeredOverlayFrame(
    val containerAlpha: Float,
    val lineFraction: Float,
    val itemAlphas: List<Float>,
) {
    companion object {
        fun hidden(count: Int) = StaggeredOverlayFrame(0f, 0f, List(count) { 0f })
    }
}

/**
 * Start times of the line + staggered items sequence used by the parental guide and display mode
 * overlays. The gaps and the hold are real time; only the fades follow [fadeScale] (0 for animations
 * off, below 1 for faster animations), so a slow animator scale can never keep an overlay up longer.
 */
internal class StaggeredOverlayTimeline(val count: Int, fadeScale: Float) {
    private val scale = fadeScale.coerceIn(0f, 1f)

    val containerInMs = fade(300)
    val lineInMs = fade(400)
    val itemInMs = fade(200)
    val itemOutMs = fade(150)
    val lineOutMs = fade(300)
    val containerOutMs = fade(200)

    val lineInStart: Long = containerInMs
    val itemInStarts: List<Long>
    val shownAt: Long
    val holdEnd: Long
    val itemOutStarts: List<Long>
    val lineOutStart: Long
    val containerOutStart: Long
    val totalMs: Long

    init {
        var cursor = lineInStart + lineInMs
        itemInStarts = List(count) {
            val start = cursor + 80
            cursor = start + itemInMs
            start
        }
        shownAt = cursor
        cursor += STAGGERED_OVERLAY_HOLD_MS
        holdEnd = cursor
        val outStarts = LongArray(count)
        for (i in (count - 1) downTo 0) {
            outStarts[i] = cursor + 60
            cursor = outStarts[i] + itemOutMs
        }
        itemOutStarts = outStarts.toList()
        lineOutStart = cursor + 100
        cursor = lineOutStart + lineOutMs
        containerOutStart = cursor + 200
        totalMs = containerOutStart + containerOutMs
    }

    fun frameAt(elapsedMs: Long): StaggeredOverlayFrame {
        val container = if (elapsedMs < containerOutStart) {
            eased(elapsedMs, 0L, containerInMs)
        } else {
            1f - eased(elapsedMs, containerOutStart, containerOutMs)
        }
        val line = if (elapsedMs < lineOutStart) {
            eased(elapsedMs, lineInStart, lineInMs)
        } else {
            1f - eased(elapsedMs, lineOutStart, lineOutMs)
        }
        val items = List(count) { i ->
            if (elapsedMs < itemOutStarts[i]) {
                eased(elapsedMs, itemInStarts[i], itemInMs)
            } else {
                1f - eased(elapsedMs, itemOutStarts[i], itemOutMs)
            }
        }
        return StaggeredOverlayFrame(container, line, items)
    }

    private fun fade(baseMs: Int): Long = (baseMs * scale).toLong()

    private fun eased(elapsedMs: Long, startMs: Long, durationMs: Long): Float = when {
        elapsedMs < startMs -> 0f
        durationMs <= 0L -> 1f
        else -> FastOutSlowInEasing.transform(((elapsedMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f))
    }
}

/**
 * Plays the sequence on the frame clock and reports every frame through [onFrame]; returns once the
 * overlay has faded out, sleeping through the hold. With [alreadyShown] it starts fully shown with a fresh hold, for content that
 * changes while the overlay is up. The system animator scale only shortens the fades.
 */
internal suspend fun runStaggeredOverlay(
    count: Int,
    alreadyShown: Boolean = false,
    onFrame: (StaggeredOverlayFrame) -> Unit,
) {
    val fadeScale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val timeline = StaggeredOverlayTimeline(count, fadeScale)
    val offsetMs = if (alreadyShown) timeline.shownAt else 0L
    val startedAtMs = withFrameMillis { it }
    while (true) {
        val elapsedMs = withFrameMillis { it } - startedAtMs + offsetMs
        if (elapsedMs >= timeline.totalMs) {
            onFrame(StaggeredOverlayFrame.hidden(count))
            return
        }
        onFrame(timeline.frameAt(elapsedMs))
        if (elapsedMs >= timeline.shownAt && elapsedMs < timeline.holdEnd) {
            delay(timeline.holdEnd - elapsedMs)
        }
    }
}
