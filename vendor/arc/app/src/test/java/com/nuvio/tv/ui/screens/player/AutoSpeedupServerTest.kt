package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.thumbnail.ConnectionLedger
import com.nuvio.tv.core.player.thumbnail.PlaybackLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSpeedupServerTest {

    private val mb = 1024L * 1024L

    @After
    fun tearDown() {
        AutoSpeedup.end()
        HostSpeeds.clearForTest()
    }

    @Test
    fun `a slow real server behind an add-on link raises the start`() {
        HostSpeeds.put("nexus.japn.tb-cdn.pw", HostSpeeds.Entry(15_000_000L / 8, 6, 300L, System.currentTimeMillis()))
        val link = "https://addon.example.org/playback/abc"
        AutoSpeedup.startHint(link, 96_000_000L)
        AutoSpeedup.begin(link, 8_000L * mb)
        assertEquals(3, AutoSpeedup.downloads)
        AutoSpeedup.onResolved("https://nexus-115.japn.tb-cdn.pw/dl/file.mkv")
        assertEquals(10, AutoSpeedup.downloads)
    }

    @Test
    fun `a quick real server behind an add-on link does not lower the start`() {
        HostSpeeds.put("nexus.japn.tb-cdn.pw", HostSpeeds.Entry(50_000_000L / 8, 3, 300L, System.currentTimeMillis()))
        val link = "https://addon.example.org/playback/abc"
        AutoSpeedup.startHint(link, 96_000_000L)
        AutoSpeedup.begin(link, 1_900L * mb)
        assertEquals(5, AutoSpeedup.downloads)
        AutoSpeedup.onResolved("https://nexus-115.japn.tb-cdn.pw/dl/file.mkv")
        assertEquals(5, AutoSpeedup.downloads)
        assertEquals(8L * mb, AutoSpeedup.pieceBytes)
    }

    @Test
    fun `an unknown real server keeps the start`() {
        val link = "https://addon.example.org/playback/abc"
        AutoSpeedup.startHint(link, 96_000_000L)
        AutoSpeedup.begin(link, 1_900L * mb)
        AutoSpeedup.onResolved("https://nexus-115.zafr.tb-cdn.to/dl/file.mkv")
        assertEquals(5, AutoSpeedup.downloads)
    }

    @Test
    fun `thumbnails see the real server behind an add-on link, the one playback's requests are counted under`() {
        val link = "https://addon.example.org/key/play/abc"
        AutoSpeedup.begin(link, 4_000L * mb)
        AutoSpeedup.onResolved("https://cee11.cdn.example.com/dl/film.mkv")
        val nextEpisode = ConnectionLedger.app.playback("https://cee11.cdn.example.com/dl/next.mkv")
        try {
            AutoSpeedup.onPlayerSample(AutoSpeedupPolicy.Sample(1_000L, 20_000L, true, 0, 0L, fileBitrate = 25_000_000L))
            val line = PlaybackLine.now(1_000L)!!
            assertEquals("cee11.cdn.example.com", line.filmKey)
            assertEquals("addon.example.org", line.linkKey)
            assertEquals("cee.cdn.example.com", line.filmServer)
            assertEquals(1, line.otherRequests)
        } finally {
            nextEpisode?.close()
        }
    }

    @Test
    fun `a start that was already judged is not set again`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(5)
        assertTrue(policy.restartStart(3))
        assertEquals(3, policy.downloads)
        assertFalse(policy.restartStart(3))
    }
}
