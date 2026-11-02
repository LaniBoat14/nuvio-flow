package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelRangeRateGateTest {
    private var now = 10_000L
    private val gate = ParallelRangeRateGate(clock = { now }, jitter = { 0L })

    @Test
    fun `open gate lets every piece start`() {
        assertFalse(gate.blocking)
        assertTrue(gate.tryStart(1))
        assertTrue(gate.tryStart(2))
        assertEquals(0, gate.waitingCount())
    }

    @Test
    fun `a burst refused together counts once and waits two seconds`() {
        val sent = now
        now += 300
        assertEquals(1, gate.refused(1, sent, null))
        assertEquals(0, gate.refused(2, sent, null))
        assertEquals(0, gate.refused(3, sent, null))
        assertEquals(1, gate.refusals)
        assertTrue(gate.blocking)
        assertEquals(ParallelRangeRateGate.FIRST_WAIT_MS, gate.waitLeftMs())
    }

    @Test
    fun `nothing starts during the wait, then only the piece nearest the player tries`() {
        val sent = now
        gate.refused(3, sent, null)
        gate.refused(1, sent, null)
        gate.refused(2, sent, null)
        assertFalse(gate.tryStart(3))
        assertFalse(gate.tryStart(2))
        assertFalse(gate.tryStart(1))
        assertFalse(gate.tryStart(7))
        assertEquals(4, gate.waitingCount())

        now += ParallelRangeRateGate.FIRST_WAIT_MS
        assertFalse(gate.tryStart(3))
        assertFalse(gate.tryStart(7))
        assertTrue(gate.tryStart(1))
        assertFalse("one try at a time", gate.tryStart(2))

        val probeSent = now
        now += 400
        assertTrue(gate.answered(probeSent))
        assertFalse(gate.blocking)
        assertTrue(gate.tryStart(2))
        assertTrue(gate.tryStart(3))
        assertTrue(gate.tryStart(7))
        assertEquals(0, gate.refusals)
    }

    @Test
    fun `a refused try doubles the wait up to fifteen seconds`() {
        gate.refused(1, now, null)
        val waits = mutableListOf(gate.waitLeftMs())
        repeat(5) {
            now += gate.waitLeftMs()
            assertTrue(gate.tryStart(1))
            val sent = now
            now += 100
            assertTrue(gate.refused(1, sent, null) > 0)
            waits += gate.waitLeftMs()
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), waits)
    }

    @Test
    fun `retry after from the server is honoured up to fifteen seconds`() {
        gate.refused(1, now, 5_000L)
        assertEquals(5_000L, gate.waitLeftMs())
        now += 5_000L
        assertTrue(gate.tryStart(1))
        val sent = now
        now += 50
        gate.refused(1, sent, 600_000L)
        assertEquals(ParallelRangeRateGate.MAX_WAIT_MS, gate.waitLeftMs())
    }

    @Test
    fun `retry after on a refusal already counted still lengthens the wait`() {
        val sent = now
        gate.refused(1, sent, null)
        assertEquals(0, gate.refused(2, sent, 9_000L))
        assertEquals(9_000L, gate.waitLeftMs())
    }

    @Test
    fun `an answer to a request sent before the refusal does not end the spell`() {
        val earlier = now
        now += 1_000
        gate.refused(1, earlier + 500, null)
        assertFalse(gate.answered(earlier))
        assertTrue(gate.blocking)
    }

    @Test
    fun `a try that fails another way lets the next piece try`() {
        gate.refused(1, now, null)
        assertFalse(gate.tryStart(1))
        assertFalse(gate.tryStart(2))
        now += ParallelRangeRateGate.FIRST_WAIT_MS
        assertTrue(gate.tryStart(1))
        assertFalse(gate.tryStart(2))
        gate.finished(1)
        assertTrue(gate.tryStart(2))
    }

    @Test
    fun `a piece that stops waiting does not hold the others back`() {
        gate.refused(1, now, null)
        assertFalse(gate.tryStart(1))
        assertFalse(gate.tryStart(2))
        gate.leave(1)
        now += ParallelRangeRateGate.FIRST_WAIT_MS
        assertTrue(gate.tryStart(2))
    }

    @Test
    fun `a refused try frees the try for the piece nearest the player`() {
        gate.refused(2, now, null)
        now += ParallelRangeRateGate.FIRST_WAIT_MS
        assertTrue(gate.tryStart(2))
        val sent = now
        assertFalse(gate.tryStart(1))
        now += 100
        gate.refused(2, sent, null)
        assertEquals(2, gate.refusals)
        now += gate.waitLeftMs()
        assertFalse(gate.tryStart(2))
        assertTrue(gate.tryStart(1))
    }

    @Test
    fun `the spell length grows until a request is answered`() {
        assertEquals(0L, gate.spellMs())
        gate.refused(1, now, null)
        now += 30_000
        assertEquals(30_000L, gate.spellMs())
        now += ParallelRangeRateGate.GIVE_UP_MS
        assertTrue(gate.spellMs() >= ParallelRangeRateGate.GIVE_UP_MS)
        assertTrue(gate.tryStart(1))
        val sent = now
        now += 10
        gate.answered(sent)
        assertEquals(0L, gate.spellMs())
    }

    @Test
    fun `a later spell starts from the short wait again`() {
        gate.refused(1, now, null)
        now += 2_000
        gate.tryStart(1)
        val sent = now
        now += 10
        gate.refused(1, sent, null)
        now += gate.waitLeftMs()
        gate.tryStart(1)
        val answeredSent = now
        now += 10
        assertTrue(gate.answered(answeredSent))
        now += 60_000
        gate.refused(4, now - 5, null)
        assertEquals(ParallelRangeRateGate.FIRST_WAIT_MS, gate.waitLeftMs())
    }
}
