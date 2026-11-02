package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SubtitleStyleSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SidecarStyleSignatureTest {

    @Test
    fun `the cue on screen is drawn again when HDR is found with the HDR style on`() {
        val style = SubtitleStyleSettings(hdrStyleEnabled = true)
        val before = PlayerUiState(subtitleStyle = style, isHdrVideo = false).sidecarStyleSignature()
        val after = PlayerUiState(subtitleStyle = style, isHdrVideo = true).sidecarStyleSignature()
        assertNotEquals(before, after)
    }

    @Test
    fun `Dim controls in HDR alone does not redraw subtitles`() {
        val style = SubtitleStyleSettings(hdrStyleEnabled = false)
        val off = PlayerUiState(subtitleStyle = style, isHdrVideo = true, dimHdrOverlays = false).sidecarStyleSignature()
        val on = PlayerUiState(subtitleStyle = style, isHdrVideo = true, dimHdrOverlays = true).sidecarStyleSignature()
        assertEquals(off, on)
    }
}
