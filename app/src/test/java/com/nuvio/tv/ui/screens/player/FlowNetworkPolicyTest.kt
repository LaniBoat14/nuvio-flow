package com.nuvio.tv.ui.screens.player

import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

class FlowNetworkPolicyTest {
    @Test fun playbackUsesArcIndependentHttp11Connections() {
        assertEquals(listOf(Protocol.HTTP_1_1), PlayerPlaybackNetworking.playbackHttpClient.protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), PlayerPlaybackNetworking.trustAllPlaybackHttpClient.protocols)
    }
    @Test fun playbackUsesArcReceiveWindowAndAddressFamilyPolicy() {
        val client = PlayerPlaybackNetworking.playbackHttpClient
        assertTrue(client.dns is com.nuvio.tv.core.network.IPv4FirstDns)
        assertSame(com.nuvio.tv.core.network.PlaybackSocketFactory, client.socketFactory)
    }
}
