package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleNudgeTest {

    @Test
    fun aPressMovesOneStepFromTheSavedOffset() {
        assertEquals(6, SubtitleNudge().press(savedOffset = 5, up = true, nowMs = 0L))
        assertEquals(4, SubtitleNudge().press(savedOffset = 5, up = false, nowMs = 0L))
    }

    @Test
    fun holdingKeepsMoving_atMostOneStepPerRepeatTime() {
        val nudge = SubtitleNudge()
        assertEquals(1, nudge.press(savedOffset = 0, up = true, nowMs = 1_000L))
        assertNull(nudge.repeat(up = true, nowMs = 1_050L))
        assertEquals(2, nudge.repeat(up = true, nowMs = 1_100L))
        assertEquals(3, nudge.repeat(up = true, nowMs = 1_200L))
        assertEquals(4, nudge.repeat(up = true, nowMs = 1_350L))
    }

    @Test
    fun aHoldCountsOnFromItsOwnValue_notTheSavedOneThatLags() {
        val nudge = SubtitleNudge()
        nudge.press(savedOffset = 10, up = false, nowMs = 0L)
        assertEquals(8, nudge.repeat(up = false, nowMs = 100L))
        assertEquals(7, nudge.repeat(up = false, nowMs = 200L))
    }

    @Test
    fun theOffsetStaysBetweenMinusTwentyAndFifty() {
        assertEquals(50, SubtitleNudge().press(savedOffset = 50, up = true, nowMs = 0L))
        assertEquals(-20, SubtitleNudge().press(savedOffset = -20, up = false, nowMs = 0L))
        val nudge = SubtitleNudge()
        nudge.press(savedOffset = 49, up = true, nowMs = 0L)
        assertNull(nudge.repeat(up = true, nowMs = 100L))
    }
}
