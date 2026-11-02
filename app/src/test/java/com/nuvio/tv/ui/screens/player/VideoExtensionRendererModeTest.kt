package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoExtensionRendererModeTest {

    @Test
    fun `prefer app decoders keeps video on the hardware decoder first`() {
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            videoExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        )
    }

    @Test
    fun `off and on are passed to video unchanged`() {
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            videoExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        )
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            videoExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        )
    }
}
