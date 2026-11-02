package com.nuvio.tv.ui.screens.player

import okhttp3.Protocol
import org.junit.Assert.*
import org.junit.Test

class FlowNetworkPolicyTest {
    @Test fun singleConnectionCanNegotiateHttp2ButParallelKeepsIndependentConnections() {
        assertTrue(PlayerPlaybackNetworking.playbackHttpClient.protocols.contains(Protocol.HTTP_2))
        assertEquals(listOf(Protocol.HTTP_1_1), PlayerPlaybackNetworking.playbackHttpClient.protocols)
    }
    @Test fun singleConnectionDoesNotForceCustomReceiveWindowOrAddressFamily() {
        val client = PlayerPlaybackNetworking.playbackHttpClient
        assertEquals(okhttp3.Dns.SYSTEM, client.dns)
        assertFalse(client.socketFactory is com.nuvio.tv.core.network.PlaybackSocketFactory)
    }
}
