package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDroppedFramesRowTest {
    private val now = 100_000L
    private val window = PlaybackStatsThresholds.RECENT_EVENT_WINDOW_MS

    private fun row(
        dropped: Int?,
        droppedAt: Long?,
        skipped: PlaybackSkippedFrameSample?,
        presentation: PlaybackVideoPresentation = PlaybackVideoPresentation.MEDIA_CODEC
    ) = droppedFramesRow(presentation, PlaybackDroppedFrameSample(dropped, droppedAt), skipped, now)

    @Test fun `both zero is green and written like the underruns row`() {
        assertEquals(StatsRow("Dropped", "0 · skipped 0", StatsDot.GOOD),
            row(0, null, PlaybackSkippedFrameSample(0, null)))
    }

    @Test fun `a recent skip turns the dot red with no dropped frames`() {
        assertEquals(StatsRow("Dropped", "0 · skipped 14", StatsDot.BAD),
            row(0, null, PlaybackSkippedFrameSample(14, now - 5_000)))
    }

    @Test fun `older skips and drops are amber`() {
        assertEquals(StatsDot.WARN, row(0, null, PlaybackSkippedFrameSample(14, now - window - 1)).dot)
        assertEquals(StatsDot.WARN, row(3, now - window - 1, PlaybackSkippedFrameSample(0, null)).dot)
        assertEquals("3 · skipped 14", row(3, null, PlaybackSkippedFrameSample(14, null)).value)
    }

    @Test fun `a recent drop with older skips is red`() {
        assertEquals(StatsDot.BAD, row(3, now - 1_000, PlaybackSkippedFrameSample(14, now - window - 1)).dot)
    }

    @Test fun `tunnelled playback stays as before`() {
        assertEquals(StatsRow("Dropped", "0 · renderer source only (tunnelled)", StatsDot.NONE),
            row(0, null, null, PlaybackVideoPresentation.TUNNELLED))
        assertEquals(StatsRow("Dropped", "2 · renderer source only (tunnelled)", StatsDot.BAD),
            row(2, now - 1_000, null, PlaybackVideoPresentation.TUNNELLED))
    }

    @Test fun `native route and missing counters stay as before`() {
        assertEquals(StatsRow("Dropped", "Not counted on the native route", StatsDot.NONE),
            row(null, null, null, PlaybackVideoPresentation.NATIVE))
        assertEquals(StatsRow("Dropped", "Unavailable", StatsDot.NONE),
            row(null, null, null, PlaybackVideoPresentation.UNSUPPORTED))
    }

    @Test fun `without a skip count the row reads as before`() {
        assertEquals(StatsRow("Dropped", "4", StatsDot.WARN), row(4, null, null))
    }
}
