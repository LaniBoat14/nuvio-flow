package com.nuvio.tv.ui.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcQualityStepsTest {

    private class Run(val steps: ArcQualitySteps) {
        var nowNanos = 0L
        val taken = mutableListOf<Pair<Long, ArcQualitySteps.Step>>()

        fun frames(count: Int, intervalMs: Float, drawMs: Float) {
            repeat(count) {
                steps.onDraw(drawMs)
                nowNanos += (intervalMs * 1_000_000).toLong()
                val step = steps.onFrame(intervalMs, nowNanos)
                if (step != ArcQualitySteps.Step.None) taken += nowNanos to step
            }
        }
    }

    @Test
    fun `start-up burst followed by light drawing keeps full quality`() {
        val run = Run(ArcQualitySteps(startDetail = 2))
        run.frames(count = 12, intervalMs = 45f, drawMs = 30f)
        run.frames(count = 15, intervalMs = 28f, drawMs = 14f)
        run.frames(count = 300, intervalMs = 18f, drawMs = 3f)

        assertTrue(run.taken.isEmpty())
        assertEquals(2, run.steps.detail)
    }

    @Test
    fun `slow pace caused elsewhere in the app keeps full quality`() {
        val run = Run(ArcQualitySteps(startDetail = 2))
        run.frames(count = 400, intervalMs = 40f, drawMs = 4f)

        assertTrue(run.taken.isEmpty())
        assertEquals(2, run.steps.detail)
    }

    @Test
    fun `sustained expensive drawing steps down one level at a time`() {
        val run = Run(ArcQualitySteps(startDetail = 2))
        run.frames(count = 600, intervalMs = 33f, drawMs = 20f)

        assertEquals(
            listOf(ArcQualitySteps.Step.LessDetail, ArcQualitySteps.Step.LessDetail),
            run.taken.map { it.second }
        )
        val gaps = run.taken.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { it >= ArcQualitySteps.SustainNanos })
        assertTrue(run.taken.first().first >= ArcQualitySteps.StartSettleNanos)
        assertTrue(run.taken.first().first < 2_000_000_000L)
        assertTrue(run.taken.last().first < 3_700_000_000L)
    }

    @Test
    fun `a struggling box at 45 ms frames steps down while the currents still gather`() {
        val run = Run(ArcQualitySteps(startDetail = 2))
        run.frames(count = 90, intervalMs = 45f, drawMs = 20f)

        assertEquals(0, run.steps.detail)
        assertTrue(run.taken.first().first < 2_000_000_000L)
    }

    @Test
    fun `a step that brings drawing back under budget is the last one`() {
        val run = Run(ArcQualitySteps(startDetail = 2))
        run.frames(count = 60, intervalMs = 33f, drawMs = 20f)
        assertEquals(1, run.steps.detail)
        run.frames(count = 300, intervalMs = 17f, drawMs = 6f)

        assertEquals(1, run.taken.size)
        assertEquals(1, run.steps.detail)
    }

    @Test
    fun `short slow patch after warm-up does not step`() {
        val run = Run(ArcQualitySteps(startDetail = 2))
        run.frames(count = 120, intervalMs = 17f, drawMs = 3f)
        run.frames(count = 10, intervalMs = 40f, drawMs = 25f)
        run.frames(count = 200, intervalMs = 17f, drawMs = 3f)

        assertTrue(run.taken.isEmpty())
    }

    @Test
    fun `a box with little memory runs light, others at full quality`() {
        assertEquals(ArcIntroTier.Light, ArcIntroTier.forMemory(2L * 1024 * 1024 * 1024))
        assertEquals(ArcIntroTier.Light, ArcIntroTier.forMemory(1_946L * 1024 * 1024))
        assertEquals(ArcIntroTier.Full, ArcIntroTier.forMemory(2_870L * 1024 * 1024))
        assertEquals(ArcIntroTier.Full, ArcIntroTier.forMemory(4L * 1024 * 1024 * 1024))
        assertEquals(ArcIntroTier.Full, ArcIntroTier.forMemory(0L))
    }

    @Test
    fun `at the lowest detail a slow pace changes nothing`() {
        val run = Run(ArcQualitySteps(startDetail = 0))
        run.frames(count = 300, intervalMs = 33f, drawMs = 20f)

        assertTrue(run.taken.isEmpty())
        assertEquals(0, run.steps.detail)
    }
}
