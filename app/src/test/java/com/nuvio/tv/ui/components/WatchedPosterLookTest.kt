package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchedPosterLookTest {
    @Test fun aDimmedPosterKeepsFortyPercentColourAtEightyPercentOpacity() {
        assertEquals(0.4f, watchedPosterSaturation(1f), 0.0001f)
        assertEquals(0.8f, watchedPosterOpacity(1f), 0.0001f)
    }

    @Test fun aFocusedOrUnwatchedPosterIsLeftAlone() {
        assertEquals(1f, watchedPosterSaturation(0f), 0.0001f)
        assertEquals(1f, watchedPosterOpacity(0f), 0.0001f)
        assertNull(watchedPosterFilter(0f))
    }

    @Test fun halfwayThroughTheFadeIsHalfwayBetween() {
        assertEquals(0.7f, watchedPosterSaturation(0.5f), 0.0001f)
        assertEquals(0.9f, watchedPosterOpacity(0.5f), 0.0001f)
    }
}
