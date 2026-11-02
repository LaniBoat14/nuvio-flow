package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import io.mockk.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test

class FlowFreshConnectionTest {
    @Test fun recoveryRequestsUseFreshConnectionsAndReportActualPeer() {
        val server=MockWebServer();server.start()
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers {
            val value=firstArg<String>()
            mockk<Uri>(relaxed=true) {
                every { toString() } returns value
                every { host } returns "127.0.0.1"
            }
        }
        try {
            repeat(2){server.enqueue(MockResponse().setBody("payload"))}
            val factory=PlayerPlaybackNetworking.createRangeRecoveryFactory(emptyMap(),true)
            repeat(2) {
                val source=factory.createDataSource()
                try {
                    source.open(DataSpec.Builder().setUri(Uri.parse(server.url("/file").toString())).build())
                    val bytes=ByteArray(32)
                    while(source.read(bytes,0,bytes.size)>=0){}
                    val details=(source as FlowObservedConnection).details
                    assertEquals("127.0.0.1",details.peer)
                    assertEquals("http/1.1",details.protocol)
                } finally {source.close()}
            }
            assertEquals(0,server.takeRequest().sequenceNumber)
            assertEquals(0,server.takeRequest().sequenceNumber)
        } finally {server.shutdown();unmockkStatic(Uri::class)}
    }
}
