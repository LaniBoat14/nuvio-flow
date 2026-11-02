package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSpeedupPolicyTest {

    private var now = 0L
    private var bytes = 0L
    private var ahead = 5_000L
    private var rebuffers = 0
    private val mb = 1024L * 1024L
    private val log = mutableListOf<String>()

    /** Plays [seconds] at [kbps] delivered, half-second samples, buffer moving by [aheadPerSecondMs]. */
    private fun AutoSpeedupPolicy.play(
        seconds: Int,
        kbps: Long,
        loading: Boolean = true,
        aheadPerSecondMs: Long = 0L,
        memoryShort: Boolean = false,
        playing: Boolean = true,
        bufferFull: Boolean = false,
        multiActive: Boolean = true,
        roomMb: Long? = null,
        swapLow: Boolean = false,
        fileMbps: Long = 0L,
        fileBps: Long = fileMbps * 1_000_000L,
        idleMb: Long = 0L,
        userBudget: Boolean = false,
        maxBufferMs: Long = 0L
    ): Int {
        repeat(seconds * 2) {
            now += 500L
            bytes += kbps * 1000L / 8L / 2L
            ahead = (ahead + aheadPerSecondMs / 2L).coerceAtLeast(0L)
            onSample(
                AutoSpeedupPolicy.Sample(
                    now, ahead, loading, rebuffers, bytes, memoryShort, playing, bufferFull, multiActive,
                    roomBytes = roomMb?.let { it * mb } ?: Long.MAX_VALUE,
                    swapLow = swapLow,
                    fileBitrate = fileBps,
                    idleBytes = idleMb * mb,
                    userBudget = userBudget,
                    maxBufferMs = maxBufferMs
                )
            )
            lastChange?.let { log += it }
        }
        return downloads
    }

    @Test
    fun `steady film with a growing buffer never adds a download`() {
        val policy = AutoSpeedupPolicy(3)
        assertEquals(1, policy.play(60, 80_000, aheadPerSecondMs = 200))
    }

    @Test
    fun `starving film tries a second download`() {
        val policy = AutoSpeedupPolicy(3)
        assertEquals(2, policy.play(9, 30_000))
    }

    @Test
    fun `second download is kept when it is 15 percent faster`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(2, policy.play(11, 36_000))
    }

    @Test
    fun `second download is handed back when it does not help`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(1, policy.play(21, 32_000))
        // Blocked for two minutes after a failed try, even while still short.
        assertEquals(1, policy.play(60, 30_000))
    }

    @Test
    fun `a growing buffer during the try counts as a gain`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(2, policy.play(11, 30_000, aheadPerSecondMs = 400))
    }

    @Test
    fun `keeps climbing while each step helps, up to the box limit`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 20_000)
        assertEquals(2, policy.play(21, 30_000))
        assertEquals(3, policy.play(11, 40_000))
        assertEquals(3, policy.play(30, 40_000))
    }

    @Test
    fun `a rebuffer starts a try even with buffer left`() {
        val policy = AutoSpeedupPolicy(3)
        ahead = 20_000L
        policy.play(9, 60_000, aheadPerSecondMs = 100)
        rebuffers = 1
        assertEquals(2, policy.play(1, 60_000))
    }

    @Test
    fun `memory pressure goes straight back to one download`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        assertEquals(1, policy.play(1, 40_000, memoryShort = true))
    }

    @Test
    fun `comfortable buffer hands a download back after a minute`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(21, 40_000)
        ahead = 35_000L
        assertEquals(2, policy.play(50, 40_000, loading = false))
        assertEquals(1, policy.play(15, 40_000, loading = false))
    }

    @Test
    fun `an idle loader with little buffer ahead keeps the download`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 5_000L
        assertEquals(2, policy.play(120, 40_000, loading = false))
        ahead = 12_000L
        assertEquals(1, policy.play(65, 40_000, loading = false))
    }

    @Test
    fun `running short soon after a hand-back keeps the count for the film`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 35_000L
        policy.play(65, 40_000, loading = false)
        ahead = 5_000L
        policy.play(9, 30_000)
        policy.play(11, 40_000)
        ahead = 35_000L
        assertEquals(2, policy.play(120, 40_000, loading = false))
    }

    @Test
    fun `never above the limit`() {
        val policy = AutoSpeedupPolicy(2)
        policy.play(9, 20_000)
        policy.play(11, 40_000)
        assertEquals(2, policy.play(30, 40_000))
    }

    @Test
    fun `a seek that empties the buffer is not taken for a film running short`() {
        val policy = AutoSpeedupPolicy(3)
        ahead = 14_000L
        policy.play(20, 80_000, loading = false)
        ahead = 0L
        assertEquals(1, policy.play(12, 80_000, aheadPerSecondMs = 500))
    }

    @Test
    fun `a try during an outage is not kept`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 0)
        assertEquals(1, policy.play(21, 0))
    }

    @Test
    fun `a buffer growing only because playback stopped is not a gain`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(1, policy.play(21, 30_000, aheadPerSecondMs = 1_000, playing = false))
    }

    @Test
    fun `a full buffer is never taken for a film running short`() {
        val policy = AutoSpeedupPolicy(3)
        assertEquals(1, policy.play(30, 60_000, bufferFull = true))
    }

    @Test
    fun `the try is timed from the switch, not from the decision`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000, multiActive = false)
        // Still waiting for the piece boundary: slower, but not judged yet.
        assertEquals(2, policy.play(8, 20_000, multiActive = false))
        assertEquals(2, policy.play(11, 40_000))
    }

    @Test
    fun `a switch that never happens is given up`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000, multiActive = false)
        assertEquals(1, policy.play(31, 30_000, multiActive = false))
    }

    @Test
    fun `slow links get a longer try`() {
        // 32 MB pieces at 30 Mbit/s: two pieces take about 17 s, so the try runs about 20 s.
        val policy = AutoSpeedupPolicy(3, pieceBytes = 32L * 1024 * 1024)
        policy.play(9, 30_000)
        assertEquals(2, policy.play(15, 30_000))
        assertEquals(1, policy.play(10, 30_000))
    }

    @Test
    fun `no second download without room for its pieces`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        // The first extra download holds three 16 MB pieces.
        assertEquals(1, policy.play(30, 30_000, roomMb = 47))
        assertEquals(2, policy.play(1, 30_000, roomMb = 48))
    }

    @Test
    fun `each further download needs room for one more piece`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 200)
        assertEquals(2, policy.play(11, 100_000, roomMb = 200))
        assertEquals(2, policy.play(11, 100_000, roomMb = 15))
        assertEquals(3, policy.play(1, 100_000, roomMb = 16))
    }

    @Test
    fun `below the low-memory line a download is handed back one at a time`() {
        val policy = AutoSpeedupPolicy(3, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 500)
        policy.play(11, 100_000, roomMb = 500)
        assertEquals(3, policy.play(12, 125_000, roomMb = 500))
        policy.play(3, 125_000, roomMb = 500)
        assertEquals(2, policy.play(1, 125_000, roomMb = -1))
        assertEquals(1, policy.play(3, 125_000, roomMb = -1))
    }

    @Test
    fun `after a low-memory hand-back no try for 15 s even with room again`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 500)
        assertEquals(1, policy.play(2, 80_000, roomMb = -1))
        assertEquals(1, policy.play(12, 80_000, roomMb = 500))
        assertEquals(2, policy.play(5, 80_000, roomMb = 500))
    }

    @Test
    fun `a box that stays short of memory never adds a download`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(1, policy.play(300, 20_000, roomMb = -60))
    }

    @Test
    fun `no new download while swap is nearly used up`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(1, policy.play(60, 30_000, roomMb = 500, swapLow = true))
        assertEquals(listOf("held downloads=1 swap low"), log.filter { "swap" in it })
        assertEquals(2, policy.play(1, 30_000, roomMb = 500))
    }

    @Test
    fun `low swap keeps the downloads already running`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(9, 80_000, roomMb = 500)
        assertEquals(2, policy.play(11, 100_000, roomMb = 500))
        assertEquals(2, policy.play(30, 100_000, roomMb = 500, swapLow = true))
        assertEquals(3, policy.play(1, 100_000, roomMb = 500))
    }

    @Test
    fun `a 4K remux starts on three downloads on boxes with 3 GB or more`() {
        assertEquals(3, AutoSpeedupPolicy(6, 16L * mb).play(9, 30_000, fileMbps = 80))
        assertEquals(listOf("downloads 1->3 start heavy fileKbps=80000"), log)
        assertEquals(3, AutoSpeedupPolicy(5, 16L * mb).play(9, 30_000, fileMbps = 60))
    }

    @Test
    fun `a lighter remux starts on two`() {
        assertEquals(2, AutoSpeedupPolicy(6, 16L * mb).play(9, 30_000, fileMbps = 50))
        assertEquals(2, AutoSpeedupPolicy(5, 16L * mb).play(9, 30_000, fileMbps = 40))
    }

    @Test
    fun `a 2 GB box starts a remux on two`() {
        assertEquals(2, AutoSpeedupPolicy(4, 16L * mb).play(9, 30_000, fileMbps = 80))
        assertEquals(2, AutoSpeedupPolicy(4, 16L * mb).play(9, 30_000, fileMbps = 45))
    }

    @Test
    fun `1080p and streaming 4K start on one`() {
        val policy = AutoSpeedupPolicy(6, 16L * mb)
        assertEquals(1, policy.play(60, 60_000, aheadPerSecondMs = 200, fileMbps = 25))
        assertEquals(1, AutoSpeedupPolicy(6, 16L * mb).play(1, 30_000, fileMbps = 39))
    }

    @Test
    fun `an unknown bitrate starts on one`() {
        assertEquals(1, AutoSpeedupPolicy(6, 16L * mb).play(5, 30_000, aheadPerSecondMs = 500))
    }

    @Test
    fun `the start never goes above three`() {
        for (max in 5..6) assertEquals(3, AutoSpeedupPolicy.startDownloads(200_000_000L, max))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(200_000_000L, 4))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(200_000_000L, 4, 16L * mb))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(200_000_000L, 2))
        assertEquals(1, AutoSpeedupPolicy.startDownloads(200_000_000L, 1))
    }

    @Test
    fun `the start fits the free memory`() {
        // Three downloads hold four 16 MB pieces, two hold three.
        assertEquals(2, AutoSpeedupPolicy(6, 16L * mb).play(9, 30_000, roomMb = 50, fileMbps = 80))
        assertEquals(1, AutoSpeedupPolicy(6, 16L * mb).play(9, 30_000, roomMb = 40, fileMbps = 80, aheadPerSecondMs = 500))
    }

    @Test
    fun `low swap holds the start until it recovers`() {
        val policy = AutoSpeedupPolicy(6, 16L * mb)
        assertEquals(1, policy.play(9, 30_000, aheadPerSecondMs = 500, swapLow = true, fileMbps = 80))
        assertEquals(3, policy.play(1, 30_000, aheadPerSecondMs = 500, fileMbps = 80))
    }

    @Test
    fun `start downloads that help are kept`() {
        val policy = AutoSpeedupPolicy(6, 16L * mb)
        policy.play(8, 40_000, aheadPerSecondMs = 500)
        assertEquals(3, policy.play(1, 40_000, fileMbps = 80))
        assertEquals(3, policy.play(11, 60_000, fileMbps = 80))
    }

    @Test
    fun `start downloads that do not help are handed back and the start is not repeated`() {
        val policy = AutoSpeedupPolicy(6, 16L * mb)
        policy.play(8, 40_000, aheadPerSecondMs = 500)
        assertEquals(2, policy.play(1, 40_000, fileMbps = 50))
        assertEquals(1, policy.play(11, 42_000, fileMbps = 50))
        assertEquals(true, log.last().startsWith("downloads 2->1 no gain"))
        assertEquals(1, policy.play(60, 42_000, aheadPerSecondMs = 2_000, fileMbps = 50))
    }

    @Test
    fun `after a start that did not help a film running short climbs the usual way`() {
        val policy = AutoSpeedupPolicy(6, 16L * mb)
        policy.play(8, 40_000, aheadPerSecondMs = 500)
        policy.play(1, 40_000, fileMbps = 50)
        assertEquals(1, policy.play(11, 42_000, fileMbps = 50))
        assertEquals(2, policy.play(3, 42_000, fileMbps = 50))
        assertEquals(true, log.last().startsWith("downloads 1->2 trial"))
    }

    @Test
    fun `a remux start stays even below the film's bitrate, one download being slower still`() {
        val fast = AutoSpeedupPolicy(6, 16L * mb)
        assertEquals(3, fast.play(14, 90_000, fileMbps = 80, aheadPerSecondMs = 1_000))
        assertEquals(true, log.any { it.startsWith("kept downloads=3") })
        val slow = AutoSpeedupPolicy(6, 16L * mb)
        assertEquals(3, slow.play(14, 50_000, fileMbps = 80, aheadPerSecondMs = 1_000))
    }

    @Test
    fun `an early try with little buffer left is judged sooner`() {
        val early = AutoSpeedupPolicy(3)
        ahead = 2_000L
        early.play(9, 30_000)
        assertEquals(1, early.play(8, 30_000))
        // The same with a normal buffer is still running its 10 s try.
        now = 0L
        bytes = 0L
        ahead = 6_000L
        val normal = AutoSpeedupPolicy(3)
        normal.play(9, 30_000)
        assertEquals(2, normal.play(8, 30_000))
    }

    @Test
    fun `the swap hold is logged again after swap recovers`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.play(20, 30_000, roomMb = -60, swapLow = true)
        assertEquals(emptyList<String>(), log.filter { "swap" in it })
        policy.play(5, 30_000, roomMb = 500, swapLow = true)
        policy.play(1, 30_000, roomMb = -60)
        policy.play(5, 30_000, roomMb = 500, swapLow = true)
        assertEquals(listOf("held downloads=1 swap low", "held downloads=1 swap low"), log.filter { "swap" in it })
    }

    /** Starts a 70 Mbit/s film on two downloads, fills the buffer and hands one back as comfortable. */
    private fun AutoSpeedupPolicy.startFillAndDrop() {
        assertEquals(2, play(9, 250_000, roomMb = 500, fileMbps = 70))
        assertEquals(2, play(11, 250_000, roomMb = 500, fileMbps = 70, aheadPerSecondMs = 2_000))
        ahead = 40_000L
        assertEquals(1, play(65, 60_000, loading = false, roomMb = 500, fileMbps = 70))
        assertEquals(true, log.last().startsWith("downloads 2->1 comfortable"))
        ahead = 5_000L
    }

    @Test
    fun `a film running short after the buffer filled adds a download using the buffer's idle memory`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.startFillAndDrop()
        assertEquals(2, policy.play(9, 40_000, roomMb = 30, idleMb = 100, fileMbps = 70))
        assertEquals(true, log.last().startsWith("downloads 1->2 trial"))
    }

    @Test
    fun `no download is added when free and idle memory together are still short`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        policy.startFillAndDrop()
        log.clear()
        assertEquals(1, policy.play(60, 40_000, roomMb = 30, idleMb = 10, fileMbps = 70))
        assertEquals(listOf("held downloads=1 roomMb=30 idleMb=10 needMb=48 blockedS=0 userBudget=false"), log)
    }

    @Test
    fun `the start does not count the buffer's idle memory`() {
        assertEquals(1, AutoSpeedupPolicy(6, 16L * mb).play(9, 30_000, roomMb = 40, idleMb = 500, fileMbps = 80, aheadPerSecondMs = 500))
    }

    @Test
    fun `a held download is logged once per change of reason`() {
        val policy = AutoSpeedupPolicy(3)
        policy.play(9, 30_000)
        assertEquals(1, policy.play(21, 32_000))
        log.clear()
        assertEquals(1, policy.play(20, 30_000))
        assertEquals(1, log.size)
        assertEquals(true, log.single().startsWith("held downloads=1 roomMb="))
        assertEquals(true, log.single().contains("blockedS="))
        log.clear()
        assertEquals(1, policy.play(5, 30_000))
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `with the user's own buffer target a download is added and kept without checking free memory`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(2, policy.play(9, 30_000, roomMb = -60, userBudget = true))
        assertEquals(true, log.last().startsWith("downloads 1->2 trial"))
        assertEquals(true, log.last().endsWith("userBudget=true"))
        assertEquals(2, policy.play(21, 36_000, roomMb = -60, userBudget = true))
        assertEquals(2, policy.play(30, 36_000, roomMb = -60, userBudget = true))
    }

    @Test
    fun `without the user's own buffer target the same box hands the download back`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(2, policy.play(9, 30_000, roomMb = 500))
        assertEquals(1, policy.play(3, 36_000, roomMb = -60))
        assertEquals(true, log.last().startsWith("downloads 2->1 memory low"))
    }

    @Test
    fun `with the user's own buffer target memory short still drops to one`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(2, policy.play(9, 30_000, roomMb = -60, userBudget = true))
        assertEquals(1, policy.play(3, 36_000, roomMb = -60, memoryShort = true, userBudget = true))
        assertEquals(1, policy.play(40, 30_000, roomMb = -60, userBudget = true))
    }

    @Test
    fun `memory short hands downloads back one at a time`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(4)
        policy.play(12, 150_000, roomMb = 500, fileMbps = 96, aheadPerSecondMs = 1_000)
        assertEquals(3, policy.play(1, 150_000, roomMb = 500, memoryShort = true, fileMbps = 96))
        assertEquals(2, policy.play(2, 150_000, roomMb = 500, memoryShort = true, fileMbps = 96))
    }

    @Test
    fun `with the user's own buffer target low swap still holds a download back`() {
        val policy = AutoSpeedupPolicy(4, pieceBytes = 16L * mb)
        assertEquals(1, policy.play(60, 30_000, roomMb = -60, swapLow = true, userBudget = true))
        assertEquals(listOf("held downloads=1 swap low"), log)
    }

    @Test
    fun `with the user's own buffer target the start does not check free memory`() {
        assertEquals(3, AutoSpeedupPolicy(6, 16L * mb).play(9, 30_000, roomMb = 40, fileMbps = 80, userBudget = true))
        assertEquals(listOf("downloads 1->3 start heavy fileKbps=80000 userBudget=true"), log)
    }

    @Test
    fun `a remux starts on several at once, a lighter film measures one download first`() {
        assertEquals(2, AutoSpeedupPolicy(4, 16L * mb).play(1, 0, roomMb = 500, fileMbps = 90))
        assertEquals(listOf("downloads 1->2 start heavy fileKbps=90000"), log)
        // 50 Mbit/s: one download carrying 1.3 x the film's rate is enough.
        log.clear()
        val fast = AutoSpeedupPolicy(4, 16L * mb)
        assertEquals(1, fast.play(1, 0, roomMb = 500, fileMbps = 50))
        assertEquals(1, fast.play(8, 150_000, roomMb = 500, fileMbps = 50, aheadPerSecondMs = 500))
        assertEquals(listOf("start not needed fileKbps=50000 singleKbps=150000 loading=100%"), log)
        log.clear()
        val slow = AutoSpeedupPolicy(4, 16L * mb)
        assertEquals(1, slow.play(1, 0, roomMb = 500, fileMbps = 50))
        assertEquals(2, slow.play(8, 55_000, roomMb = 500, fileMbps = 50))
        assertEquals(listOf("downloads 1->2 start fileKbps=50000 singleKbps=55000"), log)
        log.clear()
        val resting = AutoSpeedupPolicy(4, 16L * mb)
        assertEquals(1, resting.play(9, 40_000, loading = false, roomMb = 500, fileMbps = 50))
        assertEquals(true, log.single().startsWith("start not needed"))
    }

    @Test
    fun `a comfortable buffer never hands back to one download that is too slow for the film`() {
        val policy = AutoSpeedupPolicy(4, 16L * mb)
        assertEquals(2, policy.play(20, 150_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 1_000))
        ahead = 11_500L
        assertEquals(2, policy.play(120, 150_000, loading = false, bufferFull = true, roomMb = 500, fileMbps = 90,
            maxBufferMs = 12_000))
        assertEquals(emptyList<String>(), log.filter { "->1" in it })
    }

    @Test
    fun `a fast link hands the downloads back at a 12 s max buffer held short by memory`() {
        // 12 s Max buffer, but the pieces the downloads hold cap the buffer at about 9.5 s.
        val policy = AutoSpeedupPolicy(4, 16L * mb)
        assertEquals(2, policy.play(9, 300_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 1_000))
        assertEquals(2, policy.play(11, 300_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 1_000))
        ahead = 9_500L
        assertEquals(2, policy.play(50, 150_000, loading = false, bufferFull = true, roomMb = 500, fileMbps = 90,
            maxBufferMs = 12_000))
        assertEquals(1, policy.play(15, 150_000, loading = false, bufferFull = true, roomMb = 500, fileMbps = 90,
            maxBufferMs = 12_000))
        assertEquals(true, log.last().startsWith("downloads 2->1 comfortable"))
    }

    @Test
    fun `a buffer at the user's short Max buffer with a resting loader hands the downloads back`() {
        val policy = AutoSpeedupPolicy(4, 16L * mb)
        policy.play(9, 300_000, roomMb = 500, fileMbps = 90, maxBufferMs = 8_000, aheadPerSecondMs = 1_000)
        policy.play(11, 300_000, roomMb = 500, fileMbps = 90, maxBufferMs = 8_000, aheadPerSecondMs = 1_000)
        ahead = 7_500L
        assertEquals(1, policy.play(65, 150_000, loading = false, roomMb = 500, fileMbps = 90, maxBufferMs = 8_000))
    }

    @Test
    fun `a slow link still adds a download and keeps it`() {
        val policy = AutoSpeedupPolicy(4, 16L * mb)
        assertEquals(2, policy.play(9, 60_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 600))
        assertEquals(2, policy.play(11, 80_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 600))
        // Short of buffer with the loader busy all the time: never handed back.
        ahead = 4_000L
        assertEquals(true, policy.play(120, 80_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000) >= 2)
        assertEquals(emptyList<String>(), log.filter { "->1" in it })
    }

    @Test
    fun `running short after a hand-back at a full buffer adds a download back for the film`() {
        val policy = AutoSpeedupPolicy(4, 16L * mb)
        policy.play(9, 300_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 1_000)
        policy.play(11, 300_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000, aheadPerSecondMs = 1_000)
        ahead = 9_500L
        assertEquals(1, policy.play(65, 150_000, loading = false, bufferFull = true, roomMb = 500, fileMbps = 90,
            maxBufferMs = 12_000))
        ahead = 4_000L
        assertEquals(2, policy.play(9, 60_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000))
        assertEquals(true, log.last().startsWith("downloads 1->2 trial"))
        assertEquals(2, policy.play(11, 80_000, roomMb = 500, fileMbps = 90, maxBufferMs = 12_000))
        ahead = 9_500L
        assertEquals(2, policy.play(120, 150_000, loading = false, bufferFull = true, roomMb = 500, fileMbps = 90,
            maxBufferMs = 12_000))
    }

    @Test
    fun `a memory warning with room left does not stop the extra downloads`() {
        val mb = 1024L * 1024L
        assertEquals(false, AutoSpeedupPolicy.memoryShort(warned = true, warnedCritical = true, roomBytes = 150 * mb, lowMemory = false))
        assertEquals(false, AutoSpeedupPolicy.memoryShort(warned = true, warnedCritical = false, roomBytes = -10 * mb, lowMemory = false))
        assertEquals(false, AutoSpeedupPolicy.memoryShort(warned = false, warnedCritical = false, roomBytes = -10 * mb, lowMemory = false))
    }

    @Test
    fun `memory is short past the box's own line, or close to it when Android says critical`() {
        val mb = 1024L * 1024L
        assertEquals(true, AutoSpeedupPolicy.memoryShort(warned = false, warnedCritical = false, roomBytes = -30 * mb, lowMemory = false))
        assertEquals(true, AutoSpeedupPolicy.memoryShort(warned = true, warnedCritical = true, roomBytes = -10 * mb, lowMemory = false))
        assertEquals(true, AutoSpeedupPolicy.memoryShort(warned = null, warnedCritical = false, roomBytes = 500 * mb, lowMemory = true))
        assertEquals(false, AutoSpeedupPolicy.memoryShort(warned = null, warnedCritical = false, roomBytes = -120 * mb, lowMemory = false))
    }

    @Test
    fun `read-ahead grows two pieces at a time with free memory and shrinks first when it runs low`() {
        val mb = 1024L * 1024L
        val piece = 8L * mb
        assertEquals(0, AutoSpeedupPolicy.readAheadPieces(0, 500 * mb, piece, 1))
        assertEquals(2, AutoSpeedupPolicy.readAheadPieces(0, 81 * mb, piece, 3))
        assertEquals(1, AutoSpeedupPolicy.readAheadPieces(0, 34 * mb, piece, 3))
        assertEquals(8, AutoSpeedupPolicy.readAheadPieces(7, 500 * mb, piece, 6))
        assertEquals(9, AutoSpeedupPolicy.readAheadPieces(8, 500 * mb, piece, 3))
        assertEquals(4, AutoSpeedupPolicy.readAheadPieces(6, 10 * mb, piece, 3))
        assertEquals(0, AutoSpeedupPolicy.readAheadPieces(3, -40 * mb, piece, 3))
        assertEquals(5, AutoSpeedupPolicy.readAheadPieces(4, 500 * mb, 16L * mb, 2))
    }

    @Test
    fun `a 2 GB box starts a remux on five small downloads`() {
        val mb = 1024L * 1024L
        assertEquals(5, AutoSpeedupPolicy.startDownloads(90_000_000L, 6, 8L * mb, smallBox = true))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(50_000_000L, 6, 8L * mb, smallBox = true))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(90_000_000L, 6, 16L * mb, smallBox = true))
        assertEquals(3, AutoSpeedupPolicy.startDownloads(90_000_000L, 6, 16L * mb, smallBox = false))
    }

    @Test
    fun `a 2 GB box starts a remux on as many 8 MB downloads as it may, holding less than two 16 MB ones`() {
        assertEquals(4, AutoSpeedupPolicy.startDownloads(90_000_000L, 4, 8L * mb))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(50_000_000L, 4, 8L * mb))
        assertEquals(true, AutoSpeedupPolicy.lentBytes(3, 8L * mb) < AutoSpeedupPolicy.lentBytes(2, 16L * mb))
    }

    @Test
    fun `a guessed remux runs its start downloads from the first sample and keeps them`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(3)
        assertEquals(3, policy.downloads)
        assertEquals(3, policy.play(14, 100_000, roomMb = 500, fileMbps = 96, aheadPerSecondMs = 1_000))
        assertEquals(true, log.any { it.startsWith("kept downloads=3") })
    }

    @Test
    fun `free memory just under the margin keeps the downloads a remux needs`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(3)
        policy.play(12, 150_000, roomMb = 500, fileMbps = 96, aheadPerSecondMs = 1_000)
        assertEquals(2, policy.play(10, 150_000, roomMb = -3, fileMbps = 96))
        assertEquals(true, log.none { "->1" in it })
    }

    @Test
    fun `an unmeasured remux keeps two downloads when the buffer is comfortable`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(3)
        // Memory short cancels the first measurement.
        assertEquals(2, policy.play(1, 150_000, roomMb = 500, memoryShort = true, fileMbps = 96))
        ahead = 11_500L
        assertEquals(2, policy.play(120, 150_000, loading = false, bufferFull = true, roomMb = 500, fileMbps = 96,
            maxBufferMs = 12_000))
        assertEquals(true, log.none { "->1" in it })
    }

    @Test
    fun `read-ahead leaves generating thumbnails their memory floor and returns to the normal rule after`() {
        val piece = 8L * mb
        val floor = 235L * mb
        // Fire Stick: 200 MB available, 115 MB above Android's line and the margin.
        val room = AutoSpeedupPolicy.readAheadRoom(115L * mb, 200L * mb, floor)
        assertEquals(-35L * mb, room)
        assertEquals(0, AutoSpeedupPolicy.readAheadPieces(6, room, piece, 3))
        assertEquals(8, AutoSpeedupPolicy.readAheadPieces(6, 115L * mb, piece, 3))
        // Well above the floor the normal rule is the tighter one.
        assertEquals(115L * mb, AutoSpeedupPolicy.readAheadRoom(115L * mb, 500L * mb, floor))
        // No session generating, or MemAvailable unknown.
        assertEquals(115L * mb, AutoSpeedupPolicy.readAheadRoom(115L * mb, 200L * mb, 0L))
        assertEquals(115L * mb, AutoSpeedupPolicy.readAheadRoom(115L * mb, -1L, floor))
        // Read-ahead stops 25 MB above the floor: 268 MB available leaves room for one more piece.
        val near = AutoSpeedupPolicy.readAheadRoom(Long.MAX_VALUE, 268L * mb, floor)
        assertEquals(1, AutoSpeedupPolicy.readAheadPieces(0, near, piece, 3))
    }
    @Test
    fun `a known rate per download starts the film on what carries it with a margin`() {
        val mb = 1024L * 1024L
        // 150 % of the film's rate on a 3 GB box or larger.
        assertEquals(5, AutoSpeedupPolicy.startDownloads(90_000_000L, 10, 16L * mb, false, 30_000_000L / 8))
        assertEquals(7, AutoSpeedupPolicy.startDownloads(90_000_000L, 10, 16L * mb, false, 20_000_000L / 8))
        assertEquals(10, AutoSpeedupPolicy.startDownloads(90_000_000L, 10, 16L * mb, false, 5_000_000L / 8))
        assertEquals(1, AutoSpeedupPolicy.startDownloads(15_000_000L, 10, 8L * mb, false, 100_000_000L / 8))
    }

    // Edge of Tomorrow on a 3 GB TCL TV: guessed 80 Mbit/s as a remux, really 94.5; TorBox carried 28.1 per download.
    private val realRemuxBps = 80_455_627_630L * 8 / 6_808
    private val perDownload = 28_107_000L / 8

    @Test
    fun `the remux from the issue 8 log starts on five downloads and is recounted to six`() {
        assertEquals(94_542_000L, realRemuxBps / 1000 * 1000)
        val start = AutoSpeedupPolicy.startDownloads(80_000_000L, 8, 8L * mb, false, perDownload)
        assertEquals(5, start)
        val policy = AutoSpeedupPolicy(8, 8L * mb, smallBox = false)
        policy.knownPerDownloadBps = perDownload
        policy.preset(start, 80_000_000L)
        assertEquals(6, policy.play(1, 107_000, roomMb = 470, fileBps = realRemuxBps))
        assertEquals("downloads 5->6 start recounted fileKbps=94542 guessedKbps=80000", log.first())
        assertEquals(6, policy.play(14, 160_000, roomMb = 470, fileBps = realRemuxBps, aheadPerSecondMs = 1_000))
        assertEquals(true, log.any { it.startsWith("kept downloads=6") })
    }

    @Test
    fun `a 2 GB box already starts the same remux on five and stays there`() {
        val start = AutoSpeedupPolicy.startDownloads(80_000_000L, 6, 8L * mb, true, perDownload)
        assertEquals(5, start)
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.knownPerDownloadBps = perDownload
        policy.preset(start, 80_000_000L)
        assertEquals(5, policy.play(1, 107_000, roomMb = 470, fileBps = realRemuxBps))
        assertEquals(true, log.none { "recounted" in it })
    }

    @Test
    fun `the recount waits for the real bitrate and runs once`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.knownPerDownloadBps = perDownload
        policy.preset(4, 80_000_000L)
        assertEquals(4, policy.play(3, 107_000, roomMb = 470))
        assertEquals(5, policy.play(1, 107_000, roomMb = 470, fileBps = realRemuxBps))
        assertEquals(5, policy.play(4, 107_000, roomMb = 470, fileBps = 2 * realRemuxBps))
        assertEquals(1, log.count { "recounted" in it })
    }

    @Test
    fun `a lighter film than guessed keeps its start`() {
        val policy = AutoSpeedupPolicy(8, 8L * mb, smallBox = false)
        policy.knownPerDownloadBps = perDownload
        policy.preset(5, 96_000_000L)
        assertEquals(5, policy.play(1, 107_000, roomMb = 470, fileBps = realRemuxBps))
        assertEquals(true, log.none { "recounted" in it })
    }

    @Test
    fun `the recount stays within free memory`() {
        val policy = AutoSpeedupPolicy(8, 8L * mb, smallBox = false)
        policy.knownPerDownloadBps = perDownload
        policy.preset(4, 70_000_000L)
        // Six downloads hold 56 MB, five 48 MB.
        assertEquals(5, policy.play(1, 107_000, roomMb = 50, fileBps = realRemuxBps))
    }

    @Test
    fun `a start that another rule already changed is not recounted`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.knownPerDownloadBps = perDownload
        policy.preset(4, 80_000_000L)
        assertEquals(3, policy.play(1, 107_000, roomMb = 470, memoryShort = true))
        assertEquals(3, policy.play(1, 107_000, roomMb = 470, fileBps = realRemuxBps))
        assertEquals(true, log.none { "recounted" in it })
    }

    @Test
    fun `a start without a guessed bitrate is not recounted`() {
        val policy = AutoSpeedupPolicy(6, 8L * mb, smallBox = true)
        policy.preset(3)
        assertEquals(3, policy.play(1, 107_000, roomMb = 470, fileMbps = 96))
    }

    @Test
    fun `a quick server before never starts a heavy film on fewer downloads than usual`() {
        val mb = 1024L * 1024L
        assertEquals(3, AutoSpeedupPolicy.startDownloads(90_000_000L, 10, 16L * mb, false, 200_000_000L / 8))
        assertEquals(5, AutoSpeedupPolicy.startDownloads(96_000_000L, 6, 8L * mb, true, 50_000_000L / 8))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(90_000_000L, 6, 16L * mb, true, 200_000_000L / 8))
        // A lighter film is judged on one download first, as without a known rate.
        assertEquals(1, AutoSpeedupPolicy.startDownloads(45_000_000L, 10, 8L * mb, false, 100_000_000L / 8))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(45_000_000L, 10, 8L * mb, false))
    }

    @Test
    fun `a 2 GB box with a known rate still keeps the start within its memory`() {
        val mb = 1024L * 1024L
        assertEquals(5, AutoSpeedupPolicy.startDownloads(90_000_000L, 6, 8L * mb, true, 15_000_000L / 8))
        assertEquals(2, AutoSpeedupPolicy.startDownloads(90_000_000L, 6, 16L * mb, true, 15_000_000L / 8))
    }

    @Test
    fun `the read-ahead follows the box's own cap`() {
        val mb = 1024L * 1024L
        assertEquals(12, AutoSpeedupPolicy.readAheadPieces(10, 500 * mb, 8L * mb, 6, 256L * mb))
        assertEquals(8, AutoSpeedupPolicy.readAheadPieces(8, 500 * mb, 8L * mb, 6))
    }
}
