package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LibassRendererWrapTest {

    private fun renderAt(presentationTimeUs: Long, shiftUs: Long): Pair<Long, Long> {
        var rendered: Pair<Long, Long>? = null
        val callback = shiftedAssVideoFrameCallback({ shiftUs }) { timeUs, releaseNs -> rendered = timeUs to releaseNs }
        callback(presentationTimeUs, 42L)
        return requireNotNull(rendered)
    }

    @Test
    fun `a subtitle delay moves libass subtitles later`() {
        assertEquals(8_000_000L to 42L, renderAt(10_000_000L, shiftUs = -2_000_000L))
    }

    @Test
    fun `without a delay libass gets the frame time`() {
        assertEquals(10_000_000L to 42L, renderAt(10_000_000L, shiftUs = 0L))
    }

    @Test
    fun `the shift is read on every frame`() {
        var shiftUs = 0L
        val seen = mutableListOf<Long>()
        val callback = shiftedAssVideoFrameCallback({ shiftUs }) { timeUs, _ -> seen += timeUs }
        callback(1_000_000L, 0L)
        shiftUs = 500_000L
        callback(1_000_000L, 0L)
        assertEquals(listOf(1_000_000L, 1_500_000L), seen)
    }

    @Test
    fun `a delay before the start never gives a negative time`() {
        assertEquals(0L to 42L, renderAt(1_000_000L, shiftUs = -5_000_000L))
    }
}
