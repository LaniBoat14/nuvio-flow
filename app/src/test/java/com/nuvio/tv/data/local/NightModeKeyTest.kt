package com.nuvio.tv.data.local

import com.nuvio.tv.data.local.NightModeLevel.LIGHT
import com.nuvio.tv.data.local.NightModeLevel.MEDIUM
import com.nuvio.tv.data.local.NightModeLevel.OFF
import com.nuvio.tv.data.local.NightModeLevel.STRONG
import org.junit.Assert.assertEquals
import org.junit.Test

class NightModeKeyTest {

    @Test
    fun `toggle turns any level off`() {
        for (level in listOf(LIGHT, MEDIUM, STRONG)) {
            assertEquals(OFF, level.nextForKey(lastOn = LIGHT, style = NightModeKeyStyle.TOGGLE))
        }
    }

    @Test
    fun `toggle brings back the level used last`() {
        for (level in listOf(LIGHT, MEDIUM, STRONG)) {
            assertEquals(level, OFF.nextForKey(lastOn = level, style = NightModeKeyStyle.TOGGLE))
        }
    }

    @Test
    fun `toggle starts at medium when nothing was used yet`() {
        assertEquals(MEDIUM, OFF.nextForKey(lastOn = OFF, style = NightModeKeyStyle.TOGGLE))
        assertEquals(MEDIUM, OFF.nextForKey(lastOn = NightModeLevel.lastOnFromKey(null), style = NightModeKeyStyle.TOGGLE))
    }

    @Test
    fun `cycle steps through every level and wraps to off`() {
        var level = OFF
        val seen = (1..5).map { level = level.nextForKey(lastOn = STRONG, style = NightModeKeyStyle.CYCLE); level }
        assertEquals(listOf(LIGHT, MEDIUM, STRONG, OFF, LIGHT), seen)
    }

    @Test
    fun `two toggles come back to where they started`() {
        for (start in listOf(LIGHT, MEDIUM, STRONG)) {
            val off = start.nextForKey(lastOn = MEDIUM, style = NightModeKeyStyle.TOGGLE)
            val remembered = rememberedNightModeLevel(start, off, MEDIUM)
            assertEquals(start, off.nextForKey(remembered, NightModeKeyStyle.TOGGLE))
        }
    }

    @Test
    fun `remembered level follows the last level that was on`() {
        assertEquals(STRONG, rememberedNightModeLevel(OFF, STRONG, LIGHT))
        assertEquals(LIGHT, rememberedNightModeLevel(LIGHT, OFF, STRONG))
        assertEquals(STRONG, rememberedNightModeLevel(OFF, OFF, STRONG))
        assertEquals(MEDIUM, rememberedNightModeLevel(OFF, OFF, OFF))
    }

    @Test
    fun `the remote button job round trips`() {
        val map = RemoteButtonMap.DEFAULT.with(
            android.view.KeyEvent.KEYCODE_PROG_YELLOW,
            RemoteButtonBinding(RemoteButtonJob.NIGHT_MODE)
        )
        val decoded = RemoteButtonMap.decode(map.encode())
        assertEquals(RemoteButtonJob.NIGHT_MODE, decoded.overrideFor(android.view.KeyEvent.KEYCODE_PROG_YELLOW)?.job)
        assertEquals(RemoteButtonJob.NIGHT_MODE, RemoteButtonJob.fromId("night_mode"))
    }
}
