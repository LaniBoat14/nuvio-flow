package com.nuvio.tv.ui.screens.detail

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroDownMoveTest {
    private fun move(
        pending: Boolean = true,
        secondaryReady: Boolean = true,
        hasDestination: Boolean = true,
        fastScrolling: Boolean = false,
        moving: Boolean = false,
        waitedMs: Long = 0L
    ) = heroDownMove(pending, secondaryReady, hasDestination, fastScrolling, moving, waitedMs)

    @Test fun `nothing happens without a Down from the hero`() {
        assertEquals(HeroDownMove.NONE, move(pending = false))
        assertEquals(HeroDownMove.NONE, move(pending = false, fastScrolling = true))
    }

    @Test fun `a Down moves focus once the page below is ready`() {
        assertEquals(HeroDownMove.MOVE, move())
    }

    @Test fun `a Down waits while the page below is still loading`() {
        assertEquals(HeroDownMove.WAIT, move(secondaryReady = false))
        assertEquals(HeroDownMove.WAIT, move(hasDestination = false))
    }

    @Test fun `a waiting Down moves when its destination arrives and a cleared one stays cleared`() {
        assertEquals(HeroDownMove.WAIT, move(hasDestination = false))
        assertEquals(HeroDownMove.MOVE, move(hasDestination = true))
        assertEquals(HeroDownMove.NONE, move(pending = false, hasDestination = true))
    }

    @Test fun `a held Down drag keeps the scroll`() {
        assertEquals(HeroDownMove.DROP, move(fastScrolling = true))
        assertEquals(HeroDownMove.DROP, move(fastScrolling = true, secondaryReady = false))
    }

    @Test fun `a second Down during the move is dropped`() {
        assertEquals(HeroDownMove.DROP, move(moving = true))
    }

    @Test fun `waiting for somewhere to go gives up after a short while`() {
        assertEquals(HeroDownMove.WAIT, move(hasDestination = false, waitedMs = HERO_DOWN_DESTINATION_WAIT_MS - 1))
        assertEquals(HeroDownMove.DROP, move(hasDestination = false, waitedMs = HERO_DOWN_DESTINATION_WAIT_MS))
        assertEquals(HeroDownMove.MOVE, move(waitedMs = HERO_DOWN_DESTINATION_WAIT_MS * 4))
    }

    @Test fun `waiting for the page below to load has no cap`() {
        assertEquals(HeroDownMove.WAIT, move(secondaryReady = false, hasDestination = false, waitedMs = 60_000L))
    }

    @Test fun `another key or OK lets a waiting Down go`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER
        ).forEach { assertTrue(cancelsWaitingHeroDown(it)) }
        assertFalse(cancelsWaitingHeroDown(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(cancelsWaitingHeroDown(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    private fun quickTap(
        rapidDown: Boolean = true,
        heroDownPending: Boolean = false,
        heroDownMoving: Boolean = false,
        pressTakenByHero: Boolean = false
    ) = detailQuickTapScrollAllowed(rapidDown, heroDownPending, heroDownMoving, pressTakenByHero)

    @Test fun `quick second Down scrolls as before away from the hero`() {
        assertTrue(quickTap())
        assertFalse(quickTap(rapidDown = false))
    }

    @Test fun `quick second Down leaves the scroll to a hero Down`() {
        assertFalse(quickTap(heroDownPending = true))
        assertFalse(quickTap(heroDownMoving = true))
        assertFalse(quickTap(pressTakenByHero = true))
    }
}
