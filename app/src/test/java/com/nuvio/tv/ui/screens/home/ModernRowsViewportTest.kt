package com.nuvio.tv.ui.screens.home

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernRowsViewportTest {
    private val portraitScale = 0.84f * 1.08f

    private fun portraitCardHeight(widthDp: Int) = (widthDp * 3 / 2).dp * portraitScale

    @Test
    fun `the existing widths keep the usual share of the screen`() {
        for (screen in listOf(540.dp, 470.dp, 720.dp, 626.dp)) {
            for (width in listOf(104, 112, 120, 126, 134, 140)) {
                assertEquals(
                    (screen * 0.52f).value,
                    modernRowsViewportHeight(screen, 0.52f, portraitCardHeight(width)).value,
                    0.01f
                )
            }
        }
    }

    @Test
    fun `extra large cards get room below the row header`() {
        val card = portraitCardHeight(176)
        assertEquals((40.dp + card + 8.dp).value, modernRowsViewportHeight(540.dp, 0.52f, card).value, 0.01f)
        val at115 = modernRowsViewportHeight(470.dp, 0.52f, card)
        assertEquals((470.dp * 0.6f).value, at115.value, 0.01f)
        assertTrue(at115 >= 40.dp + card)
        assertEquals((720.dp * 0.52f).value, modernRowsViewportHeight(720.dp, 0.52f, card).value, 0.01f)
    }

    @Test
    fun `the rows never take more than six tenths of the screen`() {
        assertEquals(
            (470.dp * 0.6f).value,
            modernRowsViewportHeight(470.dp, 0.52f, 400.dp).value,
            0.01f
        )
    }
}
