package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailEdgeBackPressTest {
    private fun press(
        keyCode: Int = KeyEvent.KEYCODE_DPAD_LEFT,
        action: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
        isRtl: Boolean = false,
        overlayOpen: Boolean = false
    ) = isDetailEdgeBackPress(action, keyCode, repeatCount, isRtl, overlayOpen)

    @Test fun `a fresh left press counts`() {
        assertTrue(press())
    }

    @Test fun `a held left press does not`() {
        assertFalse(press(repeatCount = 1))
        assertFalse(press(repeatCount = 12))
    }

    @Test fun `key up and other keys do not`() {
        assertFalse(press(action = KeyEvent.ACTION_UP))
        assertFalse(press(keyCode = KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(press(keyCode = KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test fun `right to left languages use right`() {
        assertTrue(press(keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, isRtl = true))
        assertFalse(press(keyCode = KeyEvent.KEYCODE_DPAD_LEFT, isRtl = true))
    }

    @Test fun `an open overlay or trailer keeps the press`() {
        assertFalse(press(overlayOpen = true))
    }
}
