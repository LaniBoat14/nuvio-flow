package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.NuvioEngineConfig
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Each piece request reports its own wait for the first byte and the time after it. */
class ParallelRangePieceTimingTest {
    private val chunk = 2L * 1024L * 1024L
    private val total = 6L * 1024L * 1024L

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

    private fun uri(name: String): Uri = mockk(relaxed = true) {
        every { host } returns name
        every { this@mockk.toString() } returns "https://$name/file.mkv"
        every { getQueryParameter(any()) } returns null
    }

    private inner class Fixture(val waitMs: (DataSpec) -> Long, val served: (DataSpec) -> Uri) {
        val link = uri("link.example")
        val timings: MutableList<PieceTiming> = Collections.synchronizedList(mutableListOf())
        val factory = mockk<OkHttpDataSource.Factory> {
            every { createDataSource() } answers { transport() }
        }
        val source = ParallelRangeDataSource(
            factory,
            parallelConnections = 2,
            chunkSize = chunk,
            shouldAllowBackgroundPrefetch = { false },
            isolateSession = true,
            onPiece = { timings += it }
        )

        private fun transport(): OkHttpDataSource {
            val ds = mockk<OkHttpDataSource>(relaxed = true)
            var position = 0L
            var end = 0L
            var servedUri: Uri? = null
            every { ds.open(any()) } answers {
                val spec = firstArg<DataSpec>()
                Thread.sleep(waitMs(spec))
                servedUri = served(spec)
                position = spec.position
                end = if (spec.length > 0) spec.position + spec.length else total
                end - position
            }
            every { ds.uri } answers { servedUri }
            every { ds.responseCode } answers { if (servedUri != null) 206 else -1 }
            every { ds.responseHeaders } answers {
                mapOf("Content-Range" to listOf("bytes ${position}-${end - 1}/$total"))
            }
            every { ds.read(any(), any(), any()) } answers {
                if (position >= end) return@answers -1
                val n = minOf(thirdArg<Int>().toLong(), end - position, 64L * 1024L).toInt()
                Thread.sleep(2)
                position += n
                n
            }
            return ds
        }

        fun download(indices: List<Long>) {
            val schedule = source.javaClass.getDeclaredMethod("ensureChunkScheduled", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            indices.forEach { schedule.invoke(source, it) }
            val resources = source.javaClass.getDeclaredField("resources").apply { isAccessible = true }.get(source)
            val session = resources.javaClass.getDeclaredField("currentChunkSession")
                .apply { isAccessible = true }.get(resources)!!
            @Suppress("UNCHECKED_CAST")
            val futures = session.javaClass.getMethod("getFutures").invoke(session) as Map<Long, java.util.concurrent.Future<*>>
            indices.forEach { futures.getValue(it).get(10, TimeUnit.SECONDS) }
        }
    }

    @Test fun `a piece reports its own wait, not the queue or the start`() {
        val f = Fixture(waitMs = { spec -> if (spec.length > 0 && spec.position > 0L) 150L else 0L }, served = { it.uri })
        try {
            f.source.open(DataSpec.Builder().setUri(f.link).setPosition(0L).build())
            f.download(listOf(1L, 2L))
            val pieces = f.timings.filter { it.ttfbMs >= 100L }
            assertEquals(2, pieces.size)
            pieces.forEach {
                assertTrue("ttfb ${it.ttfbMs}", it.ttfbMs in 140L..1_000L)
                assertTrue("transfer ${it.transferMs}", it.transferMs > 0L)
                assertTrue(it.bytes in (chunk - 64L * 1024L)..chunk)
                assertFalse(it.redirected)
            }
        } finally { f.source.close() }
    }
}
