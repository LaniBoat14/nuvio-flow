package com.nuvio.tv.ui.screens.detail

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class V2EpisodeCardSizeTest {

    @Test
    fun eachOriginalWidthStepGetsASixteenByNinePicture() {
        assertEquals(157.5f, v2EpisodeCardHeight(280.dp).value, 0.01f)
        assertEquals(180f, v2EpisodeCardHeight(320.dp).value, 0.01f)
        assertEquals(202.5f, v2EpisodeCardHeight(360.dp).value, 0.01f)
        assertEquals(225f, v2EpisodeCardHeight(400.dp).value, 0.01f)
    }
}
