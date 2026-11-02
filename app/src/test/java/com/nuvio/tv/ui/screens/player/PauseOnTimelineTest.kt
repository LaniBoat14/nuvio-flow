package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseOnTimelineTest {

    private val ok = KeyEvent.KEYCODE_DPAD_CENTER
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    private fun settle(
        pauseRequest: Int = 1,
        seenRequest: Int = 0,
        hasTimeline: Boolean = true,
        thumbnailsOn: Boolean = true,
        pressed: Boolean = false,
        onControl: Boolean = false,
    ) = PauseOnTimeline.afterSettle(pauseRequest, seenRequest, hasTimeline, thumbnailsOn, pressed, onControl)

    @Test
    fun aNewPauseFocusesTheTimeline_onceAndOnlyWithATimeline() {
        assertEquals(PauseOnTimeline.Settle.TIMELINE, settle())
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(pauseRequest = 1, seenRequest = 1))
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(pauseRequest = 2, seenRequest = 1, hasTimeline = false))
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(pauseRequest = 0, seenRequest = 0))
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(pauseRequest = 3, seenRequest = 3))
    }

    @Test
    fun withThumbnailsOff_aPauseStaysOnTheUsualControl() {
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(thumbnailsOn = false))
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(thumbnailsOn = false, hasTimeline = false))
    }

    @Test
    fun aKeyPressedSinceThePauseWins() {
        assertEquals(PauseOnTimeline.Settle.STAY, settle(pressed = true, onControl = true))
        assertEquals(PauseOnTimeline.Settle.STAY, settle(pressed = true, onControl = true, thumbnailsOn = false))
        assertEquals(PauseOnTimeline.Settle.STAY, settle(pressed = true, onControl = true, hasTimeline = false))
    }

    @Test
    fun aKeyThatReachedNoControl_getsTheUsualPlaceNotTheTimeline() {
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(pressed = true, onControl = false))
    }

    @Test
    fun withoutANewPause_keysChangeNothing() {
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(seenRequest = 1, pressed = true, onControl = true))
        assertEquals(PauseOnTimeline.Settle.USUAL, settle(seenRequest = 1, onControl = true))
    }

    @Test
    fun noKeyPressed_focusOnAControlStillMovesToTheTimeline() {
        assertEquals(PauseOnTimeline.Settle.TIMELINE, settle(onControl = true))
    }

    @Test
    fun onlyTheFirstKeyDownOfAHeldOkCounts() {
        assertTrue(PauseOnTimeline.firstPress(down, repeatCount = 0))
        assertFalse(PauseOnTimeline.firstPress(down, repeatCount = 1))
        assertFalse(PauseOnTimeline.firstPress(down, repeatCount = 7))
        assertFalse(PauseOnTimeline.firstPress(up, repeatCount = 0))
    }

    @Test
    fun confirmKeys() {
        assertTrue(PauseOnTimeline.isConfirmKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(PauseOnTimeline.isConfirmKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(PauseOnTimeline.isConfirmKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertFalse(PauseOnTimeline.isConfirmKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertFalse(PauseOnTimeline.isConfirmKey(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun theReleaseOfTheKeyThatPausedIsSwallowed_repeatsPass() {
        assertEquals(PauseOnTimeline.Release.PASS, PauseOnTimeline.release(ok, ok, down, repeatCount = 1))
        assertEquals(PauseOnTimeline.Release.PASS, PauseOnTimeline.release(ok, ok, down, repeatCount = 4))
        assertEquals(PauseOnTimeline.Release.SWALLOW, PauseOnTimeline.release(ok, ok, up, repeatCount = 0))
    }

    @Test
    fun nothingArmed_orAnotherKey_passes() {
        assertEquals(PauseOnTimeline.Release.PASS, PauseOnTimeline.release(-1, ok, up, repeatCount = 0))
        assertEquals(PauseOnTimeline.Release.PASS, PauseOnTimeline.release(ok, KeyEvent.KEYCODE_DPAD_LEFT, up, repeatCount = 0))
        assertEquals(PauseOnTimeline.Release.PASS, PauseOnTimeline.release(ok, KeyEvent.KEYCODE_ENTER, up, repeatCount = 0))
        assertEquals(PauseOnTimeline.Release.PASS, PauseOnTimeline.release(ok, KeyEvent.KEYCODE_ENTER, down, repeatCount = 0))
    }

    @Test
    fun aFreshPressOfTheSameKeyForgetsALostRelease() {
        assertEquals(PauseOnTimeline.Release.FORGET, PauseOnTimeline.release(ok, ok, down, repeatCount = 0))
    }

    @Test
    fun thumbnailFollowsTheGlide_whateverThePlaybackState() {
        assertEquals(90_000L, PauseOnTimeline.thumbnailPositionMs(90_000L, 90_000L, gliding = true, playing = true, barShown = false))
        assertEquals(90_000L, PauseOnTimeline.thumbnailPositionMs(90_000L, 90_000L, gliding = true, playing = false, barShown = true))
    }

    @Test
    fun paused_theLastTapStaysUpWhileTheBarShows() {
        assertEquals(90_000L, PauseOnTimeline.thumbnailPositionMs(90_000L, null, gliding = false, playing = false, barShown = true))
        assertEquals(90_000L, PauseOnTimeline.thumbnailPositionMs(90_000L, 90_000L, gliding = false, playing = false, barShown = true))
        assertNull(PauseOnTimeline.thumbnailPositionMs(90_000L, null, gliding = false, playing = false, barShown = false))
        assertNull(PauseOnTimeline.thumbnailPositionMs(90_000L, 90_000L, gliding = false, playing = false, barShown = false))
    }

    @Test
    fun playing_aTapShowsNothing() {
        assertNull(PauseOnTimeline.thumbnailPositionMs(90_000L, 90_000L, gliding = false, playing = true, barShown = true))
        assertNull(PauseOnTimeline.thumbnailPositionMs(90_000L, null, gliding = false, playing = true, barShown = true))
    }

    @Test
    fun noPosition_noThumbnail() {
        assertNull(PauseOnTimeline.thumbnailPositionMs(null, null, gliding = true, playing = false, barShown = true))
        assertNull(PauseOnTimeline.thumbnailPositionMs(null, null, gliding = false, playing = false, barShown = true))
    }

    @Test
    fun afterTheStrip_thePictureStaysCentredUntilItGoes() {
        var kept = PauseOnTimeline.keepsStripPlace(keptBefore = false, stripShown = true, thumbnailShown = true)
        assertTrue(kept)
        // Key released: the strip ends, the single picture keeps the centre, also for later taps.
        kept = PauseOnTimeline.keepsStripPlace(kept, stripShown = false, thumbnailShown = true)
        assertTrue(kept)
        kept = PauseOnTimeline.keepsStripPlace(kept, stripShown = false, thumbnailShown = true)
        assertTrue(kept)
        kept = PauseOnTimeline.keepsStripPlace(kept, stripShown = false, thumbnailShown = false)
        assertFalse(kept)
        assertFalse(PauseOnTimeline.keepsStripPlace(kept, stripShown = false, thumbnailShown = true))
    }

    @Test
    fun withoutTheStrip_thePictureFollowsTheBar() {
        assertFalse(PauseOnTimeline.keepsStripPlace(keptBefore = false, stripShown = false, thumbnailShown = true))
    }
}
