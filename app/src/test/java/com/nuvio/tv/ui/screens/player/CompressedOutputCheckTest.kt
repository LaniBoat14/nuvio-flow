package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.AudioPassthroughPolicy.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressedOutputCheckTest {

    private val route = "type:hdmi|name:am9_pro"
    private val hdmi = setOf(
        C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
        C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD
    )

    @Test
    fun `output held elsewhere is not learned as a receiver refusal`() {
        assertTrue(CompressedOutputCheck.outputUnavailable(refusedListedLive = true, companionOpened = false))
    }

    @Test
    fun `a format the receiver really refuses is still learned`() {
        assertFalse(CompressedOutputCheck.outputUnavailable(refusedListedLive = true, companionOpened = true))
        assertFalse(CompressedOutputCheck.outputUnavailable(refusedListedLive = false, companionOpened = false))
        assertFalse(CompressedOutputCheck.outputUnavailable(refusedListedLive = true, companionOpened = null))
    }

    @Test
    fun `companion is AC-3, or E-AC-3 when AC-3 itself was refused`() {
        assertEquals(C.ENCODING_AC3, CompressedOutputCheck.companionEncoding(C.ENCODING_E_AC3, hdmi))
        assertEquals(C.ENCODING_AC3, CompressedOutputCheck.companionEncoding(C.ENCODING_DTS_HD, hdmi))
        assertEquals(C.ENCODING_E_AC3, CompressedOutputCheck.companionEncoding(C.ENCODING_AC3, hdmi))
        assertNull(CompressedOutputCheck.companionEncoding(C.ENCODING_DTS, setOf(C.ENCODING_DTS)))
    }

    @Test
    fun `registration echo lists only outputs that are already connected`() {
        val known = setOf(2, 17, 41)
        assertTrue(addsOnlyKnownOutputs(known, listOf(2, 17, 41)))
        assertFalse(addsOnlyKnownOutputs(known, listOf(2, 17, 41, 58)))
        assertFalse(addsOnlyKnownOutputs(known, emptyList()))
        assertFalse(addsOnlyKnownOutputs(emptySet(), listOf(58)))
    }

    @Test
    fun `evidence kept across the echo stops a second refused format being learned`() {
        val ledger = AudioRejectionLedger()
        ledger.noteOpenRefused(route, Group.EAC3, nowMs = 1_000L)
        if (!addsOnlyKnownOutputs(setOf(2, 17, 41), listOf(2, 17, 41))) ledger.invalidate()
        ledger.noteOpenRefused(route, Group.DTS_HD, nowMs = 2_000L)
        assertFalse(ledger.commit(AudioRejectionLedger.entry(route, Group.DTS_HD), nowMs = 3_000L).learn)
    }

    @Test
    fun `wiping the evidence on the echo let the second format be learned`() {
        val ledger = AudioRejectionLedger()
        ledger.noteOpenRefused(route, Group.EAC3, nowMs = 1_000L)
        ledger.invalidate()
        ledger.noteOpenRefused(route, Group.DTS_HD, nowMs = 2_000L)
        assertTrue(ledger.commit(AudioRejectionLedger.entry(route, Group.DTS_HD), nowMs = 3_000L).learn)
    }
}
