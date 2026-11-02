package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class HdrOverlayDimTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `white text ends where bright subtitles are capped`() {
        val white = argb(255, 255, 255, 255)
        assertEquals(capSubtitleHighlight(white), hdrOverlayColor(white))
        assertEquals(argb(255, 180, 180, 180), hdrOverlayColor(white))
    }

    @Test
    fun `glass keeps its opacity instead of fading`() {
        val glass = argb(51, 20, 20, 24)
        assertEquals(51, hdrOverlayColor(glass) ushr 24)
    }

    @Test
    fun `black stays black`() {
        assertEquals(argb(200, 0, 0, 0), hdrOverlayColor(argb(200, 0, 0, 0)))
    }

    @Test
    fun `accent keeps its hue`() {
        val result = hdrOverlayColor(argb(255, 255, 102, 0))
        assertEquals(argb(255, 180, 72, 0), result)
    }

    @Test
    fun `translucent white text keeps its alpha`() {
        val result = hdrOverlayColor(argb(178, 255, 255, 255))
        assertEquals(argb(178, 180, 180, 180), result)
    }
}
