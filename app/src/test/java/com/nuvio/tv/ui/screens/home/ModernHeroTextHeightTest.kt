package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHeroTextHeightTest {
    private val portraitScale = 0.84f * 1.08f

    private fun portraitCardHeight(widthDp: Int) = (widthDp * 3 / 2).dp * portraitScale

    private fun landscapeCardHeight(widthDp: Int) = widthDp.dp * 1.24f * 1.34f / 1.77f

    // Logo slot, a metadata row and a badge row, three gaps, then the description lines.
    private fun heroBlock(portrait: Boolean, lines: Int): Dp {
        val gap = if (portrait) 8.dp * 0.92f else 8.dp
        val line = if (portrait) 18.dp else 20.dp
        return 100.dp + 16.dp + 24.dp + gap * 3 + line * lines
    }

    private fun room(screen: Dp, fraction: Float, card: Dp) =
        modernHeroTextMaxHeight(screen, modernRowsViewportHeight(screen, fraction, card))

    @Test
    fun `four lines fit at 1080p with the usual card sizes`() {
        for (width in listOf(104, 112, 120, 126, 134, 140)) {
            assertTrue(room(540.dp, 0.52f, portraitCardHeight(width)) >= heroBlock(portrait = true, lines = 4))
        }
        for (width in listOf(104, 112, 120, 126, 134, 140, 176)) {
            assertTrue(room(540.dp, 0.49f, landscapeCardHeight(width)) >= heroBlock(portrait = false, lines = 4))
        }
    }

    @Test
    fun `smaller ui scales keep four lines`() {
        for (screen in listOf(568.dp, 600.dp, 635.dp, 720.dp)) {
            assertTrue(room(screen, 0.52f, portraitCardHeight(176)) >= heroBlock(portrait = true, lines = 4))
        }
    }

    @Test
    fun `extra large cards still leave three lines`() {
        val room = room(540.dp, 0.52f, portraitCardHeight(176))
        assertTrue(room >= heroBlock(portrait = true, lines = 3))
        assertTrue(room < heroBlock(portrait = true, lines = 4))
    }

    @Test
    fun `the block stays a few dp off the top edge`() {
        val rows = modernRowsViewportHeight(540.dp, 0.52f, portraitCardHeight(126))
        assertEquals((540.dp - rows - 16.dp - 4.dp).value, modernHeroTextMaxHeight(540.dp, rows).value, 0.01f)
    }

    @Test
    fun `no room gives zero rather than a negative height`() {
        assertEquals(0f, modernHeroTextMaxHeight(100.dp, 120.dp).value, 0.01f)
    }
}
