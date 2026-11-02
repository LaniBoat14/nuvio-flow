package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NightModeLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MpvSoundFiltersTest {

    @Test
    fun `nothing is added when both are off`() {
        assertEquals("", mpvSoundFilterChain(0, NightModeLevel.OFF))
    }

    @Test
    fun `gain alone gets a limiter and no compressor`() {
        val chain = mpvSoundFilterChain(6, NightModeLevel.OFF)
        assertTrue(chain.startsWith("lavfi=[volume=volume=6dB"))
        assertFalse(chain.contains("acompressor"))
        assertTrue(chain.endsWith("alimiter=limit=0.8910:attack=1:release=100:level=0]"))
    }

    @Test
    fun `night mode uses the shared preset numbers`() {
        val chain = mpvSoundFilterChain(0, NightModeLevel.MEDIUM)
        assertFalse(chain.contains("volume="))
        // -15 dB threshold, 3:1, +10 dB make-up
        assertTrue(chain, chain.contains("acompressor=threshold=0.1778:ratio=3.0000"))
        assertTrue(chain, chain.contains("makeup=3.1623"))
    }

    @Test
    fun `surround played as stereo is downmixed in the chain and limited after the downmix`() {
        val chain = mpvSoundFilterChain(0, NightModeLevel.OFF, stereoDownmixBoostDb = 12)
        assertEquals(
            "lavfi=[aresample=lfe_mix_level=0.5000:center_mix_level=2.8150,aformat=channel_layouts=stereo," +
                "alimiter=limit=0.8910:attack=1:release=100:level=0]",
            chain
        )
        val withGain = mpvSoundFilterChain(6, NightModeLevel.LIGHT, stereoDownmixBoostDb = 0)
        assertTrue(withGain, withGain.indexOf("aformat=channel_layouts=stereo") > withGain.indexOf("acompressor"))
        assertTrue(withGain, withGain.indexOf("alimiter") > withGain.indexOf("aformat"))
        assertFalse(withGain.contains("center_mix_level"))
    }

    @Test
    fun `the downmix limiter is only for surround tracks mpv plays out as stereo`() {
        assertTrue(mpvStereoDownmixNeedsLimiter(outputChannels = 2, trackChannels = 6))
        assertFalse(mpvStereoDownmixNeedsLimiter(outputChannels = 6, trackChannels = 6))
        assertFalse(mpvStereoDownmixNeedsLimiter(outputChannels = 2, trackChannels = 2))
        assertFalse(mpvStereoDownmixNeedsLimiter(outputChannels = 0, trackChannels = 8))
    }

    @Test
    fun `numbers use a dot in every locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertFalse(mpvSoundFilterChain(3, NightModeLevel.STRONG).contains(","+"0"))
            assertTrue(mpvSoundFilterChain(3, NightModeLevel.STRONG).contains("0.8910"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
