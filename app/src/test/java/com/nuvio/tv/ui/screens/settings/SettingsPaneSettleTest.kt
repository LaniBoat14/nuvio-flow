package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPaneSettleTest {
    @Test fun `moving along the menu waits before building the next options`() {
        assertEquals(
            SETTINGS_PANE_SETTLE_MS,
            settingsPaneSettleMs(SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT, movingAlongMenu = true)
        )
    }

    @Test fun `opening a category shows its options at once`() {
        assertEquals(0L, settingsPaneSettleMs(SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT, movingAlongMenu = false))
    }

    @Test fun `options already showing the category never wait`() {
        assertEquals(0L, settingsPaneSettleMs(SettingsCategory.PLAYBACK, SettingsCategory.PLAYBACK, movingAlongMenu = true))
    }

    @Test fun `the wait stays short enough to feel immediate`() {
        assert(SETTINGS_PANE_SETTLE_MS in 100L..200L)
    }
}
