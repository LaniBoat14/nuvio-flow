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
}
