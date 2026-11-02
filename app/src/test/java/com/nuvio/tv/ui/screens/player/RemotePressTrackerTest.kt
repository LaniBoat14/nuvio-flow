package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import com.nuvio.tv.data.local.RemoteButtonBinding
import com.nuvio.tv.data.local.RemoteButtonJob
import com.nuvio.tv.data.local.RemoteKeyJobs
import com.nuvio.tv.ui.screens.player.RemotePressTracker.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePressTrackerTest {
    private val menu = KeyEvent.KEYCODE_MENU
    private val info = KeyEvent.KEYCODE_INFO
    private val jobs = RemoteKeyJobs(
        press = RemoteButtonBinding(RemoteButtonJob.STATS),
        hold = RemoteButtonBinding(RemoteButtonJob.SOURCES)
    )

    private fun tracker(downAt: Long = 1_000L) = RemotePressTracker(holdMs = 500L).also { it.start(menu, downAt, jobs) }

    private fun RemotePressTracker.down(repeat: Int, heldMs: Long, key: Int = menu) =
        onKey(key, KeyEvent.ACTION_DOWN, repeat, heldMs)

    private fun RemotePressTracker.up(heldMs: Long, key: Int = menu) = onKey(key, KeyEvent.ACTION_UP, 0, heldMs)

    @Test
    fun `a short press fires on the release only`() {
        val t = tracker()
        assertEquals(jobs, t.jobs)
        assertEquals(Outcome.PRESS, t.up(120L))
        assertFalse(t.isTracking(menu))
        assertNull(t.jobs)
    }

    @Test
    fun `a hold fires once on the first repeat past the threshold and the release does nothing`() {
        val t = tracker()
        assertEquals(Outcome.WAITING, t.down(1, 300L))
        assertEquals(Outcome.HOLD, t.down(2, 500L))
        assertEquals(Outcome.HOLD_REPEAT, t.down(3, 550L))
        assertEquals(Outcome.HOLD_REPEAT, t.down(4, 600L))
        assertEquals(Outcome.RELEASED, t.up(650L))
        assertFalse(t.isTracking(menu))
    }

    @Test
    fun `a remote without repeats fires the hold on a long release`() {
        val t = tracker()
        assertEquals(Outcome.HOLD, t.up(900L))
        assertFalse(t.isTracking(menu))
    }

    @Test
    fun `the hold time counts from the first key down, not from the events' own down time`() {
        // A TV remote over HDMI-CEC stamps every repeat with downTime == eventTime.
        val t = tracker(downAt = 10_000L)
        fun heldFromCec(eventTime: Long) = eventTime - (10_000L)
        assertEquals(Outcome.WAITING, t.down(1, heldFromCec(10_200L)))
        assertEquals(Outcome.WAITING, t.down(2, heldFromCec(10_400L)))
        assertEquals(Outcome.HOLD, t.down(3, heldFromCec(10_520L)))
        assertEquals(Outcome.RELEASED, t.up(heldFromCec(10_700L)))
    }

    @Test
    fun `two quick presses are two presses`() {
        val t = tracker()
        assertEquals(Outcome.PRESS, t.up(100L))
        t.start(menu, 2_000L, jobs)
        assertEquals(Outcome.WAITING, t.down(1, 200L))
        assertEquals(Outcome.PRESS, t.up(250L))
    }

    @Test
    fun `other keys are not the tracker's business`() {
        val t = tracker()
        assertEquals(Outcome.NONE, t.down(1, 600L, key = info))
        assertEquals(Outcome.NONE, t.up(600L, key = info))
        assertTrue(t.isTracking(menu))
        assertEquals(Outcome.HOLD, t.down(1, 600L))
    }

    @Test
    fun `a second hold button swallows what is left of the first until it is let go`() {
        val t = tracker()
        t.start(info, 1_200L, jobs)
        assertTrue(t.isTracking(menu))
        assertTrue(t.isTracking(info))
        assertEquals(Outcome.WAITING, t.down(3, 700L, key = menu))
        assertEquals(Outcome.WAITING, t.up(800L, key = menu))
        assertFalse(t.isTracking(menu))
        assertEquals(Outcome.PRESS, t.up(100L, key = info))
    }

    @Test
    fun `a fresh key down of the tracked key forgets its earlier press`() {
        val t = tracker()
        assertEquals(Outcome.NONE, t.down(0, 0L))
        t.forget(info)
        assertTrue(t.isTracking(menu))
        t.forget(menu)
        assertFalse(t.isTracking(menu))
        assertNull(t.jobs)
        assertEquals(Outcome.NONE, t.up(100L))
    }
}
