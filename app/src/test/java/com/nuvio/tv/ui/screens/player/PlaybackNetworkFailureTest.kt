package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException

class PlaybackNetworkFailureTest {
    private val spec: DataSpec = DataSpec.Builder().setUri(mockk<Uri>(relaxed = true)).build()

    private fun chunkError(cause: Throwable): Throwable =
        ExecutionException(IOException("Failed to download chunk 4 after 2 attempts", cause))

    @Test
    fun `network exceptions anywhere in the cause chain count as network failures`() {
        listOf(
            SocketException("Connection reset"),
            ConnectException("Connection refused"),
            UnknownHostException("cdn.example"),
            SocketTimeoutException("timeout"),
            HttpDataSource.HttpDataSourceException(
                IOException("unexpected end of stream"),
                spec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                HttpDataSource.HttpDataSourceException.TYPE_READ
            )
        ).forEach { cause ->
            assertTrue(cause.toString(), PlaybackNetworkFailure.hasNetworkCause(chunkError(cause)))
        }
    }

    @Test
    fun `failures without a network cause stay unclassified`() {
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(IOException("Short chunk")))
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(chunkError(EOFException())))
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(chunkError(IllegalStateException("bad box"))))
        assertFalse(PlaybackNetworkFailure.hasNetworkCause(null))
    }

    @Test
    fun `a chunk lost to the network becomes an http read failure with a network code`() {
        val failure = PlaybackNetworkFailure.chunkFailure(
            "Failed to download chunk 4",
            chunkError(SocketException("Connection reset")),
            spec
        )
        assertTrue(failure is HttpDataSource.HttpDataSourceException)
        failure as HttpDataSource.HttpDataSourceException
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, failure.reason)
        assertEquals(HttpDataSource.HttpDataSourceException.TYPE_READ, failure.type)
        assertTrue(PlaybackNetworkFailure.hasNetworkCause(failure))
    }

    @Test
    fun `a timed out chunk reports a timeout code`() {
        val failure = PlaybackNetworkFailure.chunkFailure(
            "Failed to download chunk 4",
            chunkError(SocketTimeoutException("read timed out")),
            spec
        ) as HttpDataSource.HttpDataSourceException
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, failure.reason)
    }

    @Test
    fun `an http status failure keeps its status in the chain`() {
        val notFound = HttpDataSource.InvalidResponseCodeException(
            404, "Not Found", null, emptyMap(), spec, byteArrayOf()
        )
        val failure = PlaybackNetworkFailure.chunkFailure("Failed to download chunk 4", chunkError(notFound), spec)
            as HttpDataSource.HttpDataSourceException
        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, failure.reason)
        assertSame(notFound, generateSequence<Throwable>(failure) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().first())
    }

    @Test
    fun `a broken chunk without a network cause stays a plain io failure`() {
        val failure = PlaybackNetworkFailure.chunkFailure(
            "Failed to download chunk 4",
            chunkError(EOFException("short body")),
            spec
        )
        assertFalse(failure is HttpDataSource.HttpDataSourceException)
        assertEquals("Failed to download chunk 4", failure.message)
    }

    private fun openError(cause: Throwable): Throwable = HttpDataSource.HttpDataSourceException(
        IOException(ExecutionException(cause)),
        spec,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        HttpDataSource.HttpDataSourceException.TYPE_OPEN
    )

    @Test
    fun `an unreachable server counts as a connect failure`() {
        listOf(
            SocketTimeoutException(
                "failed to connect to nexus-114.example/203.0.113.10 (port 443) from /192.168.1.20 (port 34494) after 6000ms"
            ),
            SocketTimeoutException("connect timed out"),
            ConnectException("Failed to connect to cdn.example/203.0.113.9:443"),
            NoRouteToHostException("No route to host"),
            UnknownHostException("cdn.example")
        ).forEach { cause ->
            assertTrue(cause.toString(), PlaybackNetworkFailure.isConnectFailure(openError(cause)))
            assertTrue(cause.toString(), PlaybackNetworkFailure.isConnectFailure(chunkError(openError(cause))))
        }
    }

    @Test
    fun `a slow first byte or a dropped body is not a connect failure`() {
        listOf(
            SocketTimeoutException("timeout"),
            SocketTimeoutException("Read timed out"),
            SocketException("Connection reset"),
            IOException("unexpected end of stream"),
            EOFException()
        ).forEach { cause ->
            assertFalse(cause.toString(), PlaybackNetworkFailure.isConnectFailure(openError(cause)))
        }
        assertFalse(PlaybackNetworkFailure.isConnectFailure(null))
    }

    @Test
    fun `only statuses that a fresh link can fix count as a refused link`() {
        fun status(code: Int) = chunkError(
            HttpDataSource.InvalidResponseCodeException(code, "fixture", null, emptyMap(), spec, byteArrayOf())
        )
        listOf(401, 403, 404, 410).forEach { assertTrue("$it", PlaybackNetworkFailure.isRefusedLink(status(it))) }
        listOf(416, 429, 500, 503).forEach { assertFalse("$it", PlaybackNetworkFailure.isRefusedLink(status(it))) }
        assertFalse(PlaybackNetworkFailure.isRefusedLink(chunkError(SocketException("Connection reset"))))
    }

    @Test
    fun `without a data spec the failure stays a plain io failure`() {
        val failure = PlaybackNetworkFailure.chunkFailure(
            "Failed to download chunk 4",
            chunkError(SocketException("Connection reset")),
            null
        )
        assertFalse(failure is HttpDataSource.HttpDataSourceException)
    }
}
