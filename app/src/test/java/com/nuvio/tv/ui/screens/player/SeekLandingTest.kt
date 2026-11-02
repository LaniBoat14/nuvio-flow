package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A tap must move every time, in its direction, however far apart the keyframes are. */
class SeekLandingTest {
    private val duration = 7_200_000L

    /** The player's own snapping of [targetMs] to [keyframesMs] under [parameters]. */
    private fun snap(parameters: SeekParameters, targetMs: Long, keyframesMs: LongArray): Long {
        val targetUs = targetMs * 1000L
        val before = keyframesMs.filter { it * 1000L <= targetUs }.maxOrNull() ?: keyframesMs.first()
        val after = keyframesMs.filter { it * 1000L >= targetUs }.minOrNull() ?: before
        return parameters.resolveSeekPositionUs(targetUs, before * 1000L, after * 1000L) / 1000L
    }

    private fun tap(playbackMs: Long, deltaMs: Long, keyframesMs: LongArray): Long {
        val target = (playbackMs + deltaMs).coerceIn(0L, duration)
        val landing = SeekLanding.of(target, keyframeMs = null, tapDeltaMs = target - playbackMs, durationMs = duration)
        return snap(landing.parameters, landing.positionMs, keyframesMs)
    }

    @Test fun knownKeyframeIsTheLanding() {
        val landing = SeekLanding.of(290_000L, keyframeMs = 289_494L, tapDeltaMs = 10_000L, durationMs = duration)
        assertEquals(289_494L, landing.positionMs)
        assertSame(SeekParameters.CLOSEST_SYNC, landing.parameters)
    }

    @Test fun heldSeekLandsOnTheNearestKeyframe() {
        val landing = SeekLanding.of(1_234_567L, keyframeMs = null, tapDeltaMs = null, durationMs = duration)
        assertEquals(1_234_567L, landing.positionMs)
        assertSame(SeekParameters.CLOSEST_SYNC, landing.parameters)
    }

    @Test fun tapStepsInItsDirection() {
        assertSame(SeekParameters.NEXT_SYNC, SeekLanding.of(290_000L, null, 10_000L, duration).parameters)
        assertSame(SeekParameters.PREVIOUS_SYNC, SeekLanding.of(270_000L, null, -10_000L, duration).parameters)
        assertSame(SeekParameters.NEXT_SYNC, SeekLanding.of(290_000L, null, 9_700L, 0L).parameters)
    }

    @Test fun tapsThatCancelOutStayPut() {
        assertSame(SeekParameters.CLOSEST_SYNC, SeekLanding.of(280_000L, null, 0L, duration).parameters)
    }

    @Test fun edgesUseTheNearestKeyframe() {
        assertSame(SeekParameters.CLOSEST_SYNC, SeekLanding.of(0L, null, -5_000L, duration).parameters)
        assertSame(SeekParameters.CLOSEST_SYNC, SeekLanding.of(600L, null, -9_400L, duration).parameters)
        assertSame(SeekParameters.PREVIOUS_SYNC, SeekLanding.of(1_000L, null, -9_000L, duration).parameters)
        assertSame(SeekParameters.CLOSEST_SYNC, SeekLanding.of(duration, null, 6_000L, duration).parameters)
        assertSame(SeekParameters.NEXT_SYNC, SeekLanding.of(duration - 1L, null, 10_000L, duration).parameters)
    }

    /** 30 s keyframes: the nearest keyframe to 10 s ahead is the one playing, so the old snapping stood still. */
    @Test fun nearestKeyframeStoodStillWithSparseKeyframes() {
        val kfs = LongArray(240) { it * 30_000L }
        assertEquals(300_000L, snap(SeekParameters.CLOSEST_SYNC, 310_000L, kfs))
        assertEquals(300_000L, snap(SeekParameters.CLOSEST_SYNC, 290_000L, kfs))
    }

    @Test fun fiveRightTapsMoveFiveTimes() {
        val kfs = LongArray(240) { it * 30_000L }
        var pos = 300_000L
        val seen = ArrayList<Long>()
        repeat(5) {
            val next = tap(pos, 10_000L, kfs)
            assertEquals(pos + 30_000L, next)
            seen += next
            pos = next
        }
        assertEquals(5, seen.distinct().size)
    }

    @Test fun fiveLeftTapsMoveFiveTimes() {
        val kfs = LongArray(240) { it * 30_000L }
        var pos = 600_000L
        repeat(5) {
            val next = tap(pos, -10_000L, kfs)
            assertEquals(pos - 30_000L, next)
            pos = next
        }
    }

    /** Dense keyframes: a tap still moves about 10 s, now never short of it. */
    @Test fun denseKeyframesStillMoveAboutTenSeconds() {
        val kfs = LongArray(4_000) { it * 2_000L + 500L }
        assertEquals(310_500L, tap(300_500L, 10_000L, kfs))
        assertEquals(312_500L, tap(301_000L, 10_000L, kfs))
        assertEquals(290_500L, tap(300_500L, -10_000L, kfs))
        assertEquals(290_500L, tap(301_000L, -10_000L, kfs))
    }

    /** Server thumbnails: a Right tap from 129.8 s targets the grid point 130 s, 200 ms on, while playback runs on. */
    @Test fun gridStepJustAheadStillMovesForward() {
        val kfs = LongArray(240) { it * 30_000L }
        val landing = SeekLanding.of(130_000L, null, tapDeltaMs = 130_000L - 129_800L, durationMs = duration)
        assertSame(SeekParameters.NEXT_SYNC, landing.parameters)
        assertEquals(150_000L, snap(landing.parameters, landing.positionMs, kfs))
        assertNotEquals(120_000L, snap(landing.parameters, landing.positionMs, kfs))
    }
}
