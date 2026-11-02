package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyOutputReturnTest {

    private class Run(val checks: Int, val rebuilds: Int, val elapsedMs: Long)

    private fun run(freesAtMs: Long?, afterRefusedReturn: Boolean = false, rebuildsBefore: Int = 0): Run {
        var now = 0L
        var ticks = 0
        var rebuilds = rebuildsBefore
        while (true) {
            val wait = BusyOutputReturn.nextDelayMs(ticks, afterRefusedReturn) ?: break
            ticks++
            now += wait
            val opened = freesAtMs != null && now >= freesAtMs
            if (BusyOutputReturn.shouldRebuild(opened, rebuilds)) {
                rebuilds++
                break
            }
            if (opened) break
        }
        return Run(ticks, rebuilds - rebuildsBefore, now)
    }

    @Test
    fun `holder lets go after 60 s, exactly one rebuild`() {
        val result = run(freesAtMs = 60_000L)
        assertEquals(1, result.rebuilds)
        assertEquals(12, result.checks)
        assertEquals(60_000L, result.elapsedMs)
    }

    @Test
    fun `output never frees, no rebuild and the checks stop after about an hour`() {
        val result = run(freesAtMs = null)
        assertEquals(0, result.rebuilds)
        assertEquals(BusyOutputReturn.MAX_CHECKS, result.checks)
        assertEquals(61 * 60_000L, result.elapsedMs)
        assertNull(BusyOutputReturn.nextDelayMs(BusyOutputReturn.MAX_CHECKS, afterRefusedReturn = false))
    }

    @Test
    fun `a refused return starts again at the slower pace`() {
        assertEquals(5_000L, BusyOutputReturn.nextDelayMs(0, afterRefusedReturn = false))
        assertEquals(15_000L, BusyOutputReturn.nextDelayMs(0, afterRefusedReturn = true))
        val result = run(freesAtMs = null, afterRefusedReturn = true, rebuildsBefore = 1)
        assertEquals(BusyOutputReturn.MAX_CHECKS - 12, result.checks)
    }

    @Test
    fun `no more than three returns per title`() {
        assertTrue(BusyOutputReturn.shouldRebuild(opened = true, rebuildsSoFar = 2))
        assertFalse(BusyOutputReturn.shouldRebuild(opened = true, rebuildsSoFar = BusyOutputReturn.MAX_REBUILDS_PER_STREAM))
        assertEquals(0, run(freesAtMs = 0L, rebuildsBefore = BusyOutputReturn.MAX_REBUILDS_PER_STREAM).rebuilds)
    }

    @Test
    fun `a real denial starts no checks`() {
        val receiverRefuses = BitstreamOutputReading(refusedListedLive = true, companionOpened = true)
        assertFalse(BusyOutputReturn.startsFor(receiverRefuses, retryScheduled = false))
        val notListed = BitstreamOutputReading(refusedListedLive = false, companionOpened = null)
        assertFalse(BusyOutputReturn.startsFor(notListed, retryScheduled = false))
        assertFalse(BusyOutputReturn.startsFor(null, retryScheduled = false))
    }

    @Test
    fun `busy output starts checks once no retry is left`() {
        val busy = BitstreamOutputReading(refusedListedLive = true, companionOpened = false)
        assertTrue(BusyOutputReturn.startsFor(busy, retryScheduled = false))
        assertFalse(BusyOutputReturn.startsFor(busy, retryScheduled = true))
    }

    @Test
    fun `no check during a seek or rebuild, in the background, or when PCM was chosen`() {
        fun may(
            playerReady: Boolean = true,
            rebuildOrSeekPending: Boolean = false,
            inBackground: Boolean = false,
            playingAsPcm: Boolean = true,
            bitstreamFormat: Boolean = true,
            policyDenies: Boolean = false,
            normalSpeed: Boolean = true
        ) = BusyOutputReturn.mayCheckNow(
            sameStream = true,
            playerReady = playerReady,
            rebuildOrSeekPending = rebuildOrSeekPending,
            inBackground = inBackground,
            playingAsPcm = playingAsPcm,
            bitstreamFormat = bitstreamFormat,
            policyDenies = policyDenies,
            normalSpeed = normalSpeed
        )
        assertTrue(may())
        assertFalse(may(playerReady = false))
        assertFalse(may(rebuildOrSeekPending = true))
        assertFalse(may(inBackground = true))
        assertFalse(may(playingAsPcm = false))
        assertFalse(may(bitstreamFormat = false))
        assertFalse(may(policyDenies = true))
        assertFalse(may(normalSpeed = false))
    }
}
