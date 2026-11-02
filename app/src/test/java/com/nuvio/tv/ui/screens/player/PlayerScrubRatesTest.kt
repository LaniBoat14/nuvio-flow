package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SeekingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayerScrubRatesTest {

    private val twoHours = 7_200_000L
    private val episode = 2_700_000L

    @Test
    fun tapMovesTenSecondsEitherWay() {
        assertEquals(10_000L, PlayerScrubRates.tapDeltaMs(forward = true))
        assertEquals(-10_000L, PlayerScrubRates.tapDeltaMs(forward = false))
    }

    private fun gentle(t: Long, tv: Boolean = false) = PlayerScrubRates.glideSpeed(t, SeekingMode.GENTLE, twoHours, tv)
    private fun balanced(t: Long, film: Long = twoHours, tv: Boolean = false) =
        PlayerScrubRates.glideSpeed(t, SeekingMode.BALANCED, film, tv)

    @Test
    fun gentle_startsSlowThenDoublesSmoothlyUpToTheCap() {
        assertEquals(30.0, gentle(0L), 1e-9)
        assertEquals(30.0, gentle(400L), 1e-9)
        assertEquals(30.0, gentle(1_000L), 1e-9)
        assertEquals(60.0, gentle(3_500L), 1e-9)
        assertEquals(120.0, gentle(6_000L), 1e-9)
        assertEquals(240.0, gentle(8_500L), 1e-9)
        assertEquals(480.0, gentle(11_000L), 1e-9)
        assertEquals(480.0, gentle(60_000L), 1e-9)
        var last = 0.0
        for (t in 0L..15_000L step 16L) {
            val v = gentle(t)
            assertTrue(v >= last)
            last = v
        }
    }

    @Test
    fun gentle_doesNotDependOnTheFilmLength() {
        assertEquals(480.0, PlayerScrubRates.glideSpeed(30_000L, SeekingMode.GENTLE, episode), 1e-9)
        assertEquals(480.0, PlayerScrubRates.glideSpeed(30_000L, SeekingMode.GENTLE, 0L), 1e-9)
    }

    @Test
    fun gentle_tvRemoteStartsTheSameAndStopsAtFourHundred() {
        for (t in listOf(0L, 1_000L, 3_500L, 6_000L, 8_500L)) assertEquals(gentle(t), gentle(t, tv = true), 1e-9)
        assertEquals(400.0, gentle(11_000L, tv = true), 1e-9)
        assertEquals(400.0, gentle(60_000L, tv = true), 1e-9)
    }

    @Test
    fun balanced_hasTheSameFirstSecondThenDoublesEveryPointEightSeconds() {
        for (t in listOf(0L, 400L, 1_000L)) assertEquals(gentle(t), balanced(t), 1e-9)
        assertEquals(60.0, balanced(1_800L), 1e-9)
        assertEquals(120.0, balanced(2_600L), 1e-9)
        assertEquals(240.0, balanced(3_400L), 1e-9)
        assertEquals(480.0, balanced(4_200L), 1e-9)
        assertEquals(960.0, balanced(5_000L), 1e-9)
        assertEquals(1_440.0, balanced(6_000L), 1e-9)
        assertEquals(1_440.0, balanced(60_000L), 1e-9)
    }

    @Test
    fun balanced_topSpeedCrossesTheFilmInAboutFiveSeconds() {
        assertEquals(1_440.0, PlayerScrubRates.topSpeed(SeekingMode.BALANCED, twoHours), 1e-9)
        assertEquals(540.0, PlayerScrubRates.topSpeed(SeekingMode.BALANCED, episode), 1e-9)
        assertEquals(480.0, PlayerScrubRates.topSpeed(SeekingMode.BALANCED, 1_200_000L), 1e-9)
        assertEquals(480.0, PlayerScrubRates.topSpeed(SeekingMode.BALANCED, 0L), 1e-9)
        assertEquals(1_440.0, PlayerScrubRates.topSpeed(SeekingMode.BALANCED, 4 * twoHours), 1e-9)
        assertEquals(1_200.0, PlayerScrubRates.topSpeed(SeekingMode.BALANCED, twoHours, tvRemote = true), 1e-9)
        assertEquals(540.0, balanced(60_000L, film = episode), 1e-9)
    }

    /**
     * Seconds of holding to move [distanceMs], from the press, on a Bluetooth remote: the press is a 10 s tap, the
     * first repeat comes after 500 ms and then one every 50 ms.
     */
    private fun holdSeconds(mode: SeekingMode, distanceMs: Long, filmMs: Long = twoHours): Double {
        if (mode == SeekingMode.FAST) {
            var position = PlayerScrubRates.STEP_SHORT_MS
            var repeat = 0
            var t = 500L
            while (true) {
                repeat++
                position += PlayerScrubRates.repeatStepMs(repeat)
                if (position >= distanceMs) return t / 1000.0
                t += 50L
            }
        }
        var position = PlayerScrubRates.STEP_SHORT_MS.toDouble()
        var t = 500L
        while (position < distanceMs) {
            position += PlayerScrubRates.glideSpeed(t, mode, filmMs) * 16
            t += 16
        }
        return t / 1000.0
    }

    private val minutes = listOf(10L, 30L, 60L, 120L).map { it * 60_000L }

    private fun assertTimes(mode: SeekingMode, expected: List<Double>) {
        minutes.zip(expected).forEach { (distance, seconds) ->
            val got = holdSeconds(mode, distance)
            assertEquals("$mode ${distance / 60_000} min: $got s", seconds, got, 0.3)
        }
    }

    @Test
    fun gentle_crossesTenMinutesToTwoHoursAsToday() {
        assertTimes(SeekingMode.GENTLE, listOf(7.7, 11.3, 15.1, 22.6))
    }

    @Test
    fun balanced_crossesTenMinutesToTwoHoursInAboutHalfTheTime() {
        assertTimes(SeekingMode.BALANCED, listOf(4.3, 5.6, 6.8, 9.3))
    }

    @Test
    fun fast_crossesTenMinutesToTwoHoursLikeTheOfficialApp() {
        assertTimes(SeekingMode.FAST, listOf(1.4, 2.4, 3.9, 6.9))
    }

    @Test
    fun balanced_crossesAWholeEpisodeInAboutEightSeconds() {
        val seconds = holdSeconds(SeekingMode.BALANCED, episode, filmMs = episode)
        assertTrue("$seconds s", seconds in 7.5..9.0)
    }

    @Test
    fun fast_stepsLikeTheOfficialApp() {
        assertEquals(listOf(10_000L, 10_000L), (1..2).map(PlayerScrubRates::repeatStepMs))
        assertEquals(List(5) { 20_000L }, (3..7).map(PlayerScrubRates::repeatStepMs))
        assertEquals(List(7) { 30_000L }, (8..14).map(PlayerScrubRates::repeatStepMs))
        assertEquals(60_000L, PlayerScrubRates.repeatStepMs(15))
        assertEquals(60_000L, PlayerScrubRates.repeatStepMs(400))
    }

    @Test
    fun heldSeek_movesAboutOneMinuteInTwoSeconds() {
        fun reach(mode: SeekingMode): Double {
            var position = 10_000.0
            var t = 400L
            while (t < 2_000L) {
                position += PlayerScrubRates.glideSpeed(t, mode, twoHours) * 16
                t += 16
            }
            return position
        }
        assertTrue(reach(SeekingMode.GENTLE) in 55_000.0..70_000.0)
        assertTrue(reach(SeekingMode.BALANCED) in 70_000.0..80_000.0)
    }

    /** A Fast hold through [PlayerScrubRates.StepChase], frame by frame: (time, cursor, speed) per 16 ms frame. */
    private fun chaseFrames(everyMs: Long, holdMs: Long, afterMs: Long = 0L, forward: Boolean = true): List<Triple<Long, Double, Double>> {
        val chase = PlayerScrubRates.StepChase(610_000.0, twoHours.toDouble())
        val out = ArrayList<Triple<Long, Double, Double>>()
        var next = 500L
        var t = 500L
        chase.repeat(next, forward)
        next += everyMs
        while (t < holdMs + afterMs) {
            t += 16
            while (next <= t && next <= holdMs) {
                chase.repeat(next, forward)
                next += everyMs
            }
            out += Triple(t, chase.cursorMs, chase.advance(16))
        }
        return out
    }

    @Test
    fun fast_cursorNeverPassesTheTargetAndEndsOnIt() {
        for (every in listOf(50L, 100L, 150L, 300L, 450L)) {
            val chase = PlayerScrubRates.StepChase(610_000.0, twoHours.toDouble())
            var t = 500L
            var last = chase.cursorMs
            repeat(40) {
                chase.repeat(t, true)
                repeat((every / 16).toInt()) {
                    chase.advance(16)
                    assertTrue("$every: ${chase.cursorMs} > ${chase.targetMs}", chase.cursorMs <= chase.targetMs + 1e-6)
                    assertTrue("$every: went back", chase.cursorMs >= last - 1e-6)
                    last = chase.cursorMs
                }
                t += every
            }
            repeat(1_500) { chase.advance(16) }
            assertEquals(chase.targetMs, chase.cursorMs, 1.0)
        }
    }

    @Test
    fun fast_speedStaysEvenAtEveryRepeatRate() {
        for (every in listOf(50L, 100L, 150L, 300L, 450L)) {
            // The steady part: 60 s steps from the 15th repeat, after the smoothing has caught up.
            val steady = chaseFrames(every, holdMs = 500L + every * 60).filter { it.first > 500L + every * 30 }
            val lo = steady.minOf { it.third }
            val hi = steady.maxOf { it.third }
            assertTrue("$every ms: $lo..$hi", (hi - lo) / (hi + lo) < 0.08)
        }
    }

    @Test
    fun fast_speedChangesGentlyFromFrameToFrame() {
        for (every in listOf(50L, 150L, 450L)) {
            val frames = chaseFrames(every, holdMs = 8_000L, afterMs = 3_000L)
            val top = frames.maxOf { it.third }
            frames.zipWithNext().forEach { (a, b) ->
                assertTrue("$every ms at ${b.first}: ${a.third} -> ${b.third}", abs(b.third - a.third) <= top * 0.1)
            }
        }
    }

    @Test
    fun fast_comesToRestSmoothlyAfterTheLastRepeat() {
        val frames = chaseFrames(50L, holdMs = 4_000L, afterMs = 2_000L)
        val after = frames.filter { it.first > 4_000L }
        after.zipWithNext().forEach { (a, b) -> assertTrue(b.third <= a.third + 1e-9) }
        assertTrue(after.last().third < 1.0)
    }

    @Test
    fun fast_crossesTheFilmAboutAsQuicklyAsTheStepsThemselves() {
        val frames = chaseFrames(50L, holdMs = 10_000L)
        val at = frames.first { it.second >= 610_000.0 + 3_600_000.0 }.first / 1000.0
        assertTrue("one hour after $at s", at in 3.7..4.5)
    }

    @Test
    fun fast_backwardsStopsAtTheStart() {
        val frames = chaseFrames(50L, holdMs = 3_000L, afterMs = 2_000L, forward = false)
        assertTrue(frames.all { it.second >= 0.0 })
        assertEquals(0.0, frames.last().second, 1.0)
    }

    @Test
    fun fast_learnsTheRepeatInterval() {
        val chase = PlayerScrubRates.StepChase(0.0, twoHours.toDouble())
        var t = 500L
        repeat(20) {
            chase.repeat(t, true)
            t += 200L
        }
        assertEquals(200.0, chase.intervalMs, 1.0)
    }
}
