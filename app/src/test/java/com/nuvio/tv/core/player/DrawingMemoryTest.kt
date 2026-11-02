package com.nuvio.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingMemoryTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `only 2 GB boxes and smaller release drawing memory for a film`() {
        assertTrue(DrawingMemory.appliesTo((1.9 * gb).toLong()))
        assertTrue(DrawingMemory.appliesTo(0L))
        assertFalse(DrawingMemory.appliesTo((2.8 * gb).toLong()))
        assertFalse(DrawingMemory.appliesTo((3.8 * gb).toLong()))
    }
}
