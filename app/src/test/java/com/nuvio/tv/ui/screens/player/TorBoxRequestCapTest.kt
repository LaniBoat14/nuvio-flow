package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** TorBox answers about ten requests running at once and refuses the rest; a manual setting stays under it. */
class TorBoxRequestCapTest {
    private val chunk = 1L * 1024L * 1024L
    private val total = 20L * 1024L * 1024L

    @Before fun start() {
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { System.nanoTime() / 1_000_000L }
        every { SystemClock.uptimeMillis() } answers { System.nanoTime() / 1_000_000L }
    }

    @After fun finish() {
        unmockkStatic(SystemClock::class)
        NuvioEngineConfig.set(NuvioEngineConfig.stockMode())
    }

    private fun link(host: String): Uri = mockk(relaxed = true) {
        every { this@mockk.host } returns host
        every { this@mockk.toString() } returns "https://$host/dld/film"
        every { getQueryParameter(any()) } returns null
    }

    private inner class Server(val link: Uri, val openMs: Long, val refuseFirst: Set<Long> = emptySet()) {
        val running = AtomicInteger(0)
        val most = AtomicInteger(0)
        private val refused = ConcurrentHashMap.newKeySet<Long>()
        val factory = mockk<OkHttpDataSource.Factory> {
            every { createDataSource() } answers {
                val ds = mockk<OkHttpDataSource>(relaxed = true)
                var position = 0L
                var end = 0L
                var open = false
                every { ds.open(any()) } answers {
                    val spec = firstArg<DataSpec>()
                    val piece = spec.position / chunk
                    if (spec.position > 0L && piece in refuseFirst && refused.add(piece)) {
                        throw HttpDataSource.InvalidResponseCodeException(429, "Too Many Requests", null, emptyMap(), spec, byteArrayOf())
                    }
                    most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    open = true
                    if (spec.position > 0L) Thread.sleep(openMs)
                    position = spec.position
                    end = if (spec.length > 0) spec.position + spec.length else total
                    end - position
                }
                every { ds.close() } answers { if (open) { open = false; running.decrementAndGet() } }
                every { ds.uri } returns link
                every { ds.responseCode } returns 206
                every { ds.responseHeaders } answers { mapOf("Content-Range" to listOf("bytes $position-${end - 1}/$total")) }
                every { ds.read(any(), any(), any()) } answers {
                    if (position >= end) return@answers -1
                    val n = minOf(thirdArg<Int>().toLong(), end - position, 256L * 1024L).toInt()
                    position += n
                    n
                }
                ds
            }
        }
    }

    private fun playThrough(server: Server): Int {
        val source = ParallelRangeDataSource(
            server.factory,
            parallelConnections = 5,
            prefetchDepthChunks = 15,
            chunkSize = chunk,
            shouldAllowBackgroundPrefetch = { true },
            isolateSession = true
        )
        try {
            source.open(DataSpec.Builder().setUri(server.link).setPosition(0L).build())
            val buffer = ByteArray(128 * 1024)
            var read = 0L
            while (read < total) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                read += n
            }
        } finally {
            source.close()
        }
        return server.most.get()
    }

    @Test fun `a player reading through a TorBox film never has more than eight requests running`() {
        val most = playThrough(Server(link("nexus-101.weur.tb-cdn.st"), openMs = 150L))
        assertTrue("most at once $most", most in 2..8)
    }

    @Test fun `other servers keep the whole read-ahead`() {
        val most = playThrough(Server(link("cdn.example.com"), openMs = 150L))
        assertTrue("most at once $most", most > 8)
    }

    @Test fun `a TorBox refusal tries again within a second and keeps the read-ahead`() {
        val server = Server(link("nexus-102.weur.tb-cdn.st"), openMs = 0L, refuseFirst = setOf(1L))
        val source = ParallelRangeDataSource(
            server.factory,
            parallelConnections = 5,
            prefetchDepthChunks = 15,
            chunkSize = chunk,
            shouldAllowBackgroundPrefetch = { false },
            isolateSession = true
        )
        try {
            source.open(DataSpec.Builder().setUri(server.link).setPosition(0L).build())
            val startedAt = System.currentTimeMillis()
            source.javaClass.getDeclaredMethod("ensureChunkScheduled", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(source, 1L)
            val resources = source.javaClass.getDeclaredField("resources").apply { isAccessible = true }.get(source)
            val session = resources.javaClass.getDeclaredField("currentChunkSession").apply { isAccessible = true }
                .get(resources)!!
            @Suppress("UNCHECKED_CAST")
            val futures = session.javaClass.getMethod("getFutures").invoke(session) as Map<Long, java.util.concurrent.Future<*>>
            futures.getValue(1L).get(10, TimeUnit.SECONDS)
            val tookMs = System.currentTimeMillis() - startedAt
            assertTrue("took $tookMs ms", tookMs < 1_500L)
            val cap = session.javaClass.getMethod("getRateLimitDepthCap").invoke(session) as AtomicInteger
            assertEquals(Int.MAX_VALUE, cap.get())
        } finally {
            source.close()
        }
    }
}
