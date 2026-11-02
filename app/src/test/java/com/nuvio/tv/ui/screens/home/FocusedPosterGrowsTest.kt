package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedPosterGrowsTest {
    @Test fun posterStaysSmallWhileTheTrailerPlaysInTheHero() {
        assertFalse(focusedPosterGrows(true, true, FocusedPosterTrailerPlaybackTarget.HERO_MEDIA))
        assertFalse(focusedPosterGrows(true, true, FocusedPosterTrailerPlaybackTarget.FEATHERED_WINDOW))
    }

    @Test fun posterGrowsForItsOwnTrailerOrWithoutTrailers() {
        assertTrue(focusedPosterGrows(true, true, FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD))
        FocusedPosterTrailerPlaybackTarget.entries.forEach { assertTrue(focusedPosterGrows(true, false, it)) }
    }

    @Test fun posterNeverGrowsWhenExpandIsOff() {
        FocusedPosterTrailerPlaybackTarget.entries.forEach {
            assertFalse(focusedPosterGrows(false, true, it))
            assertFalse(focusedPosterGrows(false, false, it))
        }
    }
}
