package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.BroadcastFrameClock
import com.nuvio.tv.data.repository.SkipInterval
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkipIntroVisibilityRulesTest {

    private val intro = SkipInterval(
        startTime = 10.0,
        endTime = 70.0,
        type = "intro",
        provider = "introdb",
    )

    private val intervals = listOf(intro)

    // ── Interval matching (ViewModel) ──────────────────────────────────────

    @Test
    fun `interval is inactive before start`() {
        assertNull(nextActiveSkipInterval(intervals, positionMs = 9_999L))
    }

    @Test
    fun `interval is active at start boundary`() {
        assertEquals(intro, nextActiveSkipInterval(intervals, positionMs = 10_000L))
    }

    @Test
    fun `interval stays active through mid segment`() {
        assertEquals(intro, nextActiveSkipInterval(intervals, positionMs = 40_000L))
    }

    @Test
    fun `interval stays active in the final half-second of the segment`() {
        assertNotNull(nextActiveSkipInterval(intervals, positionMs = 69_600L))
        assertEquals(intro, nextActiveSkipInterval(intervals, positionMs = 69_999L))
    }

    @Test
    fun `interval clears exactly at endTime`() {
        assertNull(nextActiveSkipInterval(intervals, positionMs = 70_000L))
        assertNull(nextActiveSkipInterval(intervals, positionMs = 120_000L))
    }

    @Test
    fun `empty intervals always resolve to null`() {
        assertNull(nextActiveSkipInterval(emptyList(), positionMs = 30_000L))
    }

    @Test
    fun `state transition clears previous active when playhead exits`() {
        val whileInside = nextActiveSkipInterval(intervals, positionMs = 30_000L)
        assertEquals(intro, whileInside)

        val afterExit = nextActiveSkipInterval(intervals, positionMs = 70_000L)
        assertNull(
            "activeSkipInterval must become null once playhead leaves the segment",
            afterExit,
        )
    }

    // ── Button visibility (SkipIntroButton) ────────────────────────────────

    @Test
    fun `button visible when interval active and not dismissed or auto-hidden`() {
        assertTrue(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = false,
                autoHidden = false,
            )
        )
    }

    @Test
    fun `button auto-hides after timeout while controls are hidden`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = false,
                autoHidden = true,
            )
        )
    }

    @Test
    fun `auto-hidden button reappears only while controls are visible`() {
        assertTrue(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = true,
                autoHidden = true,
            )
        )
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = false,
                controlsVisible = false,
                autoHidden = true,
            )
        )
    }

    @Test
    fun `manually dismissed button stays hidden until controls are shown`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = true,
                controlsVisible = false,
                autoHidden = false,
            )
        )
        assertTrue(
            isSkipIntroButtonVisible(
                hasActiveInterval = true,
                dismissed = true,
                controlsVisible = true,
                autoHidden = false,
            )
        )
    }

    @Test
    fun `no active interval means button is never visible`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = false,
                dismissed = false,
                controlsVisible = true,
                autoHidden = false,
            )
        )
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = false,
                dismissed = false,
                controlsVisible = false,
                autoHidden = false,
            )
        )
    }

    @Test
    fun `after interval clears auto-hidden state cannot keep button on screen`() {
        assertFalse(
            isSkipIntroButtonVisible(
                hasActiveInterval = false,
                dismissed = false,
                controlsVisible = false,
                autoHidden = false,
            )
        )
    }

    // ── Focus while overlays open (#2874) ─────────────────────────────────

    @Test
    fun `skip intro can accept focus when subtitle overlay is closed`() {
        assertTrue(isSkipIntroCanFocus(subtitleOverlayVisible = false))
    }

    @Test
    fun `skip intro cannot accept focus while subtitle selection overlay is open`() {
        // DPAD_DOWN past the last subtitle card must not escape onto Skip Intro.
        assertFalse(isSkipIntroCanFocus(subtitleOverlayVisible = true))
    }

    // ── Auto-hide countdown ────────────────────────────────────────────────

    @Test
    fun `auto-hide remaining duration is full timeout at progress zero`() {
        assertEquals(
            SKIP_INTRO_AUTO_HIDE_TIMEOUT_MS,
            skipIntroAutoHideRemainingMs(progress = 0f),
        )
    }

    @Test
    fun `auto-hide remaining duration halves at mid progress`() {
        assertEquals(5_000, skipIntroAutoHideRemainingMs(progress = 0.5f))
    }

    @Test
    fun `auto-hide remaining duration collapses to 1ms when progress already complete`() {
        assertEquals(1, skipIntroAutoHideRemainingMs(progress = 1f))
    }

    @Test
    fun `auto-hide progress grows with elapsed time from where it was paused`() {
        assertEquals(0f, skipIntroAutoHideProgress(startProgress = 0f, elapsedMs = 0L), 0.0001f)
        assertEquals(0.25f, skipIntroAutoHideProgress(startProgress = 0f, elapsedMs = 2_500L), 0.0001f)
        assertEquals(0.8f, skipIntroAutoHideProgress(startProgress = 0.5f, elapsedMs = 3_000L), 0.0001f)
        assertEquals(1f, skipIntroAutoHideProgress(startProgress = 0.5f, elapsedMs = 9_000L), 0.0001f)
    }

    @Test
    fun `auto-hide countdown ends after the full timeout of frame time`() = runTest {
        val frames = BroadcastFrameClock()
        val steps = mutableListOf<Float>()
        val countdown = launch(frames) { runSkipIntroAutoHide(startProgress = 0f) { steps += it } }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()

        frames.sendFrame(16_000_000L)
        runCurrent()
        assertTrue(countdown.isActive)
        assertEquals(0.0016f, steps.last(), 0.0001f)

        frames.sendFrame(9_999_000_000L)
        runCurrent()
        assertTrue("one millisecond short must still be counting", countdown.isActive)
        assertEquals(0.9999f, steps.last(), 0.0001f)

        frames.sendFrame(10_000_000_000L)
        runCurrent()
        assertTrue(countdown.isCompleted)
        assertEquals(1f, steps.last(), 0f)
    }

    @Test
    fun `auto-hide countdown resumed half way needs only the remaining half`() = runTest {
        val frames = BroadcastFrameClock()
        val steps = mutableListOf<Float>()
        val countdown = launch(frames) { runSkipIntroAutoHide(startProgress = 0.5f) { steps += it } }
        runCurrent()
        frames.sendFrame(20_000_000_000L)
        runCurrent()

        frames.sendFrame(22_000_000_000L)
        runCurrent()
        assertTrue(countdown.isActive)
        assertEquals(0.7f, steps.last(), 0.0001f)

        frames.sendFrame(25_000_000_000L)
        runCurrent()
        assertTrue(countdown.isCompleted)
        assertEquals(1f, steps.last(), 0f)
    }

    @Test
    fun `auto-hide countdown cancelled mid way keeps the progress it reached`() = runTest {
        val frames = BroadcastFrameClock()
        var progress = 0f
        val countdown = launch(frames) { runSkipIntroAutoHide(startProgress = 0f) { progress = it } }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        frames.sendFrame(4_000_000_000L)
        runCurrent()
        countdown.cancel()
        runCurrent()

        assertEquals(0.4f, progress, 0.0001f)
        assertEquals(6_000, skipIntroAutoHideRemainingMs(progress))
    }
}
