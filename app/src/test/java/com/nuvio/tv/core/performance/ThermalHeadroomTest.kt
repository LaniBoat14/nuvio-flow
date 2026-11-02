package com.nuvio.tv.core.performance

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThermalHeadroomTest {
    @Before fun setUp() = ThermalHeadroom.resetForTest()
    @After fun tearDown() = ThermalHeadroom.resetForTest()

    private var calls = 0

    private fun readAt(nowMs: Long, answer: Float): Float =
        ThermalHeadroom.readThrottled(nowMs) { calls++; answer }

    @Test fun latchesAfterConsecutiveFailures() {
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD - 1) { ThermalHeadroom.record(Float.NaN) }
        assertFalse(ThermalHeadroom.halDead)
        ThermalHeadroom.record(Float.NaN)
        assertTrue(ThermalHeadroom.halDead)
    }

    @Test fun aRealAnswerResetsTheCount() {
        repeat(30) {
            ThermalHeadroom.record(Float.NaN)
            ThermalHeadroom.record(0.4f)
        }
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD - 1) { ThermalHeadroom.record(Float.NaN) }
        assertFalse(ThermalHeadroom.halDead)
    }

    @Test fun aBoxThatHasAnsweredIsNeverLatched() {
        ThermalHeadroom.record(0.7f)
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD * 5) { ThermalHeadroom.record(Float.NaN) }
        assertFalse(ThermalHeadroom.halDead)
    }

    @Test fun negativeAnswersCountAsFailures() {
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD) { ThermalHeadroom.record(-1f) }
        assertTrue(ThermalHeadroom.halDead)
    }

    @Test fun noCallOnceLatched() {
        repeat(ThermalHeadroom.FAILURES_BEFORE_DEAD) { ThermalHeadroom.record(Float.NaN) }
        assertTrue(ThermalHeadroom.read(null, 0).isNaN())
        assertTrue(readAt(1_000_000L, 0.5f).isNaN())
        assertEquals(0, calls)
    }

    @Test fun asksAtMostOncePerIntervalAndSharesTheAnswer() {
        assertEquals(0.6f, readAt(0L, 0.6f))
        for (t in 1_000L until ThermalHeadroom.MIN_INTERVAL_MS step 1_000L) {
            assertEquals(0.6f, readAt(t, 0.9f))
        }
        assertEquals(1, calls)
        assertEquals(0.7f, readAt(ThermalHeadroom.MIN_INTERVAL_MS, 0.7f))
        assertEquals(2, calls)
    }

    @Test fun oneMissedReadIsRetriedAtTheNextInterval() {
        readAt(0L, 0.6f)
        assertTrue(readAt(10_000L, Float.NaN).isNaN())
        assertTrue(readAt(15_000L, 0.6f).isNaN())
        assertEquals(0.61f, readAt(20_000L, 0.61f))
        assertEquals(3, calls)
    }

    @Test fun twoFailuresInARowBackOff() {
        readAt(0L, 0.6f)
        readAt(10_000L, Float.NaN)
        readAt(20_000L, Float.NaN)
        assertEquals(3, calls)
        for (t in 30_000L until 20_000L + ThermalHeadroom.FAILURE_BACKOFF_MS step 1_000L) {
            assertTrue(readAt(t, 0.6f).isNaN())
        }
        assertEquals(3, calls)
        assertEquals(0.62f, readAt(20_000L + ThermalHeadroom.FAILURE_BACKOFF_MS, 0.62f))
        assertEquals(0.62f, readAt(20_000L + ThermalHeadroom.FAILURE_BACKOFF_MS + ThermalHeadroom.MIN_INTERVAL_MS - 1, Float.NaN))
        assertEquals(4, calls)
        assertFalse(ThermalHeadroom.halDead)
    }

    @Test fun aBoxThatNeverAnswersStopsAfterThreeReadsInAboutAMinute() {
        readAt(0L, Float.NaN)
        readAt(1_000L, Float.NaN)
        readAt(10_000L, Float.NaN)
        readAt(30_000L, Float.NaN)
        readAt(70_000L, Float.NaN)
        assertTrue(ThermalHeadroom.halDead)
        assertEquals(3, calls)
        readAt(10_000_000L, 0.5f)
        assertEquals(3, calls)
    }

    @Test fun aThrowingCallCountsAsAFailure() {
        assertTrue(ThermalHeadroom.readThrottled(0L) { throw SecurityException("denied") }.isNaN())
        assertTrue(ThermalHeadroom.readThrottled(10_000L) { throw IllegalStateException("hal") }.isNaN())
        assertTrue(ThermalHeadroom.readThrottled(30_000L) { 0.5f }.isNaN())
    }
}
