package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SeekingMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * Remote seeking. A tap moves 10 s. A held key glides from its first repeat: the cursor moves every frame at
 * [START_SPEED] (film ms per ms) for the first [GLIDE_FROM_MS] of holding, then the speed doubles every 2.5 s up to
 * 480x (Gentle) or every 0.8 s up to a top that crosses the film in about 5 s (Balanced). A TV remote over HDMI-CEC
 * stops at 5/6 of the top: some TVs send the release seconds after the key is let go.
 * Fast steps 10, 20, 30, then 60 s per key repeat like the official app; [StepChase] spreads every step over the
 * time to the next repeat, so the cursor still moves every frame.
 */
object PlayerScrubRates {
    const val STEP_SHORT_MS = 10_000L

    private const val GLIDE_FROM_MS = 1_000L
    private const val START_SPEED = 30.0
    private const val GENTLE_MAX_SPEED = 480.0
    private const val GENTLE_DOUBLING_MS = 2_500.0
    private const val BALANCED_DOUBLING_MS = 800.0
    private const val BALANCED_MIN_TOP = 480.0
    private const val BALANCED_MAX_TOP = 1_440.0
    private const val BALANCED_CROSS_MS = 5_000.0
    private const val TV_REMOTE_SHARE = 5.0 / 6.0

    /** Thumbnails, time and timeline follow a glide at this interval; the bar's own cursor follows every frame. */
    const val GLIDE_PUBLISH_MS = 166L

    fun tapDeltaMs(forward: Boolean): Long = if (forward) STEP_SHORT_MS else -STEP_SHORT_MS

    /** Top glide speed; [filmMs] is the title's length (0 when unknown). */
    fun topSpeed(mode: SeekingMode, filmMs: Long, tvRemote: Boolean = false): Double {
        val top = when (mode) {
            SeekingMode.BALANCED, SeekingMode.CONFIRM -> (filmMs / BALANCED_CROSS_MS).coerceIn(BALANCED_MIN_TOP, BALANCED_MAX_TOP)
            else -> GENTLE_MAX_SPEED
        }
        return if (tvRemote) top * TV_REMOTE_SHARE else top
    }

    /** Film ms per ms for a key held for [holdDurationMs]. */
    fun glideSpeed(holdDurationMs: Long, mode: SeekingMode, filmMs: Long = 0L, tvRemote: Boolean = false): Double {
        val doubling = if (mode == SeekingMode.BALANCED || mode == SeekingMode.CONFIRM) BALANCED_DOUBLING_MS else GENTLE_DOUBLING_MS
        val ramp = START_SPEED * 2.0.pow((holdDurationMs - GLIDE_FROM_MS).coerceAtLeast(0L) / doubling)
        return min(topSpeed(mode, filmMs, tvRemote), ramp)
    }

    /** Fast: the step for the [repeat]th key repeat of a hold (1 for the first). */
    fun repeatStepMs(repeat: Int): Long = when {
        repeat >= 15 -> 60_000L
        repeat >= 8 -> 30_000L
        repeat >= 3 -> 20_000L
        else -> STEP_SHORT_MS
    }

    /**
     * Fast: every key repeat moves the target a step; the cursor follows through two smoothing stages, each about
     * [CHASE_SHARE] repeat intervals long, so its speed stays even (within a few per cent) at any repeat rate and it
     * never passes the target. The interval is learnt from the repeats (a Bluetooth remote about 50 ms, a TV remote
     * over HDMI-CEC 100 to 450 ms).
     */
    class StepChase(startMs: Double, private val endMs: Double) {
        var targetMs = startMs
            private set
        var cursorMs = startMs
            private set
        private var midMs = startMs
        private var repeats = 0
        private var lastRepeatAt = -1L
        var intervalMs = FIRST_INTERVAL_MS
            private set

        fun repeat(at: Long, forward: Boolean) {
            repeats++
            if (lastRepeatAt >= 0L && at > lastRepeatAt) {
                val gap = (at - lastRepeatAt).toDouble().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                intervalMs = if (repeats == 2) gap else intervalMs * 0.7 + gap * 0.3
            }
            lastRepeatAt = at
            val step = repeatStepMs(repeats).toDouble()
            targetMs = (targetMs + if (forward) step else -step).coerceIn(0.0, endMs)
        }

        /** Moves the cursor on by [dtMs]; returns its speed in film ms per ms. */
        fun advance(dtMs: Long): Double {
            if (dtMs <= 0L) return 0.0
            val before = cursorMs
            val keep = exp(-dtMs / (CHASE_SHARE * intervalMs))
            midMs = targetMs + (midMs - targetMs) * keep
            cursorMs = midMs + (cursorMs - midMs) * keep
            return abs(cursorMs - before) / dtMs
        }

        companion object {
            const val CHASE_SHARE = 1.5
            const val FIRST_INTERVAL_MS = 150.0
            const val MIN_INTERVAL_MS = 30.0
            const val MAX_INTERVAL_MS = 500.0
        }
    }
}
