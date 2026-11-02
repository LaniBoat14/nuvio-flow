package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentDiscoveryFocusRowTest {
    @Test
    fun `back from plugins lands on plugins`() {
        assertEquals(ContentDiscoveryRow.PLUGINS, contentDiscoveryFocusRow(ContentDiscoveryRow.PLUGINS, showPlugins = true))
    }

    @Test
    fun `every row the user opened takes the focus back`() {
        ContentDiscoveryRow.entries.forEach { assertEquals(it, contentDiscoveryFocusRow(it, showPlugins = true)) }
    }

    @Test
    fun `hidden plugins row falls back to addons`() {
        assertEquals(ContentDiscoveryRow.ADDONS, contentDiscoveryFocusRow(ContentDiscoveryRow.PLUGINS, showPlugins = false))
    }
}
