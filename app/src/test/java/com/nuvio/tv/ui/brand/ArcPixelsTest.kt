package com.nuvio.tv.ui.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcPixelsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `halving averages each 2x2 block exactly`() {
        val white = argb(255, 255, 255, 255)
        val black = argb(255, 0, 0, 0)
        val src = intArrayOf(white, black, white, black, white, black, white, black)
        val out = ArcPixels.areaResample(src, 4, 2, 2, 1)
        out.forEach { assertEquals(argb(255, 128, 128, 128), it) }
    }

    @Test
    fun `transparent pixels do not darken the colour next to them`() {
        val red = argb(255, 255, 0, 0)
        val src = intArrayOf(red, 0, red, 0)
        val out = ArcPixels.areaResample(src, 2, 2, 1, 1)
        assertEquals(argb(128, 255, 0, 0), out[0])
    }

    @Test
    fun `sharpening lifts an edge and leaves flat areas and transparency alone`() {
        val w = 5
        val h = 3
        val px = IntArray(w * h) { i -> if (i % w < 2) argb(255, 60, 60, 60) else argb(255, 200, 200, 200) }
        px[0] = 0
        val flat = px[1 * w + 0]
        ArcPixels.sharpen(px, w, h)
        assertTrue(((px[1 * w + 1] shr 16) and 0xFF) < 60)
        assertTrue(((px[1 * w + 2] shr 16) and 0xFF) > 200)
        assertEquals(flat, px[1 * w + 0])
        assertEquals(0, px[0])
    }
}
