package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferSettingsNumbersTest {
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `the reach line shows what the last playback's buffer holds of a remux and a web file`() {
        assertEquals(BufferSettingsNumbers.Reach(245, 21, 130), BufferSettingsNumbers.reach(245))
        assertEquals(BufferSettingsNumbers.Reach(110, 9, 58), BufferSettingsNumbers.reach(110))
        assertEquals(BufferSettingsNumbers.Reach(840, 74, 448), BufferSettingsNumbers.reach(840))
        assertEquals(BufferSettingsNumbers.Reach(0, 0, 0), BufferSettingsNumbers.reach(-5))
    }

    @Test
    fun `the reach line uses the size the last playback got, or the automatic size before the first`() {
        val auto = 200
        assertEquals(auto, BufferSettingsNumbers.playbackBufferMb(auto, automaticSize = true, nativeMemory = false, lastPlayback = null))
        val last = com.nuvio.tv.core.player.BufferSizing.PlaybackBudget(nativeMemory = false, automatic = true, budgetMb = 110)
        assertEquals(110, BufferSettingsNumbers.playbackBufferMb(auto, automaticSize = true, nativeMemory = false, lastPlayback = last))
        assertEquals(17, BufferSettingsNumbers.reach(BufferSettingsNumbers.playbackBufferMb(auto, true, false, null)).remuxSeconds)
    }

    @Test
    fun `amber only when a remux cannot fill the seconds`() {
        val reach = BufferSettingsNumbers.reach(245)
        assertFalse(BufferSettingsNumbers.remuxCannotFill(20, reach))
        assertFalse(BufferSettingsNumbers.remuxCannotFill(21, reach))
        assertTrue(BufferSettingsNumbers.remuxCannotFill(22, reach))
        assertTrue(BufferSettingsNumbers.remuxCannotFill(60, reach))
        assertTrue(BufferSettingsNumbers.remuxCannotFill(30, BufferSettingsNumbers.reach(245)))
        assertFalse(BufferSettingsNumbers.remuxCannotFill(5, BufferSettingsNumbers.reach(110)))
    }

    @Test
    fun `the back buffer note shows what the player keeps inside the size`() {
        // Native memory preset: 12 s back buffer, 280 s ahead.
        assertNull(BufferSettingsNumbers.backBufferKept(12_000, 150, true, 280_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(12, 120), BufferSettingsNumbers.backBufferKept(12_000, 1000, true, 200_000))
        assertNull(BufferSettingsNumbers.backBufferKept(12_000, 62, true, 200_000))
        assertNull(BufferSettingsNumbers.backBufferKept(12_000, 100, true, 200_000))
        // Custom buffers without native memory on a 2 GB box with 2 x 16 MB chunks: 150 - 64 = 86 MB.
        assertNull(BufferSettingsNumbers.backBufferKept(5_000, 86, false, 15_000))
        // A short back buffer that fits stays as set.
        assertEquals(BufferSettingsNumbers.BackBufferKept(5, 50), BufferSettingsNumbers.backBufferKept(5_000, 1000, false, 15_000))
    }

    @Test
    fun `with native memory the back buffer is also held to half the minimum buffer`() {
        assertEquals(BufferSettingsNumbers.BackBufferKept(8, 75), BufferSettingsNumbers.backBufferKept(12_000, 1000, true, 15_000))
        assertEquals(BufferSettingsNumbers.BackBufferKept(12, 120), BufferSettingsNumbers.backBufferKept(12_000, 1000, false, 15_000))
    }

    @Test
    fun `the back buffer note uses the size the last film really got`() {
        val native = com.nuvio.tv.core.player.BufferSizing.PlaybackBudget(nativeMemory = true, automatic = true, budgetMb = 100)
        assertEquals(150, BufferSettingsNumbers.playbackBufferMb(150, true, true, null))
        assertEquals(100, BufferSettingsNumbers.playbackBufferMb(150, true, true, native))
        // A size the user set is what playback gets; another engine's or a user size's playback says nothing.
        assertEquals(300, BufferSettingsNumbers.playbackBufferMb(300, false, true, native))
        assertEquals(86, BufferSettingsNumbers.playbackBufferMb(86, true, false, native))
        assertEquals(150, BufferSettingsNumbers.playbackBufferMb(150, true, true, native.copy(automatic = false)))
    }

    @Test
    fun `nothing is kept at the smallest size`() {
        assertNull(BufferSettingsNumbers.backBufferKept(12_000, 50, true, 200_000))
        assertNull(BufferSettingsNumbers.backBufferKept(0, 1000, true, 200_000))
    }

    @Test
    fun `the stutter line follows the automatic size`() {
        assertEquals(150, BufferSettingsNumbers.stutterLimitMb(true, true, 150, 250))
        assertEquals(75, BufferSettingsNumbers.stutterLimitMb(false, true, 86, 175))
        assertEquals(250, BufferSettingsNumbers.stutterLimitMb(true, false, 150, 250))
    }

    @Test
    fun `the buffer's own colour leaves an automatic size alone`() {
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(150, true, 150, 325))
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(150, false, 150, 325))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.bufferStatus(250, false, 150, 325))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.bufferStatus(400, false, 150, 325))
        // Managed custom buffers: the buffer is the automatic size less the chunks.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(86, true, 75, 225))
    }

    private val fireStick4kMax = 1_710_032L * 1024L
    private val box3gb = 2_936_000L * 1024L
    private val am9 = 3_801_816L * 1024L

    private fun card(bufferMb: Int, automatic: Boolean, stutterMb: Int, dangerMb: Int, totalMb: Int, ram: Long) =
        BufferSettingsNumbers.cardStatus(
            BufferSettingsNumbers.bufferStatus(bufferMb, automatic, stutterMb, dangerMb),
            BufferSettingsNumbers.totalStatus(totalMb, ram)
        )

    @Test
    fun `default settings stay green on 2, 3 and 4 GB boxes`() {
        // Fire Stick 4K Max: 150 MB buffer and thumbnails, with and without native memory and Custom network 2 x 16 MB.
        assertEquals(MemoryUsageStatus.SAFE, card(150, false, 150, 325, 150 + 50, fireStick4kMax))
        assertEquals(MemoryUsageStatus.SAFE, card(150, true, 150, 325, 150 + 95, fireStick4kMax))
        assertEquals(MemoryUsageStatus.SAFE, card(150, true, 150, 325, 150 + 96 + 95, fireStick4kMax))
        assertEquals(MemoryUsageStatus.SAFE, card(86, true, 75, 225, 86 + 64 + 95, fireStick4kMax))
        // 3 GB: 500 MB automatic buffer, thumbnails, Custom network 4 x 16 MB.
        assertEquals(MemoryUsageStatus.SAFE, card(500, true, 500, 650, 500 + 125, box3gb))
        assertEquals(MemoryUsageStatus.SAFE, card(500, true, 500, 650, 500 + 160 + 125, box3gb))
        // 4 GB: 1000 MB automatic buffer, thumbnails, Custom network 4 x 16 MB.
        assertEquals(MemoryUsageStatus.SAFE, card(1000, true, 1000, 1200, 1000 + 125, am9))
        assertEquals(MemoryUsageStatus.SAFE, card(1000, true, 1000, 1200, 1000 + 160 + 125, am9))
    }

    @Test
    fun `16 connections of 32 MB on a 4 GB box turn the card red`() {
        assertEquals(MemoryUsageStatus.DANGER, card(1000, true, 1000, 1200, 1000 + 1088 + 125, am9))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(2213, am9))
    }

    @Test
    fun `an automatic buffer still counts at its shown size in the total`() {
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.bufferStatus(1000, true, 1000, 1200))
        // 16 x 16 MB beside the 1000 MB automatic buffer.
        assertEquals(MemoryUsageStatus.WARNING, card(1000, true, 1000, 1200, 1000 + 544 + 125, am9))
        // Fire Stick, 150 MB automatic buffer with 224 MB of downloads and 4K thumbnails.
        assertEquals(MemoryUsageStatus.WARNING, card(150, true, 150, 325, 150 + 224 + 95, fireStick4kMax))
    }

    @Test
    fun `the total sets the amber and red lines by the box's memory`() {
        // Fire Stick 4K Max: 869 MB beyond the system share.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(434, fireStick4kMax))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(435, fireStick4kMax))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(608, fireStick4kMax))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(609, fireStick4kMax))
        // 3 GB: 2067 MB beyond it.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(1033, box3gb))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(1034, box3gb))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(1447, box3gb))
        // AM9: 2912 MB beyond it.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(1456, am9))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(1457, am9))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(2038, am9))
        assertEquals(MemoryUsageStatus.DANGER, BufferSettingsNumbers.totalStatus(2039, am9))
        // Unknown memory is taken as 2 GB.
        assertEquals(MemoryUsageStatus.SAFE, BufferSettingsNumbers.totalStatus(624, 0L))
        assertEquals(MemoryUsageStatus.WARNING, BufferSettingsNumbers.totalStatus(625, 0L))
    }

    @Test
    fun `a user buffer past its own lines keeps the card at least that colour`() {
        assertEquals(MemoryUsageStatus.DANGER, card(400, false, 150, 325, 400 + 95, fireStick4kMax))
        assertEquals(MemoryUsageStatus.WARNING, card(1100, false, 1000, 1200, 1100 + 125, am9))
        assertEquals(MemoryUsageStatus.DANGER, card(1100, false, 1000, 1200, 1100 + 1088, am9))
    }

    @Test
    fun `the connection count line is for boxes with 2 GB or less above 2 connections`() {
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 4))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 3))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 2))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((3.8 * gb).toLong(), 4))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh(0L, 4))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 16))
        assertTrue(BufferSettingsNumbers.connectionCountTooHigh((1.9 * gb).toLong(), 8))
        assertFalse(BufferSettingsNumbers.connectionCountTooHigh((3.8 * gb).toLong(), 16))
    }

    @Test
    fun `custom buffers without native memory stop at 1600 MB`() {
        assertEquals(1600, BufferSettingsNumbers.CUSTOM_ENGINE_MAX_MB)
    }
}
