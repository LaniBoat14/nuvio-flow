package com.nuvio.tv.ui.brand

import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArcPaletteTest {
    private val samples = intArrayOf(
        0xFF2878FF.toInt(), 0xFFB43CE6.toInt(), 0xFFFAFAFA.toInt(), 0xFF050505.toInt(), 0x805AC8FF.toInt()
    )

    private fun assertMapped(palette: ArcPalette, vararg expectedRgb: Int) {
        samples.forEachIndexed { i, pixel ->
            val out = palette.map(pixel)
            assertEquals("alpha $i", pixel ushr 24, out ushr 24)
            for (shift in intArrayOf(16, 8, 0)) {
                val want = (expectedRgb[i] shr shift) and 0xFF
                val got = (out shr shift) and 0xFF
                assertTrue("pixel $i channel $shift: $got vs $want", abs(want - got) <= 1)
            }
        }
    }

    @Test
    fun matchesTheBrandKitColourMapping() {
        assertMapped(ArcPalette.Crimson, 0xF2726B, 0xB44047, 0xFAF3F2, 0x050505, 0xFA9F99)
        assertMapped(ArcPalette.Glass, 0xE5EAEE, 0xA5B5BD, 0xF9F9FA, 0x050505, 0xF0F3F6)
    }

    @Test
    fun darkCustomColoursAreLiftedBeforeMapping() {
        val palette = ArcPalette.custom(CustomThemeColors(0x200010, 0xEC70A9, 0xFFB37A))
        assertMapped(palette, 0xB2678A, 0xDF988D, 0xEFEDEE, 0x050505, 0x9F7589)
    }

    @Test
    fun originalLeavesPixelsUntouched() {
        samples.forEach { assertEquals(it, ArcPalette.Original.map(it)) }
    }

    @Test
    fun everyThemeHasItsOwnPalette() {
        val palettes = AppTheme.entries.map { ArcPalette.forTheme(it) }
        palettes.forEach { assertFalse(it.isOriginal) }
        assertEquals(AppTheme.entries.size, palettes.map { it.key }.toSet().size)
    }

    @Test
    fun customPaletteFollowsTheChosenColours() {
        val a = ArcPalette.forTheme(AppTheme.CUSTOM, CustomThemeColors(0x112233, 0x445566, 0x778899))
        val b = ArcPalette.forTheme(AppTheme.CUSTOM, CustomThemeColors(0x112233, 0x445566, 0x778899))
        val c = ArcPalette.forTheme(AppTheme.CUSTOM, CustomThemeColors.Default)
        assertEquals(a, b)
        assertFalse(a == c)
    }
}
