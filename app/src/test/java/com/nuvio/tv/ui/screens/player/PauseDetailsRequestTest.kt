package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseDetailsRequestTest {
    @Test
    fun `closing plays the video again only when it was playing`() {
        val request = PauseDetailsRequest()
        request.open(resumeOnClose = true)
        assertTrue(request.close())
        request.open(resumeOnClose = false)
        assertFalse(request.close())
    }

    @Test
    fun `a closed request does not resume twice`() {
        val request = PauseDetailsRequest()
        request.open(resumeOnClose = true)
        assertTrue(request.close())
        assertFalse(request.close())
        assertFalse(request.isOpen)
    }

    @Test
    fun `nothing opened means nothing to resume`() {
        assertFalse(PauseDetailsRequest().close())
    }

    @Test
    fun `an open request keeps its overlay from the automatic timer`() {
        val request = PauseDetailsRequest()
        assertFalse(request.holdsOverlay(overlayShowing = true))
        request.open(resumeOnClose = true)
        assertTrue(request.holdsOverlay(overlayShowing = true))
        assertTrue(request.isOpen)
    }

    @Test
    fun `a request whose overlay went away is forgotten`() {
        val request = PauseDetailsRequest()
        request.open(resumeOnClose = true)
        assertFalse(request.holdsOverlay(overlayShowing = false))
        assertFalse(request.isOpen)
        assertFalse(request.close())
    }

    @Test
    fun `clearing drops the resume`() {
        val request = PauseDetailsRequest()
        request.open(resumeOnClose = true)
        request.clear()
        assertFalse(request.close())
    }
}
