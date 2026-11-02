package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvArcAudioOutputTest {
    private val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private val hdmi = AudioDeviceInfo.TYPE_HDMI
    private val arc = AudioDeviceInfo.TYPE_HDMI_ARC
    private val earc = AudioDeviceInfo.TYPE_HDMI_EARC

    @Test
    fun tvPlayingOutOfArcOrEarc_showsTheSetting() {
        assertTrue(TvArcAudioOutput.matches("0", earc, listOf(speaker, earc)))
        assertTrue(TvArcAudioOutput.matches("0", arc, listOf(speaker, arc)))
        assertTrue(TvArcAudioOutput.matches("0,5", earc, listOf(speaker, earc)))
    }

    @Test
    fun tvOnItsOwnSpeakers_hidesTheSetting() {
        assertFalse(TvArcAudioOutput.matches("0", speaker, listOf(speaker, earc)))
        assertFalse(TvArcAudioOutput.matches("0", speaker, listOf(speaker)))
    }

    @Test
    fun boxes_neverSeeTheSetting() {
        assertFalse(TvArcAudioOutput.matches("4", hdmi, listOf(hdmi)))
        assertFalse(TvArcAudioOutput.matches("4", hdmi, listOf(hdmi, arc, earc)))
        assertFalse(TvArcAudioOutput.matches("4", earc, listOf(earc)))
        assertFalse(TvArcAudioOutput.matches(null, hdmi, listOf(hdmi, speaker, earc)))
        assertFalse(TvArcAudioOutput.matches(null, null, listOf(hdmi, speaker, arc)))
    }

    @Test
    fun unreadableDeviceType_fallsBackToTheOutputList() {
        assertTrue(TvArcAudioOutput.matches(null, earc, listOf(speaker, earc)))
        assertFalse(TvArcAudioOutput.matches(null, earc, listOf(earc)))
    }

    @Test
    fun unknownRoute_usesArcWhenNoHdmiPortIsListed() {
        assertTrue(TvArcAudioOutput.matches("0", null, listOf(speaker, earc)))
        assertFalse(TvArcAudioOutput.matches("0", null, listOf(speaker)))
    }
}
