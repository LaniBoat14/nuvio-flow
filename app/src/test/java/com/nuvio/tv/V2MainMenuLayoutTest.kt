package com.nuvio.tv

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class V2MainMenuLayoutTest {
    @Test fun `rows keep their full height when there is room`() {
        // 1080p at 100 %: 540 high, 484 inside the menu's padding.
        assertEquals(54.dp, menuRowHeight(484.dp, 5))
        assertEquals(54.dp, menuRowHeight(484.dp, 6))
    }

    @Test fun `six pages at the largest UI scale shrink the rows instead of reaching the profile`() {
        // 1080p at 115 %: about 470 high, 414 inside the padding.
        assertEquals(54.dp, menuRowHeight(414.dp, 5))
        val height = menuRowHeight(414.dp, 6)
        assertEquals(45.dp, height)
        val group = height * 6 + 4.dp * 5
        assertEquals(true, group <= 414.dp - (54.dp + 8.dp) * 2)
    }

    @Test fun `rows never get smaller than a usable minimum`() {
        assertEquals(40.dp, menuRowHeight(300.dp, 6))
        assertEquals(54.dp, menuRowHeight(300.dp, 0))
    }
}
