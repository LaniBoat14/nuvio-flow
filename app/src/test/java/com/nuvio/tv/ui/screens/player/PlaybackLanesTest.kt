package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackLanesTest {
    private val mb = 1024L * 1024L

    @After fun tearDown() = PlaybackLanes.clear()

    @Test fun `automatic speed-up shows the count, chunk size and auto`() {
        assertEquals("3 × 8 MB · auto", PlaybackLanes(3, 8 * mb, auto = true).label)
    }

    @Test fun `extra connections set by hand leave out auto`() {
        assertEquals("2 × 8 MB", PlaybackLanes(2, 8 * mb, auto = false).label)
    }

    @Test fun `one connection keeps the line unchanged`() {
        assertNull(PlaybackLanes(1, 8 * mb, auto = true).label)
        assertNull(PlaybackLanes(1, 16 * mb, auto = false).label)
        assertNull(PlaybackLanes(0, 8 * mb, auto = false).label)
    }

    @Test fun `unknown chunk size shows nothing`() {
        assertNull(PlaybackLanes(4, 0L, auto = false).label)
    }

    @Test fun `chunk sizes under a megabyte are shown in kilobytes`() {
        assertEquals("512 KB", PlaybackLanes.chunkSizeLabel(512 * 1024L))
        assertEquals("4 × 256 KB", PlaybackLanes(4, 256 * 1024L, auto = false).label)
    }

    @Test fun `whole and part megabytes`() {
        assertEquals("1 MB", PlaybackLanes.chunkSizeLabel(mb))
        assertEquals("16 MB", PlaybackLanes.chunkSizeLabel(16 * mb))
        assertEquals("1.5 MB", PlaybackLanes.chunkSizeLabel(1536 * 1024L))
    }

    @Test fun `connections row shows the downloads and chunk size on the chunk path`() {
        assertEquals("3 × 8 MB · auto", PlaybackLanes.connectionsText(PlaybackLanes(3, 8 * mb, auto = true)))
        assertEquals("2 × 8 MB", PlaybackLanes.connectionsText(PlaybackLanes(2, 8 * mb, auto = false)))
        assertEquals("1 × 8 MB · auto", PlaybackLanes.connectionsText(PlaybackLanes(1, 8 * mb, auto = true)))
        assertEquals("1 × 8 MB", PlaybackLanes.connectionsText(PlaybackLanes(1, 8 * mb, auto = false)))
        assertEquals("4 × 512 KB", PlaybackLanes.connectionsText(PlaybackLanes(4, 512 * 1024L, auto = false)))
    }

    @Test fun `connections row off the chunk path is a single stream`() {
        assertEquals("1 · single stream", PlaybackLanes.connectionsText(null))
        assertEquals("1 · single stream", PlaybackLanes.connectionsText(PlaybackLanes(1, 0L, auto = false)))
    }

    @Test fun `speed row gains the downloads only when there is more than one`() {
        assertEquals("84.2 Mbit/s · 3 × 8 MB · auto", PlaybackLanes.speedText("84.2 Mbit/s", PlaybackLanes(3, 8 * mb, auto = true)))
        assertEquals("84.2 Mbit/s · 2 × 8 MB", PlaybackLanes.speedText("84.2 Mbit/s", PlaybackLanes(2, 8 * mb, auto = false)))
        assertEquals("84.2 Mbit/s", PlaybackLanes.speedText("84.2 Mbit/s", PlaybackLanes(1, 8 * mb, auto = true)))
        assertEquals("Measuring", PlaybackLanes.speedText("Measuring", null))
    }

    @Test fun `settings by hand are reported until cleared`() {
        PlaybackLanes.useManual(2, 8 * mb)
        assertEquals("2 × 8 MB", PlaybackLanes.current()?.label)
        PlaybackLanes.clear()
        assertNull(PlaybackLanes.current())
    }

    @Test fun `the requests row shows requests, budget, rate and wait`() {
        assertEquals("6 of 8 · 31.0 Mbit/s each · first byte 330 ms",
            PlaybackLanes.requestsText(AutoSpeedup.Hud(6, 8, 3_875_000L, 330L)))
        assertEquals("1 of 8", PlaybackLanes.requestsText(AutoSpeedup.Hud(1, 8, 0L, -1L)))
        assertNull(PlaybackLanes.requestsText(null))
        assertEquals("0 of 5 · first byte 0 ms", PlaybackLanes.requestsText(AutoSpeedup.Hud(0, 5, 0L, 0L)))
    }
}
