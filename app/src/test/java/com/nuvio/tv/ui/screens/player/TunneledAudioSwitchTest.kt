package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunneledAudioSwitchTest {
    private val englishDdp = TrackInfo(0, "English", "en", codec = "E-AC-3", channelCount = 6, sampleRate = 48_000)
    private val spanishDdp = TrackInfo(1, "Spanish", "es", codec = "E-AC-3", channelCount = 6, sampleRate = 48_000)
    private val englishTrueHd = TrackInfo(2, "English", "en", codec = "TrueHD", channelCount = 8, sampleRate = 48_000)
    private val commentaryStereo = TrackInfo(3, "Commentary", "en", codec = "E-AC-3", channelCount = 2, sampleRate = 48_000)

    @Test
    fun `without a running tunnel nothing is rebuilt`() {
        assertFalse(tunneledAudioSwitchNeedsRebuild(false, englishDdp, englishTrueHd))
    }

    @Test
    fun `a track of the same format and layout switches in place`() {
        assertFalse(tunneledAudioSwitchNeedsRebuild(true, englishDdp, spanishDdp))
    }

    @Test
    fun `another format or channel layout rebuilds inside a tunnel`() {
        assertTrue(tunneledAudioSwitchNeedsRebuild(true, englishDdp, englishTrueHd))
        assertTrue(tunneledAudioSwitchNeedsRebuild(true, englishDdp, commentaryStereo))
    }

    @Test
    fun `an unknown current format rebuilds to stay safe`() {
        assertTrue(tunneledAudioSwitchNeedsRebuild(true, null, spanishDdp))
        assertTrue(tunneledAudioSwitchNeedsRebuild(true, englishDdp.copy(codec = null), spanishDdp))
    }
}
