package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerClockShadowTest {

    @Test
    fun `shadow at 1080p is 1 dp down-right with a 1 dp blur`() {
        val shadow = playerClockShadow(2f)
        assertEquals(Offset(2f, 2f), shadow.offset)
        assertEquals(2f, shadow.blurRadius, 0f)
        assertEquals(Color(0xB3000000), shadow.color)
    }

    @Test
    fun `shadow grows with the UI scale`() {
        val small = playerClockShadow(2f)
        val large = playerClockShadow(2.5f)
        assertEquals(small.blurRadius * 1.25f, large.blurRadius, 0.001f)
        assertEquals(small.offset.x * 1.25f, large.offset.x, 0.001f)
        assertEquals(small.offset.y * 1.25f, large.offset.y, 0.001f)
    }

    @Test
    fun `clock and ends at use the larger readable sizes`() {
        assertEquals(15f, PLAYER_CLOCK_FONT_SIZE.value, 0f)
        assertEquals(11.5f, PLAYER_ENDS_AT_FONT_SIZE.value, 0f)
        assertEquals(0.90f, PLAYER_ENDS_AT_ALPHA, 0f)
    }
}
