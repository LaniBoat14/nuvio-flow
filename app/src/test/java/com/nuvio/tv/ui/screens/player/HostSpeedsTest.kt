package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostSpeedsTest {

    @After
    fun tearDown() = HostSpeeds.clearForTest()

    @Test
    fun `the machine number is left out and the place stays`() {
        assertEquals("nexus.japn.tb-cdn.pw", HostSpeeds.keyOf("https://nexus-115.japn.tb-cdn.pw/dl/a.mkv"))
        assertEquals("store.weur.tb-cdn.st", HostSpeeds.keyOf("https://store-059.weur.tb-cdn.st/x"))
        assertEquals("syd.download.real-debrid.com", HostSpeeds.keyOf("https://syd3-4.download.real-debrid.com/d/x"))
        assertEquals("syd.download.real-debrid.com", HostSpeeds.keyOf("https://syd4-1.download.real-debrid.com/d/x"))
        assertEquals("cdn-ovh-bea.energycdn.com", HostSpeeds.keyOf("https://1-cdn2-ovh-bea.energycdn.com/x"))
        assertEquals("cdn-ovh-bea.energycdn.com", HostSpeeds.keyOf("https://7-cdn5-ovh-bea.energycdn.com/x"))
        assertEquals("cdn.example.com", HostSpeeds.keyOf("https://cdn2.example.com/a"))
    }

    @Test
    fun `plain hosts and addresses stay as they are`() {
        assertEquals("example.com", HostSpeeds.keyOf("https://example.com/a"))
        assertEquals("cdn.example.com", HostSpeeds.keyOf("https://cdn.example.com/a"))
        assertEquals("api.example.com", HostSpeeds.keyOf("https://api.example.com/a"))
        assertEquals("node1.com", HostSpeeds.keyOf("https://node1.com/a"))
        assertEquals("localhost", HostSpeeds.keyOf("http://localhost:8080/a"))
        assertEquals("192.168.1.10", HostSpeeds.keyOf("http://192.168.1.10:3100/a"))
        assertEquals("[::1]", HostSpeeds.keyOf("http://[::1]:8080/a"))
        assertEquals("123.example.com", HostSpeeds.keyOf("https://123.example.com/a"))
        assertNull(HostSpeeds.keyOf("not a link"))
    }

    @Test
    fun `an entry survives being written and read back`() {
        val e = HostSpeeds.Entry(3_750_000L, 6, 730L, 1_000L)
        assertEquals(e, HostSpeeds.decode(HostSpeeds.encode(e)))
        assertNull(HostSpeeds.decode("1,2,3"))
        assertNull(HostSpeeds.decode("0,2,3,4"))
    }

    @Test
    fun `entries saved before the rate was shared by the requests really running are not used`() {
        // The old rate divided by downloads while the read-ahead also ran: up to four times too high.
        assertNull(HostSpeeds.decode("12000000,2,730,1000"))
        assertNull(HostSpeeds.decode("12000000,2,730,1000,8388608"))
        assertNull(HostSpeeds.decode("12000000,2,730,1000,8388608,v1"))
        assertEquals(HostSpeeds.Entry(12_000_000L, 2, 730L, 1_000L, 8_388_608L, 8_388_608L),
            HostSpeeds.decode("12000000,2,730,1000,8388608,v2"))
    }

    @Test
    fun `an entry older than the keep time is ignored`() {
        HostSpeeds.put("japn.tb-cdn.pw", HostSpeeds.Entry(10_000_000L, 4, 300L, 0L))
        assertEquals(4, HostSpeeds.get("japn.tb-cdn.pw", 1_000L)?.downloads)
        assertNull(HostSpeeds.get("japn.tb-cdn.pw", HostSpeeds.KEEP_MS))
    }

    @Test
    fun `a new film is averaged with what the server delivered before`() {
        HostSpeeds.record("nexus.japn.tb-cdn.pw", HostSpeeds.Entry(10_000_000L, 4, 300L, 1_000L))
        assertEquals(10_000_000L, HostSpeeds.get("nexus.japn.tb-cdn.pw", 2_000L)?.perDownloadBps)
        HostSpeeds.record("nexus.japn.tb-cdn.pw", HostSpeeds.Entry(5_000_000L, 7, -1L, 5_000L))
        val e = HostSpeeds.get("nexus.japn.tb-cdn.pw", 6_000L)
        assertEquals(8_000_000L, e?.perDownloadBps)
        assertEquals(5, e?.downloads)
        assertEquals(300L, e?.firstByteMs)
        assertEquals(5_000L, e?.savedAtMs)
    }

    @Test
    fun `an update keeps the entry for another keep time`() {
        HostSpeeds.record("syd.download.real-debrid.com", HostSpeeds.Entry(4_000_000L, 3, 500L, 0L))
        HostSpeeds.record("syd.download.real-debrid.com", HostSpeeds.Entry(6_000_000L, 3, 400L, HostSpeeds.KEEP_MS - 10L))
        val e = HostSpeeds.get("syd.download.real-debrid.com", HostSpeeds.KEEP_MS + 1_000L)
        assertEquals(4_800_000L, e?.perDownloadBps)
        assertEquals(400L, e?.firstByteMs)
    }

    @Test
    fun `an expired entry is not averaged in`() {
        HostSpeeds.record("weur.example.com", HostSpeeds.Entry(4_000_000L, 3, 500L, 0L))
        HostSpeeds.record("weur.example.com", HostSpeeds.Entry(6_000_000L, 2, 400L, HostSpeeds.KEEP_MS + 5L))
        assertEquals(6_000_000L, HostSpeeds.get("weur.example.com", HostSpeeds.KEEP_MS + 10L)?.perDownloadBps)
    }
}
