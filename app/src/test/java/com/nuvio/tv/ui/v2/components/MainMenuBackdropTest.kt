package com.nuvio.tv.ui.v2.components

import org.junit.Assert.*
import org.junit.Test

class MainMenuBackdropTest {
    @Test fun `on a dark picture the shade follows glass transparency`() {
        val frosted = mainMenuShadeAlpha(0.1f, 40)
        val balanced = mainMenuShadeAlpha(0.1f, 60)
        val clear = mainMenuShadeAlpha(0.1f, 85)
        assertTrue(frosted > balanced && balanced > clear)
        assertEquals(0.39f, balanced, 0.001f)
    }

    @Test fun `on a bright picture the shade keeps white text readable even with Clear`() {
        for (transparency in listOf(40, 60, 85, 100)) {
            val alpha = mainMenuShadeAlpha(0.85f, transparency)
            assertTrue("t=$transparency a=$alpha", 0.85f * (1f - alpha) <= 0.2001f)
        }
    }

    @Test fun `the shade never turns into a solid panel`() {
        for (luminance in listOf(0.5f, 1f, 5f)) for (transparency in listOf(0, 60, 100)) {
            assertTrue(mainMenuShadeAlpha(luminance, transparency) <= 0.85f)
        }
        assertTrue(mainMenuShadeAlpha(0f, 150) >= 0.15f)
    }

    @Test fun `a picture the size of the screen gives the left part under the menu`() {
        val region = requireNotNull(menuRegionInImage(96, 54, 1920f, 1080f, 0.25f))
        assertEquals(0, region.left)
        assertEquals(24, region.right)
        assertEquals(2, region.top)
        assertEquals(43, region.bottom)
    }

    @Test fun `a wide picture is cropped from the left because it is aligned to the right`() {
        // 2.4:1 at 1080 high is 2592 wide: 672 px hang off the left edge.
        val region = requireNotNull(menuRegionInImage(240, 100, 1920f, 1080f, 0.25f))
        assertEquals(62, region.left)
        assertEquals(106, region.right)
    }

    @Test fun `right to left reads the right edge`() {
        val region = requireNotNull(menuRegionInImage(96, 54, 1920f, 1080f, 0.25f, rtl = true))
        assertEquals(72, region.left)
        assertEquals(96, region.right)
    }

    @Test fun `right to left on a wide picture reads the strip that is really on screen`() {
        // In RTL the picture is anchored left, so the overhang is on the right.
        val region = requireNotNull(menuRegionInImage(240, 100, 1920f, 1080f, 0.25f, rtl = true))
        assertEquals(133, region.left)
        assertEquals(177, region.right)
    }

    @Test fun `empty sizes give no region`() {
        assertNull(menuRegionInImage(0, 54, 1920f, 1080f, 0.25f))
        assertNull(menuRegionInImage(96, 54, 1920f, 1080f, 0f))
    }

    @Test fun `bright spots behind the letters count, not the average`() {
        val width = 10
        val pixels = IntArray(100) { if (it % 10 == 9) WHITE else BLACK }
        val luminance = requireNotNull(menuRegionLuminance(pixels, width, MenuRegion(0, 0, 10, 10)))
        assertEquals(0f, luminance, 0.001f)
        val brighter = IntArray(100) { if (it % 10 >= 8) WHITE else BLACK }
        assertEquals(1f, requireNotNull(menuRegionLuminance(brighter, width, MenuRegion(0, 0, 10, 10))), 0.001f)
    }

    @Test fun `see-through pixels are ignored`() {
        val pixels = IntArray(4) { 0x00FFFFFF }
        assertNull(menuRegionLuminance(pixels, 2, MenuRegion(0, 0, 2, 2)))
    }

    @Test fun `the hero scrim is unchanged while the menu is closed`() {
        val stops = listOf(0f to 1f, 0.5f to 0.8f, 1f to 0f)
        assertEquals(stops, openMenuScrimStops(stops, 1000f, 200f, 0f))
    }

    @Test fun `with the menu open the scrim clears under the rows and is back before the page starts`() {
        val stops = listOf(0f to 1f, 0.22f to 0.9f, 0.46f to 0.8f, 0.76f to 0.42f, 1f to 0f)
        val open = openMenuScrimStops(stops, 1000f, 200f, 1f)
        open.filter { it.first <= 0.14f }.forEach { assertEquals(0f, it.second, 0.0001f) }
        val pageStart = open.first { it.first >= 0.19f }
        assertEquals(1f + (0.9f - 1f) * pageStart.first / 0.22f, pageStart.second, 0.001f)
        assertTrue(open.filter { it.first >= 0.19f }.all { it.second > 0f || it.first == 1f })
        assertEquals(0f, open.last().second, 0.0001f)
        assertEquals(open.map { it.first }.sorted(), open.map { it.first })
    }

    @Test fun `halfway open keeps half the scrim under the menu`() {
        val open = openMenuScrimStops(listOf(0f to 1f, 1f to 0f), 1000f, 200f, 0.5f)
        assertEquals(0.5f, open.first().second, 0.0001f)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
