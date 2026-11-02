package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StaggeredOverlayTimelineTest {

    private fun animatorScale(factor: Float) = object : MotionDurationScale {
        override val scaleFactor = factor
    }

    @Test
    fun `normal speed matches the 1x sequence of about seven and a half seconds`() {
        val timeline = StaggeredOverlayTimeline(count = 2, fadeScale = 1f)
        assertEquals(1_260L, timeline.shownAt)
        assertEquals(6_260L, timeline.holdEnd)
        assertEquals(7_480L, timeline.totalMs)
    }

    @Test
    fun `a slow animator scale cannot stretch the overlay`() {
        assertEquals(7_480L, StaggeredOverlayTimeline(count = 2, fadeScale = 10f).totalMs)
        assertEquals(8_950L, StaggeredOverlayTimeline(count = 5, fadeScale = 10f).totalMs)
    }

    @Test
    fun `animations off drops the fades but keeps the five second hold`() {
        val timeline = StaggeredOverlayTimeline(count = 2, fadeScale = 0f)
        assertEquals(5_580L, timeline.totalMs)
        assertEquals(5_000L, timeline.holdEnd - timeline.shownAt)
        val first = timeline.frameAt(0L)
        assertEquals(1f, first.containerAlpha, 0f)
        assertEquals(1f, first.lineFraction, 0f)
        assertEquals(listOf(0f, 0f), first.itemAlphas)
        assertEquals(listOf(1f, 1f), timeline.frameAt(160L).itemAlphas)
    }

    @Test
    fun `faster animations shorten only the fades`() {
        val timeline = StaggeredOverlayTimeline(count = 2, fadeScale = 0.5f)
        assertEquals(5_000L, timeline.holdEnd - timeline.shownAt)
        assertEquals(6_530L, timeline.totalMs)
    }

    @Test
    fun `frames follow the sequence in and out`() {
        val timeline = StaggeredOverlayTimeline(count = 2, fadeScale = 1f)
        assertEquals(0f, timeline.frameAt(0L).containerAlpha, 0f)
        assertEquals(FastOutSlowInEasing.transform(0.5f), timeline.frameAt(150L).containerAlpha, 0.0001f)
        val lineHalfWay = timeline.frameAt(500L)
        assertTrue(lineHalfWay.lineFraction > 0f && lineHalfWay.lineFraction < 1f)
        assertEquals(listOf(0f, 0f), lineHalfWay.itemAlphas)
        assertEquals(listOf(1f, 0f), timeline.frameAt(1_000L).itemAlphas)

        assertEquals(StaggeredOverlayFrame(1f, 1f, listOf(1f, 1f)), timeline.frameAt(3_000L))

        assertEquals(listOf(1f, 0f), timeline.frameAt(6_500L).itemAlphas)
        assertEquals(1f, timeline.frameAt(6_500L).containerAlpha, 0f)
        assertEquals(0f, timeline.frameAt(7_080L).lineFraction, 0.0001f)
        assertEquals(1f - FastOutSlowInEasing.transform(0.5f), timeline.frameAt(7_380L).containerAlpha, 0.0001f)
    }

    private fun TestScope.play(
        frames: BroadcastFrameClock,
        scale: Float,
        seen: MutableList<StaggeredOverlayFrame>,
        alreadyShown: Boolean = false,
    ): Job {
        val job = launch(frames + animatorScale(scale)) {
            runStaggeredOverlay(count = 2, alreadyShown = alreadyShown) { seen += it }
        }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        return job
    }

    private fun TestScope.frameAt(frames: BroadcastFrameClock, ms: Long) {
        advanceUntilIdle()
        frames.sendFrame(ms * 1_000_000L)
        runCurrent()
    }

    @Test
    fun `at ten times slower animations the overlay still ends on real time`() = runTest {
        val frames = BroadcastFrameClock()
        val seen = mutableListOf<StaggeredOverlayFrame>()
        val overlay = play(frames, scale = 10f, seen = seen)

        frameAt(frames, 3_000L)
        assertEquals(StaggeredOverlayFrame(1f, 1f, listOf(1f, 1f)), seen.last())

        frameAt(frames, 7_479L)
        assertTrue(overlay.isActive)

        frameAt(frames, 7_480L)
        assertTrue(overlay.isCompleted)
        assertEquals(StaggeredOverlayFrame.hidden(2), seen.last())
    }

    @Test
    fun `with animations off the overlay shows at once and leaves after the hold`() = runTest {
        val frames = BroadcastFrameClock()
        val seen = mutableListOf<StaggeredOverlayFrame>()
        val overlay = play(frames, scale = 0f, seen = seen)

        frameAt(frames, 16L)
        assertEquals(1f, seen.last().containerAlpha, 0f)

        frameAt(frames, 5_579L)
        assertTrue(overlay.isActive)
        frameAt(frames, 5_580L)
        assertTrue(overlay.isCompleted)
    }

    @Test
    fun `new content while shown starts fully shown with a fresh hold`() = runTest {
        val frames = BroadcastFrameClock()
        val seen = mutableListOf<StaggeredOverlayFrame>()
        val overlay = play(frames, scale = 1f, seen = seen, alreadyShown = true)

        frameAt(frames, 16L)
        assertEquals(StaggeredOverlayFrame(1f, 1f, listOf(1f, 1f)), seen.last())

        frameAt(frames, 6_219L)
        assertTrue(overlay.isActive)
        frameAt(frames, 6_220L)
        assertTrue(overlay.isCompleted)
    }
}
