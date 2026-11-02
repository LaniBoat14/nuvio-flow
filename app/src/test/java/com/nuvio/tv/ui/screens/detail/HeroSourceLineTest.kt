package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.data.local.StreamAutoPlayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HeroSourceLineTest {
    @Test fun `source line needs pre-search and an auto-play mode other than manual`() {
        for (mode in StreamAutoPlayMode.entries) {
            assertEquals(mode != StreamAutoPlayMode.MANUAL, heroSourceLineOn(true, mode))
            assertEquals(false, heroSourceLineOn(false, mode))
        }
    }
}
