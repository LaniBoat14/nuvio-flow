package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeroBackdropRetryTest {
    @Test fun `a backdrop missed during start-up loads on a later try by itself`() = runTest {
        var tries = 0
        val result = retryHeroBackdrop(HERO_BACKDROP_RETRY_DELAYS_MS) {
            tries++
            if (tries < 2) null else "backdrop"
        }
        assertEquals("backdrop", result)
        assertEquals(2, tries)
        assertEquals(4_000L, currentTime)
    }

    @Test fun `tries are bounded when the picture never arrives`() = runTest {
        var tries = 0
        assertNull(retryHeroBackdrop(HERO_BACKDROP_RETRY_DELAYS_MS) { tries++; null })
        assertEquals(HERO_BACKDROP_RETRY_DELAYS_MS.size, tries)
    }

    @Test fun `moving to another card stops the tries`() = runTest {
        var tries = 0
        val job = launch { retryHeroBackdrop(HERO_BACKDROP_RETRY_DELAYS_MS) { tries++; null } }
        advanceTimeBy(1_500L)
        runCurrent()
        job.cancel()
        advanceTimeBy(60_000L)
        assertEquals(1, tries)
        assertTrue(job.isCancelled)
    }

    @Test fun `a late backdrop replaces the text-only stand-in of the same visit`() {
        val visit = HeroScenePresentation<String>().select("cw-1:1280x720")
        val standIn = visit.fallback(visit.ticket, "text only")
        assertFalse(standIn.committed)
        val loaded = standIn.ready(visit.ticket, "with backdrop")
        assertEquals("with backdrop", loaded.displayed)
        assertTrue(loaded.committed)
    }

    @Test fun `a late backdrop from an earlier visit is ignored`() {
        val first = HeroScenePresentation<String>().select("cw-1:1280x720")
        val firstTicket = first.ticket
        val second = first.fallback(firstTicket, "text only").select("cw-2:1280x720")
        assertEquals(second, second.ready(firstTicket, "old backdrop"))
    }
}
