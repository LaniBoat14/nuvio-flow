package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

class PrepareRateBookTest {
    @Test
    fun `keys split by kind of title and decoder`() {
        assertEquals("hd|sw", PrepareRateBook.key(fourK = false, remux = true, hardware = false))
        assertEquals("remux|sw", PrepareRateBook.key(fourK = true, remux = true, hardware = false))
        assertEquals("4k|hw", PrepareRateBook.key(fourK = true, remux = false, hardware = true))
    }

    @Test
    fun `first measurement is taken as it is`() {
        assertEquals(0.41, PrepareRateBook.blend(null, 0.41), 1e-9)
    }

    @Test
    fun `later measurements are averaged with the stored one`() {
        assertEquals(0.6, PrepareRateBook.blend(0.4, 0.8), 1e-9)
    }

    @Test
    fun `values stay inside sane bounds`() {
        assertEquals(PrepareRateBook.MIN_SECONDS, PrepareRateBook.blend(null, 0.001), 1e-9)
        assertEquals(PrepareRateBook.MAX_SECONDS, PrepareRateBook.blend(null, 60.0), 1e-9)
    }
}
