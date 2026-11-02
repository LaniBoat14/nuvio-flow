package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.ui.screens.player.iec.LiveDirectAudioPlayback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SameConfigRetrySkipTest {

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    private fun skip(
        pcmFallbackAvailable: Boolean = true,
        refusedListedLive: Boolean = true,
        ownTrackMayHold: Boolean = false,
        companionOpened: Boolean? = false
    ) = CompressedOutputCheck.skipSameConfigRetry(
        pcmFallbackAvailable, refusedListedLive, ownTrackMayHold, companionOpened
    )

    @Test
    fun `output held by another app goes straight to PCM`() {
        assertTrue(skip())
    }

    @Test
    fun `retries stay when the output is there or PCM cannot take over`() {
        assertFalse(skip(companionOpened = true))
        assertFalse(skip(companionOpened = null))
        assertFalse(skip(refusedListedLive = false))
        assertFalse(skip(pcmFallbackAvailable = false))
    }

    @Test
    fun `retries stay while our own passthrough track may still hold the output`() {
        assertFalse(skip(ownTrackMayHold = true))
    }

    @Test
    fun `own track counts as holding while open and shortly after it closed`() {
        assertTrue(CompressedOutputCheck.ownTrackMayHoldOutput(ownPassthroughLive = true, msSinceOwnCleared = null))
        assertTrue(CompressedOutputCheck.ownTrackMayHoldOutput(ownPassthroughLive = false, msSinceOwnCleared = 300L))
        assertFalse(
            CompressedOutputCheck.ownTrackMayHoldOutput(
                ownPassthroughLive = false,
                msSinceOwnCleared = CompressedOutputCheck.OWN_TRACK_CLOSE_MS
            )
        )
        assertFalse(CompressedOutputCheck.ownTrackMayHoldOutput(ownPassthroughLive = false, msSinceOwnCleared = null))
    }

    @Test
    fun `AM9 1464 step 3, E-AC-3 refused while another app holds TrueHD, IEC usable earlier`() {
        var now = 0L
        LiveDirectAudioPlayback.clock = { now }
        now = ms(13, 55, 17, 91)
        LiveDirectAudioPlayback.noteOwnTrackOpened()
        now = ms(13, 55, 34, 784)
        LiveDirectAudioPlayback.noteOwnTrackClosed()

        now = ms(13, 59, 47, 123)
        val live = setOf(
            C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DOLBY_TRUEHD
        )
        assertEquals(C.ENCODING_AC3, CompressedOutputCheck.companionEncoding(C.ENCODING_E_AC3, live))
        val reading = BitstreamOutputReading(
            refusedListedLive = PassthroughOutputReturn.canPass(live, C.ENCODING_E_AC3),
            companionOpened = false
        )
        assertTrue(reading.outputUnavailable)
        assertTrue(
            skip(
                refusedListedLive = reading.refusedListedLive,
                ownTrackMayHold = CompressedOutputCheck.ownTrackMayHoldOutput(
                    LiveDirectAudioPlayback.ownTrackOpen(),
                    LiveDirectAudioPlayback.msSinceOwnTrackClosed()
                ),
                companionOpened = reading.companionOpened
            )
        )
    }

    @Test
    fun `a refusal where AC-3 still opens is retried and learned`() {
        val reading = BitstreamOutputReading(refusedListedLive = true, companionOpened = true)
        assertFalse(reading.outputUnavailable)
        assertFalse(skip(companionOpened = reading.companionOpened))
    }

    private fun ms(h: Int, m: Int, s: Int, milli: Int): Long = ((h * 60L + m) * 60L + s) * 1000L + milli
}
