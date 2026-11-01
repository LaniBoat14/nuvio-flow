package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class FlowBufferedReadTest {
    private class Source(val payload: ByteArray) : DataSource {
        var offset = 0
        var reads = 0
        override fun open(dataSpec: DataSpec): Long { offset = dataSpec.position.toInt(); return (payload.size-offset).toLong() }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            reads++
            if (offset == payload.size) return -1
            val count = minOf(length, payload.size-offset)
            payload.copyInto(buffer, start, offset, offset+count); offset += count
            return count
        }
        override fun getUri(): Uri? = null
        override fun addTransferListener(listener: TransferListener) = Unit
        override fun close() = Unit
    }
    private fun spec(position: Long = 0) = DataSpec.Builder().setUri(mockk<Uri>(relaxed=true)).setPosition(position).build()
    @Test fun tinyReadsPreserveBytesAndBatchUpstreamCalls() {
        val payload = ByteArray(700_000) { (it * 31).toByte() }
        val upstream = Source(payload)
        val source = BufferedReadDataSource(upstream, 256*1024)
        source.open(spec())
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(184)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == -1) break
            result.write(buffer, 0, count)
        }
        assertArrayEquals(payload, result.toByteArray())
        assertTrue("Expected block reads, not thousands of tiny upstream reads", upstream.reads <= 4)
        assertEquals(0, source.read(buffer, 0, 0))
        source.close()
    }
    @Test fun directBufferReadsShareTheSameBufferedCursor() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        val source = FlowMediaReadDataSource(BufferedReadDataSource(Source(payload)))
        source.open(spec())
        val prefix = ByteArray(123)
        source.read(prefix, 0, prefix.size)
        val direct = java.nio.ByteBuffer.allocateDirect(1000)
        val count = source.read(direct, direct.remaining())
        direct.flip()
        val actual = ByteArray(count)
        direct.get(actual)
        assertArrayEquals(payload.copyOfRange(123, 123+count), actual)
        assertEquals((123+count).toLong(), FlowMediaReadMeter.bytes.get())
        source.close()
    }
    @Test fun reopenDiscardsOldBufferedBytesAndRestartsMediaEpoch() {
        val upstream = Source(ByteArray(500_000) { (it % 251).toByte() })
        val source = FlowMediaReadDataSource(BufferedReadDataSource(upstream))
        source.open(spec())
        val buffer = ByteArray(100)
        source.read(buffer, 0, 100)
        assertEquals(100L, FlowMediaReadMeter.bytes.get())
        val epoch = FlowMediaReadMeter.epoch.get()
        source.close()
        source.open(spec(200_000))
        assertTrue(FlowMediaReadMeter.epoch.get() > epoch)
        assertEquals(0L, FlowMediaReadMeter.bytes.get())
        source.read(buffer, 0, 100)
        assertArrayEquals(upstream.payload.copyOfRange(200_000, 200_100), buffer)
        source.close()
    }
}
