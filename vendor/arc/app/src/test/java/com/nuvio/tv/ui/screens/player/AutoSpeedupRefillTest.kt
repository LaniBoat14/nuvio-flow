package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refill boost, requests really running, the film's recent rate and the 2 GB memory plan. */
class AutoSpeedupRefillTest {
    private var now = 0L
    private var bytes = 0L
    private var ahead = 0L
    private var position = 0L
    private var rebuffers = 0
    private var seekAt = 0L
    private var requestMs = 0L
    private var playerBytes = 0L
    private val mb = 1024L * 1024L
    private val log = mutableListOf<String>()
    private val refills = mutableListOf<String>()
    private val notes = mutableListOf<String>()

    /** [seconds] of half-second samples at [kbps] delivered; the buffer moves by [aheadPerSecondMs]. */
    private fun AutoSpeedupPolicy.play(
        seconds: Int,
        kbps: Long,
        aheadPerSecondMs: Long = 0L,
        loading: Boolean = true,
        playing: Boolean = true,
        fileMbps: Long = 80L,
        targetMs: Long = 60_000L,
        requests: Int = downloads,
        filmKbps: Long = fileMbps * 1000L,
        bufferFull: Boolean = false
    ): Int {
        repeat(seconds * 2) {
            now += 500L
            bytes += kbps * 1000L / 8L / 2L
            requestMs += requests * 500L
            if (playing) position += 500L
            ahead = (ahead + aheadPerSecondMs / 2L).coerceAtLeast(0L)
            val mediaMs = (aheadPerSecondMs / 2L + if (playing) 500L else 0L).coerceAtLeast(0L)
            playerBytes += filmKbps * 1000L / 8L * mediaMs / 1000L
            onSample(
                AutoSpeedupPolicy.Sample(
                    now, ahead, loading, rebuffers, bytes, playing = playing, bufferFull = bufferFull,
                    fileBitrate = fileMbps * 1_000_000L, requestMs = requestMs, requests = requests,
                    playerBytes = playerBytes, bufferedMs = position + ahead, bufferTargetMs = targetMs,
                    seekAtMs = seekAt
                )
            )
            lastChange?.let { log += it }
            lastRefill?.let { refills += it }
            lastBoostNote?.let { notes += it }
        }
        return downloads
    }

    private fun seek(toAheadMs: Long = 0L) {
        position += 600_000L
        ahead = toAheadMs
        seekAt = now + 1L
    }

    /** A film running on [n] downloads with a healthy buffer. */
    private fun steady(n: Int, max: Int = 8): AutoSpeedupPolicy {
        val p = AutoSpeedupPolicy(max)
        p.preset(n)
        ahead = 40_000L
        p.play(12, 120_000, loading = false)
        assertEquals(n, p.downloads)
        return p
    }

    @Test
    fun `a long seek goes straight to the most requests without a try`() {
        val p = steady(2)
        seek()
        assertEquals(8, p.play(1, 0))
        assertTrue(log.last(), log.last().startsWith("downloads 2->8 boost seek base=2"))
    }

    @Test
    fun `the boost respects the server's request budget`() {
        val p = steady(2)
        p.roomCap = 5
        seek()
        assertEquals(5, p.play(1, 0))
    }

    @Test
    fun `the boost stays within the piece budget`() {
        val p = steady(2, max = 6)
        p.pieceBytes = 16L * mb
        p.pieceBudgetBytes = 64L * mb
        seek()
        // (n + 1) x 16 MB within 64 MB: three downloads.
        assertEquals(3, p.play(1, 0))
    }

    @Test
    fun `a short seek inside the buffer adds nothing`() {
        val p = steady(2)
        ahead = 30_000L
        seekAt = now + 1L
        assertEquals(2, p.play(5, 120_000, loading = false))
    }

    @Test
    fun `a stall boosts at once`() {
        val p = steady(2)
        ahead = 3_000L
        rebuffers++
        assertEquals(8, p.play(1, 40_000))
        assertTrue(log.last().contains("boost stall"))
    }

    @Test
    fun `a buffer falling under the low mark boosts`() {
        val p = steady(2)
        // 60 s target: low mark 10 s.
        p.play(40, 70_000, aheadPerSecondMs = -800)
        assertEquals(8, p.downloads)
        assertTrue(log.any { it.contains("boost dip") })
    }

    @Test
    fun `a refilled buffer hands the boost back in one step after a comfortable minute`() {
        val p = steady(2)
        seek()
        p.play(1, 0)
        assertEquals(8, p.downloads)
        p.play(30, 300_000, aheadPerSecondMs = 2_000)
        assertEquals(8, p.downloads)
        assertTrue(refills.any { it.startsWith("refill seek tookMs=") })
        p.play(62, 120_000, loading = false)
        assertEquals(2, p.downloads)
        assertTrue(log.last().contains("comfortable after the refill"))
        // Stays there while comfortable.
        assertEquals(2, p.play(120, 120_000, loading = false))
    }

    @Test
    fun `a boost that does not grow the buffer goes back and waits`() {
        val p = steady(2)
        seek()
        p.play(1, 0)
        assertEquals(8, p.downloads)
        p.play(16, 70_000, aheadPerSecondMs = 0)
        assertTrue(log.any { it.startsWith("downloads 8->2 boost no gain") })
        // Another seek within the wait boosts nothing.
        log.clear()
        seek()
        p.play(5, 70_000)
        assertTrue(log.none { it.contains("boost") })
    }

    @Test
    fun `falling short again soon after a hand-back keeps one more`() {
        val p = steady(2)
        seek()
        p.play(1, 0)
        p.play(30, 300_000, aheadPerSecondMs = 2_000)
        p.play(62, 120_000, loading = false)
        assertEquals(2, p.downloads)
        p.play(70, 70_000, aheadPerSecondMs = -800)
        assertEquals(8, p.downloads)
        p.play(20, 300_000, aheadPerSecondMs = 2_000)
        p.play(62, 120_000, loading = false)
        assertEquals(3, p.downloads)
    }

    @Test
    fun `no oscillation while a refilled film plays steadily`() {
        val p = steady(2)
        seek()
        p.play(1, 0)
        p.play(30, 300_000, aheadPerSecondMs = 2_000)
        p.play(62, 120_000, loading = false)
        val changes = log.size
        p.play(600, 120_000, loading = false)
        assertEquals(changes, log.size)
        assertEquals(2, p.downloads)
    }

    @Test
    fun `a known rate per request boosts only as many as regain the buffer in ten seconds`() {
        val p = AutoSpeedupPolicy(8)
        p.knownPerDownloadBps = 4_000_000L
        p.firstByteMs = 0L
        p.preset(2)
        ahead = 40_000L
        p.play(12, 64_000, loading = false, fileMbps = 40L)
        seek()
        // 5 MB/s film, 27 s of buffer to regain in 10 s: 18.5 MB/s, five requests of 4 MB/s.
        assertEquals(5, p.play(1, 0, fileMbps = 40L))
    }

    @Test
    fun `a seek boost left out by a stale rate per request goes ahead once the buffer stays low`() {
        val p = AutoSpeedupPolicy(8)
        p.knownPerDownloadBps = 10_000_000L
        p.firstByteMs = 0L
        p.preset(4)
        ahead = 40_000L
        p.play(12, 300_000, loading = false, fileMbps = 65L)
        seek()
        // 8.1 MB/s film, 30 s to regain in 10 s: 32.5 MB/s, which four requests of 10 MB/s seem to carry.
        assertEquals(4, p.play(1, 0, fileMbps = 65L))
        assertTrue(notes.joinToString(), notes.any { it.startsWith("boost seek not needed downloads=4") })
        // The server answers slowly after the seek: the buffer barely grows.
        p.play(6, 70_000, aheadPerSecondMs = 100, fileMbps = 65L)
        assertEquals(8, p.downloads)
        assertTrue(log.last(), log.last().startsWith("downloads 4->8 boost check base=4"))
    }

    @Test
    fun `a short seek inside the buffer is not boosted by the check either`() {
        val p = steady(2)
        ahead = 30_000L
        seekAt = now + 1L
        assertEquals(2, p.play(8, 120_000, loading = false))
        assertTrue(log.none { it.contains("boost") })
    }

    @Test
    fun `without the buffer's size nothing is boosted`() {
        val p = steady(2)
        seek()
        assertEquals(2, p.play(1, 0, targetMs = 0L))
    }

    @Test
    fun `the first look at a film is not taken for a seek`() {
        val p = AutoSpeedupPolicy(8)
        seekAt = 5L
        now = 10L
        assertEquals(1, p.play(1, 0, fileMbps = 0L))
        assertTrue(log.none { it.contains("boost") })
    }

    @Test
    fun `refill lowMark and goal follow what the box holds`() {
        assertEquals(4_000L, AutoSpeedupPolicy.lowMarkMs(9_000L))
        assertEquals(10_000L, AutoSpeedupPolicy.lowMarkMs(120_000L))
        assertEquals(8_100L, AutoSpeedupPolicy.refillGoalMs(9_000L))
        assertEquals(30_000L, AutoSpeedupPolicy.refillGoalMs(120_000L))
        assertEquals(3L * 1_000_000L, AutoSpeedupPolicy.refillRate(1_000_000L, 10_000L, 30_000L))
    }

    @Test
    fun `a try shares its rate by the requests really running, read-ahead included`() {
        val p = AutoSpeedupPolicy(3)
        ahead = 5_000L
        p.play(9, 30_000, targetMs = 0L, fileMbps = 0L, requests = 1)
        assertEquals(2, p.downloads)
        p.play(21, 48_000, targetMs = 0L, fileMbps = 0L, requests = 6)
        assertEquals(2, p.downloads)
        // 6 MB/s over six requests, not over two downloads.
        assertEquals(1_000_000.0, p.measuredPerDownloadBps.toDouble(), 20_000.0)
    }

    @Test
    fun `requests between two samples come from the running total`() {
        val p = AutoSpeedupPolicy(3)
        p.play(10, 30_000, targetMs = 0L, fileMbps = 0L, requests = 4)
        assertEquals(4.0, p.requestsBetween(now - 4_000L, now)!!, 0.01)
        val q = AutoSpeedupPolicy(3)
        q.onSample(AutoSpeedupPolicy.Sample(0L, 0L, true, 0, 0L))
        q.onSample(AutoSpeedupPolicy.Sample(500L, 0L, true, 0, 0L))
        assertNull(q.requestsBetween(0L, 500L))
    }

    @Test
    fun `a try is judged on its median rate so one quick piece does not keep it`() {
        val p = AutoSpeedupPolicy(3)
        ahead = 5_000L
        p.play(9, 30_000, targetMs = 0L, fileMbps = 0L)
        assertEquals(2, p.downloads)
        // One burst of 2 s at 200 Mbit/s inside a try that is otherwise no faster.
        p.play(6, 31_000, targetMs = 0L, fileMbps = 0L)
        p.play(2, 200_000, targetMs = 0L, fileMbps = 0L)
        p.play(14, 31_000, targetMs = 0L, fileMbps = 0L)
        assertEquals(1, p.downloads)
        assertTrue(log.toString(), log.any { it.contains("no gain") })
    }

    @Test
    fun `a try for one more download runs at least twenty seconds`() {
        val p = AutoSpeedupPolicy(3)
        ahead = 5_000L
        p.play(9, 30_000, targetMs = 0L, fileMbps = 0L)
        assertEquals(2, p.downloads)
        p.play(15, 40_000, targetMs = 0L, fileMbps = 0L)
        assertTrue(log.none { it.startsWith("kept") })
        p.play(10, 40_000, targetMs = 0L, fileMbps = 0L)
        assertTrue(log.any { it.startsWith("kept downloads=2") })
    }

    @Test
    fun `the target follows a busy stretch of the film`() {
        val p = AutoSpeedupPolicy(8)
        ahead = 20_000L
        // The file averages 40 Mbit/s; the last minute of media ran at 90.
        p.play(70, 120_000, loading = false, fileMbps = 40L, filmKbps = 90_000L, targetMs = 0L)
        assertEquals(90_000_000.0, p.recentFilmBps.toDouble(), 500_000.0)
        assertEquals(90_000_000L / 8 * 120 / 100, p.targetNow(40_000_000L), 200_000L)
        // A quiet stretch leaves the average's 1.5 times.
        val q = AutoSpeedupPolicy(8)
        q.play(70, 120_000, loading = false, fileMbps = 40L, filmKbps = 20_000L, targetMs = 0L)
        assertEquals(AutoSpeedupPolicy.targetBps(40_000_000L), q.targetNow(40_000_000L))
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Long) =
        assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())

    @Test
    fun `no hand-back below what the busy scenes need`() {
        val p = AutoSpeedupPolicy(8)
        p.knownPerDownloadBps = 5_000_000L
        p.firstByteMs = 0L
        p.preset(3)
        ahead = 40_000L
        // Busy scenes at 90 Mbit/s: 13.5 MB/s with margin, two requests of 5 MB/s carry 10.
        p.play(130, 120_000, loading = false, fileMbps = 40L, filmKbps = 90_000L, targetMs = 0L)
        assertEquals(3, p.downloads)
        val q = AutoSpeedupPolicy(8)
        q.knownPerDownloadBps = 5_000_000L
        q.firstByteMs = 0L
        q.preset(3)
        q.play(130, 120_000, loading = false, fileMbps = 40L, filmKbps = 30_000L, targetMs = 0L)
        assertTrue(q.downloads < 3)
    }

    @Test
    fun `the film rate starts again after a seek`() {
        val r = FilmRate()
        r.note(0L, 0L)
        r.note(10L * mb, 20_000L)
        assertEquals(0L, r.bps())
        r.note(20L * mb, 40_000L)
        assertEquals(20L * mb * 8_000L / 40_000L, r.bps())
        r.note(21L * mb, 600_000L)
        assertEquals(0L, r.bps())
    }

    @Test
    fun `the film rate keeps only the last minute of media`() {
        val r = FilmRate()
        var b = 0L
        for (i in 0..200) {
            b += if (i < 100) 1_000_000L else 3_000_000L
            r.note(b, i * 1_000L)
        }
        assertEquals(24_000_000.0, r.bps().toDouble(), 600_000.0)
    }

    @Test
    fun `on 2 GB the read-ahead takes what the piece budget leaves, one per download at most`() {
        assertEquals(2, AutoSpeedupPolicy.lentReadAhead(5, 8L * mb, 64L * mb, 100L * mb))
        assertEquals(0, AutoSpeedupPolicy.lentReadAhead(3, 16L * mb, 64L * mb, 100L * mb))
        assertEquals(2, AutoSpeedupPolicy.lentReadAhead(2, 8L * mb, 64L * mb, 100L * mb))
        assertEquals(0, AutoSpeedupPolicy.lentReadAhead(5, 8L * mb, 64L * mb, -1L))
        assertEquals(0, AutoSpeedupPolicy.lentReadAhead(1, 8L * mb, 64L * mb, 100L * mb))
    }

    @Test
    fun `the buffer's seconds come from the max buffer or its memory, whichever runs out first`() {
        assertEquals(12_631L, AutoSpeedup.bufferTargetMs(50_000L, 150_000_000L, 95_000_000L))
        assertEquals(50_000L, AutoSpeedup.bufferTargetMs(50_000L, 1_000L * mb, 80_000_000L))
        assertEquals(50_000L, AutoSpeedup.bufferTargetMs(50_000L, 0L, 0L))
        assertEquals(0L, AutoSpeedup.bufferTargetMs(0L, 0L, 80_000_000L))
    }

    @Test
    fun `the request meter counts request-milliseconds`() {
        var t = 0L
        val m = RequestMeter { t }
        m.started()
        m.started()
        t = 1_000L
        assertEquals(2_000L, m.requestMs())
        m.ended()
        t = 2_000L
        assertEquals(3_000L, m.requestMs())
        m.ended()
        assertEquals(0, m.running())
        assertEquals(3_000L, m.requestMs())
    }
}
