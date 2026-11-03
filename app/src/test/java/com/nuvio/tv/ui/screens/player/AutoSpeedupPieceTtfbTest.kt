package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedupPieceTtfbTest {

    private val mb = 1024L * 1024L
    private val large = 256L * mb

    @After
    fun tearDown() {
        AutoSpeedup.startHint("", 0L)
        AutoSpeedup.end()
        HostSpeeds.clearForTest()
    }

    private fun mbit(n: Double) = (n * 1_000_000 / 8).toLong()

    private fun piece(atMs: Long, ttfbMs: Long, flowBps: Long, reused: Boolean? = true, redirected: Boolean = false,
                      bytes: Long = 8L * mb) =
        PieceTiming(atMs, ttfbMs, bytes * 1000L / flowBps, bytes, reused, redirected)

    private fun near(expected: Long, got: Long, percent: Int = 2) =
        assertTrue("expected about $expected, got $got", kotlin.math.abs(got - expected) * 100 <= expected * percent)

    @Test
    fun `the rolling average keeps only the last 30 s`() {
        val times = PieceTimes()
        times.add(piece(0L, 2_000L, mbit(10.0)))
        times.add(piece(10_000L, 500L, mbit(60.0)))
        times.add(piece(20_000L, 600L, mbit(60.0)))
        assertEquals(3, times.averages(20_000L).pieces)
        assertEquals(1_033L, times.averages(20_000L).ttfbMs)
        val later = times.averages(35_000L)
        assertEquals(2, later.pieces)
        assertEquals(550L, later.ttfbMs)
        near(mbit(60.0), later.flowBps)
        assertEquals(0, times.averages(60_000L).pieces)
        assertEquals(-1L, times.averages(60_000L).ttfbMs)
    }

    @Test
    fun `the flowing rate is bytes over transfer time`() {
        val times = PieceTimes()
        times.add(piece(0L, 500L, mbit(20.0)))
        times.add(piece(1_000L, 500L, mbit(60.0)))
        // 16 MB in 3.2 s + 1.07 s, not the mean of 20 and 60.
        near(mbit(30.0), times.averages(1_000L).flowBps)
        // Small ranges (start, tail) say nothing about the flow.
        times.add(piece(1_500L, 500L, mbit(500.0), bytes = 256L * 1024L))
        assertEquals(2, times.averages(1_500L).flowPieces)
    }

    @Test
    fun `reused connections are preferred and a redirect never counts as a wait`() {
        val times = PieceTimes()
        times.add(piece(0L, 1_370L, mbit(60.0), reused = false, redirected = true))
        times.add(piece(1_000L, 1_420L, mbit(60.0), reused = false))
        times.add(piece(2_000L, 520L, mbit(60.0)))
        times.add(piece(3_000L, 540L, mbit(60.0)))
        val a = times.averages(3_000L)
        assertEquals(530L, a.ttfbMs)
        assertEquals(530L, a.reusedTtfbMs)
        assertEquals(1_420L, a.newTtfbMs)
        assertEquals(2, a.reused)
        assertEquals(3, a.timed)
        assertEquals(4, a.flowPieces)
        assertEquals(530L, times.filmTtfbMs())
    }

    @Test
    fun `new connections count when no request reused one`() {
        val times = PieceTimes()
        times.add(piece(0L, 1_400L, mbit(30.0), reused = false))
        times.add(piece(1_000L, 1_200L, mbit(30.0), reused = null))
        assertEquals(1_300L, times.averages(1_000L).ttfbMs)
        assertEquals(-1L, times.averages(1_000L).reusedTtfbMs)
        assertEquals(1_300L, times.filmTtfbMs())
    }

    private fun slowServerRemux(): AutoSpeedupPolicy {
        // A 4K remux from a slow server: 40.6 Mbit/s delivered per download on 8 MB pieces, first byte 1370 ms
        // at the start (through the add-on's redirect); the piece requests themselves waited about 520 ms on reused
        // connections.
        val policy = AutoSpeedupPolicy(10, 8L * mb, smallBox = false)
        policy.pieceBudgetBytes = large
        policy.firstByteMs = 1_370L
        policy.knownPerDownloadBps = mbit(40.62)
        policy.knownPieceBytes = 8L * mb
        return policy
    }

    /** What one download carried while data flowed, from 40.6 Mbit/s delivered with a 520 ms wait per piece. */
    private val trueFlow = (8L * mb * 1000.0 / (8L * mb * 1000.0 / mbit(40.62) - 520.0)).toLong()

    @Test
    fun `the slow server replay reads the rate the pieces really flowed at`() {
        val policy = slowServerRemux()
        // Before: the start's first byte made 40.6 Mbit/s look like 238, now held at twice what was delivered.
        assertEquals(2 * mbit(40.62), policy.rawRateNow())
        policy.onPiece(piece(1_000L, 1_370L, trueFlow, reused = false, redirected = true))
        policy.onPiece(piece(2_000L, 1_420L, trueFlow, reused = false))
        policy.onPiece(piece(3_000L, 520L, trueFlow))
        policy.onPiece(piece(4_000L, 525L, trueFlow))
        policy.onPiece(piece(5_000L, 515L, trueFlow))
        assertEquals(520L, policy.waitMs())
        near(trueFlow, policy.rawRateNow()!!)
        assertTrue(policy.rawRateNow()!! < mbit(65.0))
        val tag = policy.measureTag()
        assertTrue(tag, tag.contains("ttfbMs=520 reusedTtfbMs=520 newTtfbMs=1420 reused=3/4"))
        assertTrue(tag, tag.contains("flowKbps=") && tag.contains("deliveredKbps=") && tag.contains("pieceMb=8"))
    }

    @Test
    fun `a remembered piece wait works the delivered rate back without inflating it`() {
        val policy = slowServerRemux()
        policy.knownFirstByteMs = 520L
        near(trueFlow, policy.rawRateNow()!!)
    }

    @Test
    fun `one timed piece is not enough to replace the delivered rate`() {
        val policy = slowServerRemux()
        policy.onPiece(piece(1_000L, 520L, mbit(300.0)))
        assertEquals(520L, policy.waitMs())
        near((8L * mb * 1000.0 / (8L * mb * 1000.0 / mbit(40.62) - 520.0)).toLong(), policy.rawRateNow()!!)
        assertNull(policy.measuredRawBps())
    }

    /** Plays a film on [downloads] with the pieces timed as on that slow server and returns every piece size change. */
    private fun play(film: Long, downloads: Int, flows: (Long) -> Long, untilMs: Long): List<Long> {
        val policy = AutoSpeedupPolicy(10, 8L * mb, smallBox = false)
        policy.pieceBudgetBytes = large
        policy.firstByteMs = 1_370L
        policy.preset(downloads)
        val changes = mutableListOf<Long>()
        var bytes = 0L
        var now = 0L
        var piece = policy.pieceBytes
        while (now <= untilMs) {
            val flow = flows(now)
            // Each download finishes about one piece a second or two; the waits jitter like the log's 500 to 820 ms.
            repeat(downloads) { i ->
                val ttfb = if ((now / 1_000L + i) % 7 == 0L) 820L else 520L
                policy.onPiece(PieceTiming(now, ttfb, policy.pieceBytes * 1000L / flow, policy.pieceBytes, true, false))
            }
            policy.onSample(AutoSpeedupPolicy.Sample(nowMs = now, aheadMs = 8_000L, loading = false, rebuffers = 0,
                bytes = bytes, fileBitrate = film))
            if (policy.pieceBytes != piece) {
                piece = policy.pieceBytes
                changes += piece
            }
            bytes += downloads * flow / 2
            now += 1_000L
        }
        return changes
    }

    @Test
    fun `suzume holds its piece size on the timed pieces`() {
        // 2 downloads; the old rule read 48 and 118 Mbit/s by turns. The pieces flowed at 24 to 28 Mbit/s.
        val changes = play(32_918_000L, 2, { if ((it / 30_000L) % 2 == 0L) mbit(24.0) else mbit(28.0) }, 600_000L)
        assertTrue("changes $changes", changes.size <= 1)
        assertTrue(changes.all { it > 8L * mb })
    }

    @Test
    fun `avatar holds its piece size on the timed pieces`() {
        // 4 to 6 downloads, each flowing 33 down to 18 Mbit/s on the slow server.
        val flows = listOf(mbit(36.0), mbit(35.0), mbit(24.0), mbit(21.0))
        val changes = play(54_554_000L, 5, { flows[(it / 30_000L).toInt().coerceAtMost(3)] }, 600_000L)
        assertTrue("changes $changes", changes.size <= 1)
        assertTrue(changes.all { it > 8L * mb })
    }

    @Test
    fun `the server is remembered with the piece wait and the piece in use at the end`() {
        val link = "https://syd2-1.download.real-debrid.com/d/remux.mkv"
        AutoSpeedup.startHint(link, 95_000_000L)
        AutoSpeedup.begin(link, 8_000L * mb)
        AutoSpeedup.onFirstByte(1_370L)
        AutoSpeedup.onPiece(piece(1_000L, 520L, mbit(60.0)))
        AutoSpeedup.onPiece(piece(2_000L, 540L, mbit(60.0)))
        AutoSpeedup.onPiece(piece(3_000L, 1_400L, mbit(60.0), reused = false))
        var now = 0L
        var bytes = 0L
        while (now <= 20_000L) {
            AutoSpeedup.onPlayerSample(AutoSpeedupPolicy.Sample(nowMs = now, aheadMs = 8_000L, loading = false,
                rebuffers = 0, bytes = 0L, fileBitrate = 95_000_000L))
            now += 1_000L
            bytes += 3 * mbit(40.0)
            AutoSpeedup.received.set(bytes)
        }
        // Grown to 16 MB after the kept try measured the rate on smaller pieces: 16 MB is what the server gets.
        AutoSpeedup.onPieceInUse(16L * mb) {}
        AutoSpeedup.end()
        val saved = HostSpeeds.get("syd.download.real-debrid.com")!!
        assertEquals(530L, saved.firstByteMs)
        assertEquals(16L * mb, saved.pieceBytes)
    }
}
