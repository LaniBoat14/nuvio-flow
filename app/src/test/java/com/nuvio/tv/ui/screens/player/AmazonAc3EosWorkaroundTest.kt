package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.PlaybackSpeedAwareAudioRenderer.Companion.needsAmazonAc3EosWorkaround
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonAc3EosWorkaroundTest {

    @Test
    fun fireTvDolbyAc3_onAndroid10OrOlder_skipsEndOfStream() {
        assertTrue(needsAmazonAc3EosWorkaround(28, "Amazon", "AFTKA", AC3))
        assertTrue(needsAmazonAc3EosWorkaround(29, "Amazon", "AFTR", AC3))
        assertTrue(needsAmazonAc3EosWorkaround(25, "Amazon", "AFTMM", AC3))
    }

    @Test
    fun newerFireOs_isLeftAlone() {
        assertFalse(needsAmazonAc3EosWorkaround(30, "Amazon", "AFTKRT", AC3))
        assertFalse(needsAmazonAc3EosWorkaround(34, "Amazon", "AFTKA", AC3))
    }

    @Test
    fun otherDecodersAndDevices_areLeftAlone() {
        assertFalse(needsAmazonAc3EosWorkaround(28, "Amazon", "AFTKA", "OMX.dolby.eac3.decoder"))
        assertFalse(needsAmazonAc3EosWorkaround(28, "Amazon", "AFTKA", "c2.android.aac.decoder"))
        assertFalse(needsAmazonAc3EosWorkaround(28, "Amazon", "KFMAWI", AC3))
        assertFalse(needsAmazonAc3EosWorkaround(28, "NVIDIA", "SHIELD Android TV", AC3))
        assertFalse(needsAmazonAc3EosWorkaround(28, "amazon", "AFTKA", AC3))
        assertFalse(needsAmazonAc3EosWorkaround(28, null, null, AC3))
        assertFalse(needsAmazonAc3EosWorkaround(28, "Amazon", "AFTKA", null))
    }

    private companion object {
        const val AC3 = "OMX.dolby.ac3.decoder"
    }
}
