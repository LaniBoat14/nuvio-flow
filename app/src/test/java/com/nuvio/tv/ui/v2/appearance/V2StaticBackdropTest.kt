package com.nuvio.tv.ui.v2.appearance

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class V2StaticBackdropTest {
    @Test fun `still page backgrounds keep their own retained layer`() {
        assertEquals(Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen), Modifier.v2StaticBackdrop())
    }

    @Test fun `the retained layer is not the per frame alpha layer`() {
        assertNotEquals(Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Auto), Modifier.v2StaticBackdrop())
    }
}
