package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.ByteBufferDataReader
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

class AutoSpeedupDataSourceTest {

    private val file = ByteArray(12 * 1024 * 1024) { (it * 31 + 7).toByte() }
    private val uri: Uri = mockk(relaxed = true)

    private inner class FakeSource(
        val name: String,
        val log: MutableList<String>,
        val fail: Boolean = false,
        val data: ByteArray = file
    ) : DataSource {
        private var pos = 0
        private var end = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            if (fail) throw IOException("refused")
            log += "$name@${dataSpec.position}"
            pos = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size else (pos + dataSpec.length).toInt()
            return (end - pos).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            val n = minOf(length, end - pos, 64 * 1024)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }
        override fun getUri(): Uri? = null
        override fun close() { log += "$name closed" }
    }

    /** A path that reads straight into the caller's buffer, as the chunk session does. */
    private inner class FakeBufferSource(private val inner: FakeSource, val bufferReads: AtomicLong) :
        DataSource by inner, ByteBufferDataReader {
        override fun supportsByteBufferRead(): Boolean = true
        override fun read(buffer: ByteBuffer, length: Int): Int {
            val array = ByteArray(length)
            val n = inner.read(array, 0, length)
            if (n > 0) {
                buffer.put(array, 0, n)
                bufferReads.incrementAndGet()
            }
            return n
        }
    }

    private fun readAllBuffered(source: AutoSpeedupDataSource, spec: DataSpec, onEachRead: (Long) -> Unit = {}): ByteArray {
        source.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocateDirect(32 * 1024)
        val chunk = ByteArray(buf.capacity())
        while (true) {
            buf.clear()
            val n = source.read(buf, buf.capacity())
            if (n == C.RESULT_END_OF_INPUT) break
            assertEquals(n, buf.position())
            buf.flip()
            buf.get(chunk, 0, n)
            out.write(chunk, 0, n)
            onEachRead(out.size().toLong())
        }
        source.close()
        return out.toByteArray()
    }

    private fun readAll(source: DataSource, spec: DataSpec, onEachRead: (Long) -> Unit = {}): ByteArray {
        source.open(spec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
            onEachRead(out.size().toLong())
        }
        source.close()
        return out.toByteArray()
    }

    @Test
    fun `switches to the multi path mid-run without losing or repeating a byte`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val received = AtomicLong()
        var multiClosed = 0
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = received,
            onMultiClosed = { multiClosed++ }
        )
        val bytes = readAll(source, DataSpec(uri)) { read ->
            downloads = when {
                read >= 9L * 1024 * 1024 -> 1
                read >= 1L * 1024 * 1024 -> 2
                else -> 1
            }
        }
        assertArrayEquals(file, bytes)
        // Single-path bytes only: the chunk session counts its own as they arrive.
        assertEquals(4 * MB + 3 * MB, received.get())
        // Only after a 4 MB run and on a piece boundary, both ways.
        assertEquals("single@0", log[0])
        assertEquals("multi@${4 * 1024 * 1024}", log[1])
        assertEquals("single closed", log[2])
        assertEquals("single@${9 * MB}", log[3])
        assertEquals("multi closed", log[4])
        // At the open on the single path, and when the multi path is left.
        assertEquals(2, multiClosed)
    }

    private inner class FakePieces(
        val log: MutableList<String>,
        var allow: Boolean = true,
        val failing: Long = 0L,
        val data: ByteArray = file
    ) : AutoSpeedupDataSource.PieceSwitch {
        val prestarts = mutableListOf<Pair<Long, Long>>()
        val used = mutableListOf<Long>()
        val dropped = mutableListOf<Uri>()
        var onPrestart: () -> Unit = {}
        override fun source(pieceBytes: Long): DataSource =
            FakeSource("multi${pieceBytes / MB}", log, fail = pieceBytes == failing, data = data)
        override fun prestart(pieceBytes: Long, uri: Uri, position: Long): Boolean {
            prestarts += pieceBytes to position
            val answer = allow
            onPrestart()
            return answer
        }
        override fun dropPrestart(uri: Uri) {
            dropped += uri
        }
        override fun inUse(pieceBytes: Long) {
            used += pieceBytes
        }
    }

    @Test
    fun `the chunk session changes piece size on a boundary of both sizes without losing a byte`() {
        val log = mutableListOf<String>()
        var piece = MB
        val pieces = FakePieces(log)
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { piece },
            downloads = { 2 },
            received = AtomicLong(),
            pieces = pieces
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> if (read >= 5L * 1024 * 1024) piece = 3 * MB }
        assertArrayEquals(file, bytes)
        // 6 MB starts both a 1 MB and a 3 MB piece; the 3 MB piece there was fetched ahead.
        assertEquals(listOf("multi1@0", "multi3@${6 * MB}", "multi1 closed", "multi3 closed"), log)
        assertEquals(3 * MB to 6 * MB, pieces.prestarts.first())
        assertEquals(listOf(MB, 3 * MB), pieces.used.distinct())
    }

    @Test
    fun `the piece size is kept while another link's session waits`() {
        val log = mutableListOf<String>()
        var piece = MB
        val pieces = FakePieces(log, allow = false)
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { piece },
            downloads = { 2 },
            received = AtomicLong(),
            pieces = pieces
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> if (read >= 5L * 1024 * 1024) piece = 3 * MB }
        assertArrayEquals(file, bytes)
        assertEquals(listOf("multi1@0", "multi1 closed"), log)
        assertEquals(1, pieces.prestarts.size)
    }

    @Test
    fun `a held piece size is tried again later instead of waiting for the next seek`() {
        val log = mutableListOf<String>()
        val big = ByteArray(40 * 1024 * 1024) { (it * 13 + 3).toByte() }
        var piece = MB
        val pieces = FakePieces(log, allow = false, data = big)
        pieces.onPrestart = { pieces.allow = true }
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log, data = big) },
            multi = { FakeSource("multi", log, data = big) },
            pieceBytes = { piece },
            downloads = { 2 },
            received = AtomicLong(),
            pieces = pieces
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> if (read >= 5L * 1024 * 1024) piece = 3 * MB }
        assertArrayEquals(big, bytes)
        // Refused at 6 MB, tried again once another 16 MB had been read, changed at the next shared boundary.
        assertEquals(2, pieces.prestarts.size)
        assertEquals(listOf("multi1@0", "multi3@${21 * MB}", "multi1 closed", "multi3 closed"), log)
        assertEquals(listOf(MB, 3 * MB), pieces.used.distinct())
    }

    @Test
    fun `a failed piece size change reopens the old size instead of reading from the ended session`() {
        val log = mutableListOf<String>()
        var piece = MB
        val pieces = FakePieces(log, failing = 3 * MB)
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { piece },
            downloads = { 2 },
            received = AtomicLong(),
            pieces = pieces
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> if (read >= 5L * 1024 * 1024) piece = 3 * MB }
        assertArrayEquals(file, bytes)
        assertEquals(listOf("multi1@0", "multi3 closed", "multi1@${6 * MB}", "multi1 closed", "multi1 closed"), log)
        assertEquals(listOf(MB), pieces.used.distinct())
    }

    @Test
    fun `a failed piece size change that cannot reopen hands the error to the player`() {
        val log = mutableListOf<String>()
        var piece = MB
        var opens = 0
        val pieces = object : AutoSpeedupDataSource.PieceSwitch {
            override fun source(pieceBytes: Long): DataSource =
                FakeSource("multi${pieceBytes / MB}", log, fail = opens++ > 0)
            override fun prestart(pieceBytes: Long, uri: Uri, position: Long) = true
            override fun dropPrestart(uri: Uri) {}
            override fun inUse(pieceBytes: Long) {}
        }
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { piece },
            downloads = { 2 },
            received = AtomicLong(),
            pieces = pieces
        )
        var failed = false
        try {
            readAll(source, DataSpec(uri)) { read -> if (read >= 5L * 1024 * 1024) piece = 3 * MB }
        } catch (e: IOException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `a failed switch keeps reading from the open path`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log, fail = true) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> if (read >= 1L * 1024 * 1024) downloads = 2 }
        assertArrayEquals(file, bytes)
        // One try, closed again; the next would wait another 16 MB, past the end of this file.
        assertEquals(listOf("single@0", "multi closed", "single closed"), log)
    }

    @Test
    fun `short side reads never switch`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        source.open(DataSpec.Builder().setUri(uri).setPosition(1000).setLength(200_000).build())
        downloads = 2
        val buf = ByteArray(64 * 1024)
        while (source.read(buf, 0, buf.size) != C.RESULT_END_OF_INPUT) Unit
        source.close()
        assertEquals(listOf("single@1000", "single closed"), log)
    }

    @Test
    fun `only opens on the single path report how long the server took`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val times = mutableListOf<Long>()
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeSource("multi", log) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = AtomicLong(),
            onFirstByte = { times += it }
        )
        source.open(DataSpec(uri))
        source.close()
        downloads = 2
        source.open(DataSpec(uri))
        source.close()
        assertEquals(1, times.size)
        assertEquals(true, times[0] >= 0L)
    }

    @Test
    fun `the switch waits for the next piece boundary`() {
        val log = mutableListOf<String>()
        var downloads = 1
        var soFar = 0L
        var soFarAtMultiOpen = -1L
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { soFarAtMultiOpen = soFar; FakeSource("multi", log) },
            pieceBytes = { 3 * MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        val bytes = readAll(source, DataSpec(uri)) { read -> soFar = read; if (read >= MB) downloads = 2 }
        assertArrayEquals(file, bytes)
        assertEquals(listOf("single@0", "multi@${6 * MB}", "single closed", "multi closed"), log)
        // Opened ahead, as soon as the 4 MB run was done, not when the reader reached the boundary.
        assertEquals(4 * MB, soFarAtMultiOpen)
    }

    @Test
    fun `buffer reads go straight through on the multi path and are copied on the single path`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val received = AtomicLong()
        val bufferReads = AtomicLong()
        val supported = mutableListOf<Boolean>()
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeBufferSource(FakeSource("multi", log), bufferReads) },
            pieceBytes = { MB },
            downloads = { downloads },
            received = received
        )
        assertFalse(source.supportsByteBufferRead())
        val bytes = readAllBuffered(source, DataSpec(uri)) { read ->
            supported += source.supportsByteBufferRead()
            downloads = when {
                read >= 9L * 1024 * 1024 -> 1
                read >= 1L * 1024 * 1024 -> 2
                else -> 1
            }
        }
        assertArrayEquals(file, bytes)
        assertEquals(4 * MB + 3 * MB, received.get())
        assertEquals(listOf("single@0", "multi@${4 * MB}", "single closed", "single@${9 * MB}", "multi closed", "single closed"), log)
        // 5 MB on the multi path, read in 32 KB calls.
        assertEquals(5 * MB / (32 * 1024), bufferReads.get())
        assertTrue(supported.contains(true))
        assertFalse(supported.last())
    }

    @Test
    fun `a buffer read stops at the piece boundary before the switch`() {
        val log = mutableListOf<String>()
        var downloads = 1
        val source = AutoSpeedupDataSource(
            single = { FakeSource("single", log) },
            multi = { FakeBufferSource(FakeSource("multi", log), AtomicLong()) },
            pieceBytes = { 3 * MB },
            downloads = { downloads },
            received = AtomicLong()
        )
        val sizes = mutableListOf<Long>()
        val bytes = readAllBuffered(source, DataSpec.Builder().setUri(uri).setPosition(1000).build()) { read ->
            sizes += read
            if (read >= MB) downloads = 2
        }
        assertArrayEquals(file.copyOfRange(1000, file.size), bytes)
        assertTrue(sizes.contains(6 * MB - 1000))
        assertEquals("multi@${6 * MB}", log[1])
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
