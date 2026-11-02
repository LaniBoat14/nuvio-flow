package com.nuvio.tv.ui.brand

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcIntroSoundGateTest {
    @Test fun soundStartsOnceWhicheverFrameSignalComesFirst() {
        val gate = ArcIntroSound.SoundGate()
        assertTrue(gate.play())
        assertFalse(gate.play())
    }

    @Test fun firstFrameAfterTheFadeBeganDoesNotStartTheSound() {
        val gate = ArcIntroSound.SoundGate()
        assertTrue(gate.fade())
        assertFalse(gate.play())
    }

    @Test fun repeatedFadeKeepsTheFirstOne() {
        val gate = ArcIntroSound.SoundGate()
        assertTrue(gate.play())
        assertTrue(gate.fade())
        assertFalse(gate.fade())
    }

    @Test fun nothingReachesThePlayerAfterRelease() {
        val gate = ArcIntroSound.SoundGate()
        assertTrue(gate.release())
        assertTrue(gate.released)
        assertFalse(gate.release())
        assertFalse(gate.play())
        assertFalse(gate.fade())
    }
}
