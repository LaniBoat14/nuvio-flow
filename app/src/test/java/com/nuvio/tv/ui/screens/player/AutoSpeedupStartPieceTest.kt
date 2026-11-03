package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoSpeedupStartPieceTest {

    private val mb = 1024L * 1024L
    private val link = "https://syd3-4.download.real-debrid.com/d/film.mkv"
    private val server = "syd.download.real-debrid.com"

    @After
    fun tearDown() {
        AutoSpeedup.end()
        HostSpeeds.clearForTest()
    }

    private fun mbit(n: Long) = n * 1_000_000L / 8

    private fun delivered(rawBps: Long, piece: Long, firstByteMs: Long): Long =
        (piece * 1000.0 / (piece * 1000.0 / rawBps + firstByteMs)).toLong()

    private fun entry(startPiece: Long, savedAtMs: Long = System.currentTimeMillis()) =
        HostSpeeds.Entry(delivered(mbit(60), 8L * mb, 300L), 3, 300L, savedAtMs, 8L * mb, startPiece)

    @Test
    fun `the start piece moves one step toward the size each film needed`() {
        assertEquals(12L * mb, HostSpeeds.stepPiece(8L * mb, 12L * mb))
        assertEquals(12L * mb, HostSpeeds.stepPiece(8L * mb, 24L * mb))
        assertEquals(16L * mb, HostSpeeds.stepPiece(24L * mb, 8L * mb))
        assertEquals(16L * mb, HostSpeeds.stepPiece(16L * mb, 16L * mb))
        assertEquals(12L * mb, HostSpeeds.stepPiece(0L, 12L * mb))
        assertEquals(12L * mb, HostSpeeds.stepPiece(12L * mb, 0L))
    }

    @Test
    fun `two films that needed 12 MB lift a server remembered on 8 MB`() {
        HostSpeeds.put(server, entry(8L * mb, savedAtMs = 1_000L))
        HostSpeeds.record(server, entry(12L * mb, savedAtMs = 2_000L))
        assertEquals(12L * mb, HostSpeeds.get(server, 2_000L)!!.startPieceBytes)
        HostSpeeds.record(server, entry(24L * mb, savedAtMs = 3_000L))
        assertEquals(16L * mb, HostSpeeds.get(server, 3_000L)!!.startPieceBytes)
        HostSpeeds.record(server, entry(8L * mb, savedAtMs = 4_000L))
        assertEquals(12L * mb, HostSpeeds.get(server, 4_000L)!!.startPieceBytes)
    }

    @Test
    fun `version 2 entries start on the piece their rate was carried on`() {
        assertEquals(12L * mb, HostSpeeds.decode("3000000,4,700,0,${12L * mb},v2")!!.startPieceBytes)
        val e = HostSpeeds.Entry(3_000_000L, 4, 700L, 5L, 8L * mb, 16L * mb)
        assertEquals(e, HostSpeeds.decode(HostSpeeds.encode(e)))
        assertNull(HostSpeeds.decode("3000000,4,700,0,${8L * mb},v3"))
        assertNull(HostSpeeds.decode("3000000,4,700,0,${8L * mb},${8L * mb},v2"))
    }

    @Test
    fun `a guessed film starts on the piece the server's films needed before`() {
        HostSpeeds.put(server, entry(16L * mb))
        AutoSpeedup.startHint(link, 64_000_000L)
        AutoSpeedup.begin(link, 8_000L * mb)
        assertEquals(3, AutoSpeedup.downloads)
        assertEquals(16L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `without a bigger remembered size the guessed start keeps the plan's piece`() {
        HostSpeeds.put(server, entry(8L * mb))
        AutoSpeedup.startHint(link, 64_000_000L)
        AutoSpeedup.begin(link, 8_000L * mb)
        assertEquals(3, AutoSpeedup.downloads)
        assertEquals(8L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `the remembered size never breaks the 2 GB memory plan`() {
        HostSpeeds.put("nexus.japn.tb-cdn.pw", HostSpeeds.Entry(delivered(mbit(100), 8L * mb, 200L), 2, 200L,
            System.currentTimeMillis(), 8L * mb, 16L * mb))
        val torbox = "https://nexus-115.japn.tb-cdn.pw/dl/film.mkv"
        AutoSpeedup.startHint(torbox, 95_000_000L)
        AutoSpeedup.begin(torbox, 1_900L * mb)
        assertEquals(5, AutoSpeedup.downloads)
        assertEquals(8L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `the real server behind an add-on brings its remembered piece`() {
        HostSpeeds.put(server, entry(16L * mb))
        val addon = "https://addon.example.org/playback/abc"
        AutoSpeedup.startHint(addon, 64_000_000L)
        AutoSpeedup.begin(addon, 8_000L * mb)
        assertEquals(8L * mb, AutoSpeedup.pieceBytes)
        AutoSpeedup.onResolved(link)
        assertEquals(16L * mb, AutoSpeedup.pieceBytes)
        assertEquals(3, AutoSpeedup.downloads)
    }

    private fun timedPolicy(ttfbMs: Long): AutoSpeedupPolicy {
        val p = AutoSpeedupPolicy(10, 16L * mb, false)
        p.pieceBudgetBytes = 256L * mb
        p.onPiece(PieceTiming(1_000L, ttfbMs, 2_000L, 8L * mb, reused = true, redirected = false))
        p.onPiece(PieceTiming(3_000L, ttfbMs, 2_000L, 8L * mb, reused = true, redirected = false))
        return p
    }

    @Test
    fun `a film remembers the smaller piece it needed, not a bigger one it ran on`() {
        assertEquals(8L * mb, timedPolicy(300L).pieceToRemember(16L * mb))
        assertEquals(12L * mb, timedPolicy(1_000L).pieceToRemember(12L * mb))
    }
}
