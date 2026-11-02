package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class SubtitleRoutingTest {

    private val streamUrl = "https://video.example.com/v.mkv"
    private val streamHeaders = mapOf("X-Api-Key" to "stream-key", "Cookie" to "session=stream", "Referer" to "https://video.example.com/")
    private val markedUri = "https://subs.example.com/a.ass?token=a%20b&nuvio_type=subtitle"
    private val route = SubtitleRoute("https://subs.example.com/a.ass?token=a%20b", mapOf("X-Api-Key" to "own-key"))
    private val routes = mapOf(markedUri to route)

    private interface BufferSource : DataSource, ByteBufferDataReader

    @Test
    fun `media keeps buffer reads through the subtitle router`() {
        val media = mockk<BufferSource>(relaxed = true)
        every { media.supportsByteBufferRead() } returns true
        every { media.open(any()) } returns 10L
        every { media.read(any<ByteBuffer>(), any()) } returns 10
        val router = SubtitleRoutingDataSourceFactory(DataSource.Factory { media }, streamUrl, streamHeaders, routes)
            .createDataSource()
        assertFalse((router as ByteBufferDataReader).supportsByteBufferRead())
        val streamUri = mockk<Uri>(relaxed = true)
        every { streamUri.toString() } returns streamUrl
        router.open(DataSpec(streamUri))
        assertTrue(router.supportsByteBufferRead())
        assertEquals(10, router.read(ByteBuffer.allocateDirect(16), 16))
    }

    @Test
    fun `a configured http subtitle uri is routed and everything else goes to media`() {
        assertEquals(SubtitleRequestTarget.Routed(route), subtitleRequestTarget(markedUri, routes))
        assertEquals(SubtitleRequestTarget.Media, subtitleRequestTarget(streamUrl, routes))
        assertEquals(SubtitleRequestTarget.Media, subtitleRequestTarget("https://subs.example.com/a.ass?token=a%20b", routes))
        // Non-HTTP subtitles keep the media data source, marked or not.
        val local = mapOf("file:///sdcard/a.ass?nuvio_type=subtitle" to SubtitleRoute("file:///sdcard/a.ass", null))
        assertEquals(SubtitleRequestTarget.Media, subtitleRequestTarget("file:///sdcard/a.ass?nuvio_type=subtitle", local))
        assertEquals(SubtitleRequestTarget.Media, subtitleRequestTarget("https://cdn.example/v.mkv?not_nuvio_type=subtitle", routes))
    }

    @Test
    fun `a marked http subtitle uri without a route is refused, not sent to media`() {
        assertEquals(SubtitleRequestTarget.Refused, subtitleRequestTarget("https://other.example/b.ass?nuvio_type=subtitle", routes))
        assertEquals(SubtitleRequestTarget.Refused, subtitleRequestTarget("HTTP://other.example/b.ass?x=1&nuvio_type=subtitle#f", routes))
    }

    @Test
    fun `a routed subtitle is requested at its own url with its own headers and not the stream's credentials`() {
        val request = subtitleRouteRequest(route, streamUrl, streamHeaders)

        assertEquals("https://subs.example.com/a.ass?token=a%20b", request.url.toString())
        assertEquals("own-key", request.header("X-Api-Key"))
        assertNull(request.header("Cookie"))
        assertEquals("https://video.example.com/", request.header("Referer"))
    }

    @Test
    fun `a routed subtitle without its own headers does not get stream credentials`() {
        val request = subtitleRouteRequest(route.copy(headers = null), streamUrl, streamHeaders)

        assertNull(request.header("X-Api-Key"))
        assertNull(request.header("Cookie"))
    }
}
