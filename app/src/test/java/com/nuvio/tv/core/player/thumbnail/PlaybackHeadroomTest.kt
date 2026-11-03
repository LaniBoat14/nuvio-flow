package com.nuvio.tv.core.player.thumbnail

import com.nuvio.tv.ui.screens.player.AutoSpeedupPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thumbnail lanes while the film plays give way to playback's buffer. */
class PlaybackHeadroomTest {
    private fun PlaybackHeadroom.playing(atMs: Long, aheadMs: Long, loading: Boolean = true): Boolean =
        note(atMs, playing = true, buffering = false, loading = loading, aheadMs = aheadMs, toEnd = false)

    @Test fun aComfortableBufferLeavesEveryLane() {
        val h = PlaybackHeadroom()
        assertFalse(h.playing(0L, 20_000L))
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        assertEquals(3, PlaybackHeadroom(max = 3).lanes)
    }

    @Test fun aLowBufferMeansNoneAtOnce() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L)
        assertTrue(h.playing(1_000L, 4_100L))
        assertEquals(0, h.lanes)
        assertEquals("buffer 4.1 s", h.why)
    }

    @Test fun bufferingMeansNone() {
        val h = PlaybackHeadroom()
        assertTrue(h.note(0L, playing = true, buffering = true, loading = true, aheadMs = 30_000L, toEnd = false))
        assertEquals(0, h.lanes)
        assertEquals("playback buffering", h.why)
    }

    @Test fun aBufferFallingFastWhileLoadingMeansNone() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L)
        h.playing(2_000L, 18_500L)
        h.playing(4_000L, 17_000L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        assertTrue(h.playing(5_000L, 15_900L))
        assertEquals(0, h.lanes)
        assertEquals("buffer 15.9 s, down 4.1 s in 6 s", h.why)
    }

    @Test fun aBufferDrainingWhilePlaybackDoesNotLoadIsNormal() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L, loading = false)
        h.playing(3_000L, 17_000L, loading = false)
        h.playing(5_000L, 15_000L, loading = false)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        // Loading starts again: the drain before it is not counted.
        h.playing(6_000L, 14_000L)
        h.playing(8_000L, 13_000L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun aSlowFallWhileLoadingKeepsTheLanes() {
        val h = PlaybackHeadroom()
        for (i in 0..10) h.playing(i * 1_000L, 20_000L - i * 300L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun onlyFallsWithinTheWindowCount() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L)
        h.playing(7_000L, 17_000L)
        h.playing(10_000L, 15_500L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun stepsBackUpOneAtATimeOnceTheBufferIsBack() {
        val h = PlaybackHeadroom()
        h.playing(0L, 20_000L, loading = false)
        h.playing(1_000L, 3_000L, loading = false)
        assertEquals(0, h.lanes)
        assertFalse(h.playing(2_000L, 9_000L))
        assertEquals(0, h.lanes)
        assertTrue(h.playing(4_000L, 9_000L))
        assertEquals(1, h.lanes)
        h.playing(5_000L, 10_000L, loading = false)
        assertEquals(1, h.lanes)
        h.playing(7_000L, 10_000L, loading = false)
        assertEquals(2, h.lanes)
        // Trouble again: none at once.
        h.playing(7_500L, 5_000L, loading = false)
        assertEquals(0, h.lanes)
    }

    @Test fun aBufferJustAboveLowIsNotBackYet() {
        val h = PlaybackHeadroom()
        h.playing(0L, 3_000L)
        for (t in 1..10) h.playing(t * 3_000L, 7_000L, loading = false)
        assertEquals(0, h.lanes)
        h.playing(40_000L, 8_000L, loading = false)
        assertEquals(1, h.lanes)
    }

    @Test fun pausedOrBufferedToTheEndIsNotLimited() {
        val h = PlaybackHeadroom()
        h.playing(0L, 2_000L)
        assertTrue(h.note(1_000L, playing = false, buffering = false, loading = true, aheadMs = 2_000L, toEnd = false))
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
        h.playing(2_000L, 2_000L)
        assertEquals(0, h.lanes)
        h.note(3_000L, playing = true, buffering = false, loading = false, aheadMs = 2_000L, toEnd = true)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    private fun lineOf(
        filmBps: Long = 80_000_000L,
        deliveredBps: Long = 120_000_000L,
        requests: Int = 5,
        needed: Int = requests,
        limit: Int = 8,
        other: Int = 0,
        rested: Boolean = false,
        seekAtMs: Long = 0L,
        refusedAtMs: Long = Long.MIN_VALUE / 2,
        spareBytes: Long = 0L,
    ) = PlaybackLine.State(0L, 60_000L, filmBps, deliveredBps, requests, rested, needed, limit, other, "real-debrid.com",
        seekAtMs, refusedAtMs, spareBytes)

    /** A headroom judged on [line], sampled every second while the film plays and loads. */
    private class Run(var line: PlaybackLine.State, refused: Boolean = false, hostRoom: Int = 8) {
        var refusedNow = refused
        val h = PlaybackHeadroom(line = { line }, refused = { refusedNow }, hostRoom = { hostRoom })
        var t = 0L
        var ahead = 20_000L
        fun play(seconds: Int, aheadStepMs: Long = 0L, loading: Boolean = true) {
            repeat(seconds) {
                t += 1_000L
                ahead += aheadStepMs
                h.note(t, playing = true, buffering = false, loading = loading, aheadMs = ahead, toEnd = false)
            }
        }
    }

    @Test fun realDebridSurplusIsWhatThePlanLeaves() {
        // 30 Mbit/s per request, 0.73 s before each piece, an 80 Mbit/s remux.
        val plan = AutoSpeedupPolicy.planPieces(730L, 30_000_000L / 8, AutoSpeedupPolicy.targetBps(80_000_000L),
            512L * 1024 * 1024, 8)!!
        assertEquals(5, plan.downloads)
        val line = lineOf(requests = 5, needed = plan.downloads, deliveredBps = 5 * 30_000_000L)
        assertEquals(3, PlaybackHeadroom.filmRoom(line))
        assertTrue(PlaybackHeadroom.rateCarries(line))
        val run = Run(line)
        // Good from the first sample (1 s): the first lane after 10 s of it, one more every 3 s.
        run.play(10)
        assertEquals(0, run.h.lanes)
        run.play(1)
        assertEquals(1, run.h.lanes)
        run.play(6)
        assertEquals(3, run.h.lanes)
        assertTrue(run.h.why, run.h.why.startsWith("surplus: downloads 150 of 80 Mbit/s, buffer 20.0 s steady, room 3 of 8"))
    }

    @Test fun playbackRunningMoreThanThePlanNeedsKeepsItsRequests() {
        assertEquals(1, PlaybackHeadroom.filmRoom(lineOf(requests = 7, needed = 5)))
        assertEquals(2, PlaybackHeadroom.filmRoom(lineOf(requests = 4, needed = 6)))
    }

    @Test fun torBoxSharesItsAccountTotalWithANextEpisode() {
        assertEquals(2, PlaybackHeadroom.filmRoom(lineOf(requests = 5, needed = 4, other = 1)))
        // A server that answered "too many requests" at 8 is held to 7.
        assertEquals(1, PlaybackHeadroom.filmRoom(lineOf(requests = 5, needed = 4, other = 1, limit = 7)))
        val run = Run(lineOf(requests = 6, needed = 6, other = 2))
        run.play(20)
        assertEquals(0, run.h.lanes)
        assertTrue(run.h.why, run.h.why.startsWith("playback needs its requests, room 0 of 8 (playback 6, needs 6, other 2)"))
    }

    @Test fun theRateMustCarryTheFilmWithMargin() {
        assertFalse(PlaybackHeadroom.rateCarries(lineOf(filmBps = 80_000_000L, deliveredBps = 100_000_000L)))
        assertTrue(PlaybackHeadroom.rateCarries(lineOf(filmBps = 80_000_000L, deliveredBps = 104_000_000L)))
        assertTrue(PlaybackHeadroom.rateCarries(lineOf(deliveredBps = 0L, rested = true)))
        assertFalse(PlaybackHeadroom.rateCarries(lineOf(filmBps = 0L)))
        val run = Run(lineOf(deliveredBps = 100_000_000L))
        run.play(30)
        assertEquals(0, run.h.lanes)
        assertEquals("buffer 20.0 s, downloads 100 of 80 Mbit/s, under 1.3x", run.h.why)
    }

    @Test fun aBufferOfAnySizeWillDoWhileItDoesNotFall() {
        val run = Run(lineOf())
        run.ahead = 5_000L
        run.play(15)
        assertEquals(2, run.h.lanes)
        run.ahead = 2_900L
        run.play(1)
        assertEquals(0, run.h.lanes)
        assertEquals("buffer 2.9 s under 3.0 s", run.h.why)
    }

    @Test fun stopsAtOnceWhenTheBufferFallsWhileLoading() {
        val run = Run(lineOf())
        run.play(20)
        assertTrue(run.h.lanes > 0)
        val gaveWay = run.h.gaveWay
        run.play(2, aheadStepMs = -600L)
        assertEquals(0, run.h.lanes)
        assertEquals(gaveWay + 1, run.h.gaveWay)
        assertTrue(run.h.why, run.h.why.startsWith("buffer 18.8 s, down 1.2 s in 10 s"))
    }

    @Test fun aBufferDrainingWhileTheLoaderRestsIsNotAFall() {
        val run = Run(lineOf(deliveredBps = 0L, rested = true))
        run.play(20, aheadStepMs = -500L, loading = false)
        assertEquals(3, run.h.lanes)
        assertTrue(run.h.why, run.h.why.startsWith("surplus: loader resting, buffer 10.0 s full"))
    }

    @Test fun stopsAtOnceOnASeekARefusalOrBuffering() {
        val run = Run(lineOf())
        run.play(20)
        assertTrue(run.h.lanes > 0)
        run.line = run.line.copy(seekAtMs = run.t)
        run.play(1)
        assertEquals(0, run.h.lanes)
        assertEquals("a seek", run.h.why)

        run.play(20)
        assertTrue(run.h.lanes > 0)
        run.line = run.line.copy(refusedAtMs = run.t)
        run.play(1)
        assertEquals(0, run.h.lanes)
        assertEquals("too many requests", run.h.why)
        run.play(14)
        assertEquals(0, run.h.lanes)

        val thumbs429 = Run(lineOf())
        thumbs429.play(20)
        thumbs429.refusedNow = true
        thumbs429.play(1)
        assertEquals(0, thumbs429.h.lanes)

        val rebuffer = Run(lineOf())
        rebuffer.play(20)
        rebuffer.h.note(21_000L, playing = true, buffering = true, loading = true, aheadMs = 0L, toEnd = false)
        assertEquals(0, rebuffer.h.lanes)
        assertEquals("playback buffering", rebuffer.h.why)
    }

    @Test fun lessRoomTakesALaneOnlyAfterFiveSecondsAndLetsTheReadsFinish() {
        val run = Run(lineOf(requests = 5, needed = 5))
        run.play(20)
        assertEquals(3, run.h.lanes)
        val gaveWay = run.h.gaveWay
        run.line = run.line.copy(requests = 7)
        run.play(4)
        assertEquals(3, run.h.lanes)
        assertTrue(run.h.why, run.h.why.endsWith(", 2 over for 3.0 s"))
        run.play(1)
        assertEquals(3, run.h.lanes)
        run.play(1)
        assertEquals(1, run.h.lanes)
        assertEquals(gaveWay, run.h.gaveWay)
        // No room at all: playback took its requests back, so the reads under way stop now.
        run.line = run.line.copy(requests = 8)
        run.play(1)
        assertEquals(0, run.h.lanes)
        assertEquals(gaveWay + 1, run.h.gaveWay)
    }

    @Test fun roomMovingBetweenFourAndSevenKeepsTheLanesSteady() {
        // The room moves every few seconds: the lanes hold and no read is cancelled.
        val run = Run(lineOf(requests = 1, needed = 1, limit = 7))
        run.play(40)
        assertEquals(6, run.h.lanes)
        val gaveWay = run.h.gaveWay
        var changes = 0
        var last = run.h.lanes
        val needs = intArrayOf(3, 1, 2, 1, 3, 3, 1, 2, 1, 1, 3, 2)
        repeat(25) { round ->
            run.line = run.line.copy(needed = needs[round % needs.size])
            run.play(3)
            if (run.h.lanes != last) changes++
            last = run.h.lanes
        }
        assertEquals(gaveWay, run.h.gaveWay)
        assertTrue("lanes changed $changes times", changes <= 4)
        assertTrue(run.h.lanes >= 4)
    }

    @Test fun roomShortForFiveSecondsThenBackWaitsBeforeAddingALane() {
        val run = Run(lineOf(requests = 1, needed = 1, limit = 7))
        run.play(40)
        assertEquals(6, run.h.lanes)
        run.line = run.line.copy(needed = 2)
        run.play(5)
        assertEquals(6, run.h.lanes)
        run.play(1)
        assertEquals(5, run.h.lanes)
        run.line = run.line.copy(needed = 1)
        run.play(10)
        assertEquals(5, run.h.lanes)
        run.play(1)
        assertEquals(6, run.h.lanes)
        // A hard stop clears it: the lanes ramp up every 3 s again after the 10 good seconds.
        run.line = run.line.copy(seekAtMs = run.t)
        run.play(1)
        assertEquals(0, run.h.lanes)
        run.play(11)
        assertEquals(1, run.h.lanes)
        run.play(3)
        assertEquals(2, run.h.lanes)
    }

    @Test fun aRateUnderTheMarginStopsNewReadsButDoesNotCancel() {
        val run = Run(lineOf())
        run.play(20)
        assertEquals(3, run.h.lanes)
        val gaveWay = run.h.gaveWay
        run.line = run.line.copy(deliveredBps = 100_000_000L)
        run.play(1)
        assertEquals(0, run.h.lanes)
        assertEquals(gaveWay, run.h.gaveWay)
    }

    @Test fun hardStopsCancelTheReadsUnderWay() {
        val seek = Run(lineOf())
        seek.play(20)
        seek.line = seek.line.copy(seekAtMs = seek.t)
        seek.play(1)
        assertEquals(1, seek.h.gaveWay)

        val refusal = Run(lineOf())
        refusal.play(20)
        refusal.line = refusal.line.copy(refusedAtMs = refusal.t)
        refusal.play(1)
        assertEquals(1, refusal.h.gaveWay)

        // A rebuffer: the player is not playing while it buffers.
        val rebuffer = Run(lineOf())
        rebuffer.play(20)
        rebuffer.h.note(21_000L, playing = false, buffering = true, loading = true, aheadMs = 0L, toEnd = false)
        assertEquals(0, rebuffer.h.lanes)
        assertEquals(1, rebuffer.h.gaveWay)

        // Buffering after a pause (a seek while paused) is not playback's reads being in the way.
        val paused = Run(lineOf())
        paused.h.note(0L, playing = false, buffering = false, loading = false, aheadMs = 30_000L, toEnd = false)
        paused.h.note(1_000L, playing = false, buffering = true, loading = true, aheadMs = 0L, toEnd = false)
        assertEquals(0, paused.h.gaveWay)
    }

    @Test fun anAddOnLinkRedirectingToTheCdnCountsAsTheFilmsServer() {
        // The film: an add-on link that redirects to one numbered node of the CDN.
        val line = lineOf(requests = 5, needed = 5, limit = 7).copy(
            filmKey = "cee11.cdn.example.com", linkKey = "addon.example.org", filmServer = "cee.cdn.example.com")
        assertTrue(PlaybackHeadroom.onFilmServer("https://cee11.cdn.example.com/dl/film.mkv", line))
        assertTrue(PlaybackHeadroom.onFilmServer("https://cee7.cdn.example.com/dl/copy.mkv", line))
        assertTrue(PlaybackHeadroom.onFilmServer("https://addon.example.org/key/play/abc", line))
        assertTrue(PlaybackHeadroom.onFilmServer(null, line))
        assertFalse(PlaybackHeadroom.onFilmServer("https://other-cdn.example.net/copy.mkv", line))

        for (source in listOf("https://cee7.cdn.example.com/dl/copy.mkv", "https://addon.example.org/key/play/abc")) {
            val run = Run(line, hostRoom = 7)
            run.h.source = source
            run.play(30)
            assertEquals(source, 2, run.h.lanes)
            assertTrue(run.h.why, run.h.why.endsWith("room 2 of 7 (playback 5, needs 5)"))
        }
    }

    @Test fun theFilmsOwnFileIsAlwaysOnTheFilmsServer() {
        // The film's ledger key is not known from the link the worker reads (an add-on before its first redirect).
        val run = Run(lineOf(requests = 5, needed = 5, limit = 7).copy(filmKey = "cee11.cdn.example.com"), hostRoom = 7)
        run.h.filmFile = true
        run.h.source = "https://addon.example.org/play/abc"
        run.play(30)
        assertEquals(2, run.h.lanes)
        assertTrue(run.h.why, run.h.why.endsWith("room 2 of 7 (playback 5, needs 5)"))
    }

    @Test fun noFlappingLanesComeBackOnlyAfterTenGoodSeconds() {
        val run = Run(lineOf())
        run.play(20)
        assertEquals(3, run.h.lanes)
        // The rate dips under 1.3x every few seconds: lanes stay off until it holds for 10 s.
        repeat(4) {
            run.line = run.line.copy(deliveredBps = 100_000_000L)
            run.play(1)
            assertEquals(0, run.h.lanes)
            run.line = run.line.copy(deliveredBps = 120_000_000L)
            run.play(8)
            assertEquals(0, run.h.lanes)
        }
        run.play(3)
        assertEquals(1, run.h.lanes)
        run.play(2)
        assertEquals(1, run.h.lanes)
        run.play(1)
        assertEquals(2, run.h.lanes)
    }

    @Test fun backFromAPauseNeedsNoWait() {
        val run = Run(lineOf())
        run.h.note(0L, playing = false, buffering = false, loading = false, aheadMs = 30_000L, toEnd = false)
        assertEquals(ThumbFetchLanes.MAX, run.h.lanes)
        run.play(1)
        assertEquals(3, run.h.lanes)
    }

    @Test fun aCopyOnAnotherServerUsesThatServersOwnLimit() {
        val run = Run(lineOf(requests = 8, needed = 8), hostRoom = 6)
        run.h.source = "https://other-cdn.example.net/copy.mkv"
        run.play(11)
        assertEquals(1, run.h.lanes)
        run.play(15)
        assertEquals(6, run.h.lanes)
        assertTrue(run.h.why, run.h.why.endsWith("room 6 on the copy's server"))
        // Still behind the film's rate and buffer: the home line is shared.
        run.line = run.line.copy(deliveredBps = 90_000_000L)
        run.play(1)
        assertEquals(0, run.h.lanes)
        // The film's own server counts against the film's surplus.
        val same = Run(lineOf(requests = 8, needed = 8), hostRoom = 6)
        same.h.source = "https://real-debrid.com/d/abc"
        same.play(25)
        assertEquals(0, same.h.lanes)
    }

    @Test fun aStripDecodeWhilePlayingNeedsPlaybacksUnusedPieces() {
        assertFalse(PlaybackHeadroom.stripFits(null))
        assertFalse(PlaybackHeadroom.stripFits(lineOf(spareBytes = 16L * 1024 * 1024)))
        assertTrue(PlaybackHeadroom.stripFits(lineOf(spareBytes = 32L * 1024 * 1024)))
    }

    @Test fun ratesNotKnownYetWait() {
        val run = Run(lineOf(deliveredBps = 0L))
        run.play(30)
        assertEquals(0, run.h.lanes)
        assertEquals("the downloads' rate not known yet", run.h.why)
    }

    @Test fun withoutAutomaticTheOldMarkStays() {
        val h = PlaybackHeadroom(line = { null })
        h.playing(0L, 7_000L)
        assertEquals(ThumbFetchLanes.MAX, h.lanes)
    }

    @Test fun secondsForTheLog() {
        assertEquals("4.1 s", PlaybackHeadroom.seconds(4_150L))
        assertEquals("0.0 s", PlaybackHeadroom.seconds(-5L))
        assertEquals("12.0 s", PlaybackHeadroom.seconds(12_000L))
    }
}
