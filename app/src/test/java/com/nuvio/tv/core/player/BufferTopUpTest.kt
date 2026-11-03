package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferTopUpTest {
    private val mb = 1024L * 1024L

    @Test
    fun `loading starts again a few seconds below the maximum`() {
        val topUp = BufferTopUp(maxBufferMs = 60_000, minBufferMs = 15_000)
        assertEquals(57_000, topUp.resumeBelowMs)
        assertTrue(topUp.wantsLoading(56_900_000L, 10 * mb, 300 * mb))
        assertFalse(topUp.wantsLoading(57_000_000L, 10 * mb, 300 * mb))
        assertFalse(topUp.wantsLoading(60_000_000L, 10 * mb, 300 * mb))
    }

    @Test
    fun `a full byte target waits until one piece fits`() {
        val topUp = BufferTopUp(maxBufferMs = 60_000, minBufferMs = 15_000, pieceBytes = 16 * mb)
        assertFalse(topUp.wantsLoading(20_000_000L, 290 * mb, 300 * mb))
        assertTrue(topUp.wantsLoading(20_000_000L, 284 * mb, 300 * mb))
        assertFalse(topUp.wantsLoading(20_000_000L, 0L, 0L))
    }

    @Test
    fun `a piece larger than a small target counts as a quarter of it`() {
        val topUp = BufferTopUp(maxBufferMs = 60_000, minBufferMs = 15_000, pieceBytes = 32 * mb)
        assertTrue(topUp.wantsLoading(20_000_000L, 30 * mb, 40 * mb))
        assertFalse(topUp.wantsLoading(20_000_000L, 31 * mb, 40 * mb))
    }

    @Test
    fun `a short buffer ahead never resumes below the minimum`() {
        assertEquals(15_000, BufferTopUp(maxBufferMs = 15_000, minBufferMs = 15_000).resumeBelowMs)
        assertEquals(15_000, BufferTopUp(maxBufferMs = 16_000, minBufferMs = 15_000).resumeBelowMs)
    }

    @Test
    fun `the log line names the numbers`() {
        assertEquals("aheadMs=45000 resumeBelowMs=42000 pieceKb=2048", BufferTopUp(45_000, 15_000).describe())
    }

    @Test
    fun `buffer ahead keeps the app's 15 s minimum and covers both start buffers`() {
        assertEquals(BufferAhead.Durations(15_000, 60_000), BufferAhead.durations(60_000, 5_000, 3_000))
        assertEquals(BufferAhead.Durations(15_000, 600_000), BufferAhead.durations(600_000, 1_500, 1_500))
        assertEquals(BufferAhead.Durations(10_000, 10_000), BufferAhead.durations(10_000, 5_000, 3_000))
        assertEquals(BufferAhead.Durations(30_000, 60_000), BufferAhead.durations(60_000, 30_000, 3_000))
        assertEquals(BufferAhead.Durations(40_000, 40_000), BufferAhead.durations(20_000, 5_000, 40_000))
    }

    @Test
    fun `stored min and max become the max`() {
        assertEquals(60_000, BufferAhead.fromStored(20_000, 60_000))
        assertEquals(280_000, BufferAhead.fromStored(200_000, 280_000))
        assertEquals(45_000, BufferAhead.fromStored(45_000, 30_000))
        assertEquals(600_000, BufferAhead.limitMs(nativeMemory = true))
        assertEquals(120_000, BufferAhead.limitMs(nativeMemory = false))
    }
}
