package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedupPieceSizeTest {

    private val mb = 1024L * 1024L
    private val small = 64L * mb
    private val large = 256L * mb

    @After
    fun tearDown() {
        AutoSpeedup.end()
        HostSpeeds.clearForTest()
    }

    private fun mbit(n: Long) = n * 1_000_000L / 8

    private fun target(filmMbit: Long) = AutoSpeedupPolicy.targetBps(filmMbit * 1_000_000L)

    /** What one download delivers on [piece] pieces with the first-byte wait, from its rate while data flows. */
    private fun delivered(rawBps: Long, piece: Long, firstByteMs: Long): Long =
        (piece * 1000.0 / (piece * 1000.0 / rawBps + firstByteMs)).toLong()

    @Test
    fun `real-debrid with a remux on a 2 GB box stays on six 8 MB downloads`() {
        val plan = AutoSpeedupPolicy.planPieces(700L, mbit(30), target(95), small, 6)!!
        assertEquals(6, plan.downloads)
        assertEquals(8L * mb, plan.pieceBytes)
        // Four 12 MB downloads carry less than six 8 MB ones there.
        assertTrue(AutoSpeedupPolicy.effectiveBps(4, 12L * mb, 700L, mbit(30)) < plan.effectiveBps)
    }

    @Test
    fun `real-debrid with a lighter film on a 2 GB box takes 12 MB pieces`() {
        val plan = AutoSpeedupPolicy.planPieces(700L, mbit(30), target(48), small, 6)!!
        assertEquals(3, plan.downloads)
        assertEquals(12L * mb, plan.pieceBytes)
    }

    @Test
    fun `a quick torbox server needs the fewest downloads on small pieces`() {
        val plan = AutoSpeedupPolicy.planPieces(200L, mbit(100), target(95), small, 6)!!
        assertEquals(2, plan.downloads)
        assertEquals(8L * mb, plan.pieceBytes)
    }

    @Test
    fun `a 1080p film needs two downloads from real-debrid`() {
        assertEquals(2, AutoSpeedupPolicy.planPieces(700L, mbit(30), target(24), small, 6)!!.downloads)
        // One quick download carries it alone.
        assertEquals(1, AutoSpeedupPolicy.planPieces(200L, mbit(100), target(24), small, 6)!!.downloads)
    }

    @Test
    fun `a slow far server takes big pieces on a box with memory`() {
        val plan = AutoSpeedupPolicy.planPieces(1_500L, mbit(15), target(80), large, 10)!!
        assertEquals(24L * mb, plan.pieceBytes)
        assertEquals(9, plan.downloads)
        val lighter = AutoSpeedupPolicy.planPieces(2_000L, mbit(15), target(40), large, 10)!!
        assertEquals(16L * mb, lighter.pieceBytes)
    }

    @Test
    fun `memory keeps the same slow server on small pieces on a 2 GB box`() {
        val plan = AutoSpeedupPolicy.planPieces(1_500L, mbit(15), target(80), small, 6)!!
        assertEquals(6, plan.downloads)
        assertEquals(8L * mb, plan.pieceBytes)
        assertTrue(AutoSpeedupPolicy.lentBytes(plan.downloads, plan.pieceBytes) <= small)
    }

    @Test
    fun `memory counts each download plus the piece being read`() {
        assertEquals(8L * mb, AutoSpeedupPolicy.fittingPiece(6, 24L * mb, small))
        assertEquals(12L * mb, AutoSpeedupPolicy.fittingPiece(4, 24L * mb, small))
        assertEquals(16L * mb, AutoSpeedupPolicy.fittingPiece(3, 24L * mb, small))
        assertEquals(16L * mb, AutoSpeedupPolicy.fittingPiece(2, 24L * mb, small))
        assertEquals(12L * mb, AutoSpeedupPolicy.fittingPiece(2, 12L * mb, small))
    }

    @Test
    fun `held memory is the read-ahead in whole pieces when that is more than the downloads`() {
        val readAhead = 112L * mb
        assertEquals(120L * mb, AutoSpeedupPolicy.heldBytes(6, 8L * mb, readAhead))
        assertEquals(120L * mb, AutoSpeedupPolicy.heldBytes(4, 12L * mb, readAhead))
        assertEquals(128L * mb, AutoSpeedupPolicy.heldBytes(3, 16L * mb, readAhead))
        assertEquals(120L * mb, AutoSpeedupPolicy.heldBytes(2, 24L * mb, readAhead))
        assertEquals(56L * mb, AutoSpeedupPolicy.heldBytes(6, 8L * mb, 0L))
    }

    @Test
    fun `unknown first byte, rate or film leaves the choice to the usual rule`() {
        assertNull(AutoSpeedupPolicy.planPieces(-1L, mbit(30), target(95), small, 6))
        assertNull(AutoSpeedupPolicy.planPieces(700L, 0L, target(95), small, 6))
        assertNull(AutoSpeedupPolicy.planPieces(700L, mbit(30), 0L, small, 6))
        assertNull(AutoSpeedupPolicy.pieceForDownloads(3, -1L, mbit(30), target(95), small))
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.pieceBudgetBytes = small
        assertNull(policy.planFor(95_000_000L))
        assertEquals(AutoSpeedupPolicy.startDownloads(95_000_000L, 6, 8L * mb, true), policy.startFor(95_000_000L))
    }

    @Test
    fun `without a film rate the smallest piece with a short wait is taken`() {
        assertEquals(16L * mb, AutoSpeedupPolicy.pieceForDownloads(2, 700L, mbit(30), 0L, small))
        assertEquals(8L * mb, AutoSpeedupPolicy.pieceForDownloads(2, 50L, mbit(30), 0L, small))
    }

    @Test
    fun `the rate while data flows comes back from what a download delivered`() {
        val raw = mbit(30)
        val got = AutoSpeedupPolicy.rawRate(delivered(raw, 8L * mb, 700L), 8L * mb, 700L)
        assertTrue(kotlin.math.abs(got - raw) * 100 < raw)
    }

    private fun quickTorbox(): AutoSpeedupPolicy {
        val policy = AutoSpeedupPolicy(6, 16L * mb, smallBox = true)
        policy.pieceBudgetBytes = small
        policy.firstByteMs = 200L
        policy.knownPerDownloadBps = delivered(mbit(100), 8L * mb, 200L)
        policy.knownPieceBytes = 8L * mb
        return policy
    }

    @Test
    fun `a measured server never starts a film below the usual start`() {
        val policy = quickTorbox()
        assertEquals(5, policy.startFor(95_000_000L))
        assertEquals(8L * mb, policy.pieceBytes)
    }

    @Test
    fun `a higher usual start gets the piece that count needs`() {
        val policy = AutoSpeedupPolicy(10, 24L * mb, smallBox = false)
        policy.pieceBudgetBytes = large
        policy.firstByteMs = 1_500L
        policy.knownPerDownloadBps = delivered(mbit(15), 8L * mb, 1_500L)
        policy.knownPieceBytes = 8L * mb
        // The plan alone wants 9 x 24 MB; the usual start for this rate is 10, which 12 MB pieces carry.
        assertEquals(10, policy.startFor(80_000_000L))
        assertEquals(12L * mb, policy.pieceBytes)
    }

    @Test
    fun `a quick server's remux later settles to the fewest downloads that carry it`() {
        val policy = quickTorbox()
        val start = policy.startFor(95_000_000L)
        policy.preset(start, 95_000_000L)
        val plan = policy.planFor(95_000_000L)!!
        assertEquals(2, plan.downloads)
        var bytes = 0L
        val perSecond = start * delivered(mbit(100), 8L * mb, 200L)
        var now = 0L
        while (now <= 400_000L) {
            policy.onSample(AutoSpeedupPolicy.Sample(nowMs = now, aheadMs = 40_000L, loading = false, rebuffers = 0,
                bytes = bytes, fileBitrate = 95_000_000L))
            now += 1_000L
            bytes += perSecond
        }
        assertEquals(plan.downloads, policy.downloads)
        assertEquals(8L * mb, policy.pieceBytes)
    }

    private class Run(val policy: AutoSpeedupPolicy, val bytesPerSecond: Long, val filmBps: Long) {
        var nowMs = 0L
        var bytes = 0L
        fun until(ms: Long) {
            while (nowMs <= ms) {
                policy.onSample(AutoSpeedupPolicy.Sample(nowMs = nowMs, aheadMs = 8_000L, loading = false,
                    rebuffers = 0, bytes = bytes, fileBitrate = filmBps))
                nowMs += 1_000L
                bytes += bytesPerSecond
            }
        }
    }

    private fun measured(downloads: Int, budget: Long, firstByteMs: Long, raw: Long, filmMbit: Long): Run {
        val policy = AutoSpeedupPolicy(10, 8L * mb, smallBox = false)
        policy.pieceBudgetBytes = budget
        policy.firstByteMs = firstByteMs
        policy.preset(downloads)
        return Run(policy, downloads * delivered(raw, 8L * mb, firstByteMs), filmMbit * 1_000_000L)
    }

    @Test
    fun `a small gain never changes the piece mid-film`() {
        // 12 MB carries under 10 % more than 8 MB for three Real-Debrid downloads.
        val run = measured(3, small, 700L, mbit(30), 48L)
        run.until(300_000L)
        assertEquals(8L * mb, run.policy.pieceBytes)
    }

    @Test
    fun `the piece grows one step at a time and holds 2 minutes after each`() {
        val run = measured(3, large, 2_000L, mbit(15), 30L)
        run.until(119_000L)
        assertEquals(8L * mb, run.policy.pieceBytes)
        run.until(120_000L)
        assertEquals(12L * mb, run.policy.pieceBytes)
        run.until(239_000L)
        assertEquals(12L * mb, run.policy.pieceBytes)
        run.until(240_000L)
        assertEquals(16L * mb, run.policy.pieceBytes)
        // 24 MB would carry under 10 % more than 16 MB.
        run.until(600_000L)
        assertEquals(16L * mb, run.policy.pieceBytes)
    }

    @Test
    fun `a lighter film never steps the piece back down`() {
        val run = measured(3, large, 2_000L, mbit(15), 30L)
        run.until(130_000L)
        assertEquals(12L * mb, run.policy.pieceBytes)
        val lighter = Run(run.policy, run.bytesPerSecond, 5_000_000L).also { it.nowMs = run.nowMs; it.bytes = run.bytes }
        lighter.until(600_000L)
        assertEquals(12L * mb, run.policy.pieceBytes)
    }

    private fun mbitKbps(kbps: Long) = kbps * 1000L / 8

    /** 8 <-> 12 MB flips on a slow server: what the rule saw every 30 s. */
    private fun replay(film: Long, fb: Long, phases: List<Pair<Int, Long>>, untilMs: Long): List<Long> {
        val sizer = PieceSizer()
        var piece = 8L * mb
        val changes = mutableListOf<Long>()
        var now = 0L
        while (now <= untilMs) {
            val (downloads, kbps) = phases[((now / 30_000L).toInt()).coerceAtMost(phases.size - 1)]
            val step = sizer.next(now, piece, downloads, mbitKbps(kbps), fb, AutoSpeedupPolicy.targetBps(film), large,
                Long.MAX_VALUE, large)
            if (step != null && !step.held) {
                piece = step.to
                changes += piece
            }
            now += 1_000L
        }
        return changes
    }

    @Test
    fun `avatar on the slow server no longer flips the piece`() {
        // 07:53:46 start heavy; 4, 5, 5, 6 downloads at 33443, 32553, 21774, 18470 kbps, first byte 1286 ms.
        val phases = listOf(4 to 33_443L, 5 to 32_553L, 5 to 21_774L, 6 to 18_470L)
        assertEquals(emptyList<Long>(), replay(54_554_000L, 1_286L, phases, 93_000L))
        val longer = replay(54_554_000L, 1_286L, phases, 600_000L)
        assertTrue(longer.size <= 1)
        assertTrue(longer.all { it > 8L * mb })
    }

    @Test
    fun `suzume on the slow server no longer flips the piece`() {
        // 2 downloads, the rule saw 48124 then 117719 kbps by turns, first byte 1370 ms, film 32918 kbps.
        val phases = (0 until 20).map { 2 to if (it % 2 == 0) 48_124L else 117_719L }
        assertEquals(emptyList<Long>(), replay(32_918_000L, 1_370L, phases, 95_000L))
        val longer = replay(32_918_000L, 1_370L, phases, 600_000L)
        assertTrue(longer.size <= 1)
        assertTrue(longer.all { it > 8L * mb })
    }

    @Test
    fun `a remembered rate is never read as more than twice what was delivered`() {
        // A slow server: 40620 kbps on 8 MB pieces with a 1370 ms first byte was read as 237889 kbps.
        assertEquals(2 * mbitKbps(40_620L), AutoSpeedupPolicy.rawRate(mbitKbps(40_620L), 8L * mb, 1_370L))
    }

    @Test
    fun `more downloads than the memory allows bring the piece down at once`() {
        val policy = AutoSpeedupPolicy(6, 12L * mb, smallBox = true)
        policy.pieceBudgetBytes = small
        policy.preset(6)
        policy.onSample(AutoSpeedupPolicy.Sample(nowMs = 0L, aheadMs = 8_000L, loading = false, rebuffers = 0, bytes = 0L))
        assertEquals(8L * mb, policy.pieceBytes)
        assertTrue(AutoSpeedupPolicy.lentBytes(policy.downloads, policy.pieceBytes) <= small)
    }

    @Test
    fun `no bigger piece without the memory for it`() {
        val run = measured(3, large, 2_000L, mbit(15), 30L)
        run.until(10_000L)
        run.policy.onSample(AutoSpeedupPolicy.Sample(nowMs = 121_000L, aheadMs = 8_000L, loading = false, rebuffers = 0,
            bytes = run.bytes + 111L * run.bytesPerSecond, fileBitrate = 30_000_000L, roomBytes = 20L * mb))
        assertEquals(8L * mb, run.policy.pieceBytes)
        assertTrue(run.policy.lastPieceChange!!.startsWith("piece held"))
    }

    @Test
    fun `the piece size is remembered and averaged per server`() {
        val old = HostSpeeds.Entry(3_000_000L, 4, 700L, 0L, 8L * mb)
        val film = HostSpeeds.Entry(3_000_000L, 4, 700L, 1_000L, 16L * mb)
        assertEquals(12L * mb, HostSpeeds.merge(old, film).pieceBytes)
        assertEquals(16L * mb, HostSpeeds.merge(HostSpeeds.Entry(3_000_000L, 4, 700L, 0L), film).pieceBytes)
        assertEquals(8L * mb, HostSpeeds.merge(old, film.copy(pieceBytes = 0L)).pieceBytes)
        assertEquals(film, HostSpeeds.decode(HostSpeeds.encode(film)))
        assertEquals(0L, HostSpeeds.decode("3000000,4,700,0,0,v2")!!.pieceBytes)
    }

    @Test
    fun `the next film from a server starts on its remembered piece`() {
        HostSpeeds.put("syd.download.real-debrid.com",
            HostSpeeds.Entry(delivered(mbit(30), 12L * mb, 700L), 3, 700L, System.currentTimeMillis(), 12L * mb))
        AutoSpeedup.begin("https://syd3-4.download.real-debrid.com/d/film.mkv", 1_900L * mb)
        assertEquals(12L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `an unknown server starts on the usual piece`() {
        AutoSpeedup.begin("https://syd3-4.download.real-debrid.com/d/film.mkv", 1_900L * mb)
        assertEquals(16L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `a remux guessed from a measured server keeps the usual start on small pieces`() {
        HostSpeeds.put("nexus.japn.tb-cdn.pw",
            HostSpeeds.Entry(delivered(mbit(100), 8L * mb, 200L), 2, 200L, System.currentTimeMillis(), 8L * mb))
        val link = "https://nexus-115.japn.tb-cdn.pw/dl/film.mkv"
        AutoSpeedup.startHint(link, 95_000_000L)
        AutoSpeedup.begin(link, 1_900L * mb)
        assertEquals(5, AutoSpeedup.downloads)
        assertEquals(8L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `the size no longer read gives its spare buffers back`() {
        AutoSpeedup.begin("https://syd3-4.download.real-debrid.com/d/film.mkv", 8_000L * mb)
        val retired = mutableListOf<Long>()
        AutoSpeedup.onPieceInUse(24L * mb) { retired += it }
        AutoSpeedup.onPieceInUse(16L * mb) { retired += it }
        assertEquals(listOf(24L * mb), retired)
        AutoSpeedup.onPieceInUse(16L * mb) { retired += it }
        assertEquals(listOf(24L * mb), retired)
    }
}
