package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonSubtitleRestoreTest {
    @Test
    fun `a restored subtitle drawn on top of the video shows while the film plays`() {
        assertTrue(addonSubtitleAttachIsSeamless(usingMpv = false, preAttached = false, drawnOnTop = true, playing = true))
    }

    @Test
    fun `a restored subtitle that needs a reload waits for a pause`() {
        assertFalse(addonSubtitleAttachIsSeamless(usingMpv = false, preAttached = false, drawnOnTop = false, playing = true))
        assertTrue(addonSubtitleAttachIsSeamless(usingMpv = false, preAttached = false, drawnOnTop = false, playing = false))
    }

    @Test
    fun `mpv and pre-attached subtitles always show at once`() {
        assertTrue(addonSubtitleAttachIsSeamless(usingMpv = true, preAttached = false, drawnOnTop = false, playing = true))
        assertTrue(addonSubtitleAttachIsSeamless(usingMpv = false, preAttached = true, drawnOnTop = false, playing = true))
    }
}
