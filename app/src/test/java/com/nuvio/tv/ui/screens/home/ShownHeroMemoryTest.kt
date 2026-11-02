package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownHeroMemoryTest {
    @Test fun `the hero last shown comes back for the same picture and size`() {
        val memory = ShownHeroMemory<String>()
        memory.keep("greys-art", 1920, 1080, "greys pixels")
        assertEquals("greys pixels", memory.recall("greys-art", 1920, 1080))
    }

    @Test fun `another picture or another size does not`() {
        val memory = ShownHeroMemory<String>()
        memory.keep("greys-art", 1920, 1080, "greys pixels")
        assertNull(memory.recall("ncis-art", 1920, 1080))
        assertNull(memory.recall("greys-art", 1280, 720))
    }

    @Test fun `only the newest hero is kept`() {
        val memory = ShownHeroMemory<String>()
        memory.keep("greys-art", 1920, 1080, "greys pixels")
        memory.keep("ncis-art", 1920, 1080, "ncis pixels")
        assertNull(memory.recall("greys-art", 1920, 1080))
        assertEquals("ncis pixels", memory.recall("ncis-art", 1920, 1080))
    }

    @Test fun `nothing shown yet gives nothing`() {
        assertNull(ShownHeroMemory<String>().recall("greys-art", 1920, 1080))
    }
}
