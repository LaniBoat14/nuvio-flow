package com.nuvio.tv.core.player.thumbnail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class ServerThumbMathTest {
    @Test
    fun picksTheLargestWidthUpToTheDecodeWidth() {
        assertEquals(320, TrickplayGrid.chooseWidth(listOf(320), 480))
        assertEquals(480, TrickplayGrid.chooseWidth(listOf(320, 480), 480))
        assertEquals(320, TrickplayGrid.chooseWidth(listOf(320, 640, 1280), 480))
        assertEquals(320, TrickplayGrid.chooseWidth(listOf(320, 480), 320))
        assertEquals(800, TrickplayGrid.chooseWidth(listOf(1280, 800), 480))
        assertNull(TrickplayGrid.chooseWidth(emptyList(), 480))
        assertNull(TrickplayGrid.chooseWidth(listOf(0, -1), 480))
    }

    @Test
    fun widerPicturesAreDecodedSmaller() {
        assertEquals(1, TrickplayGrid.sampleSize(320, 480))
        assertEquals(1, TrickplayGrid.sampleSize(480, 480))
        assertEquals(2, TrickplayGrid.sampleSize(640, 480))
        assertEquals(2, TrickplayGrid.sampleSize(640, 320))
        assertEquals(4, TrickplayGrid.sampleSize(1280, 480))
        assertEquals(4, TrickplayGrid.sampleSize(800, 320))
        assertEquals(1, TrickplayGrid.sampleSize(320, 0))
    }

    @Test
    fun memoryEstimateFollowsTheBoxClass() {
        val small = MemoryGovernor.displayChargeMb(rgb565 = true, fourK = false, strong = false)
        val strong = MemoryGovernor.displayChargeMb(rgb565 = true, fourK = false, strong = true)
        // 35 MB decode, 24 frames 480x360 RGB565, one full-colour frame, 6 MB preload
        assertEquals(50, small)
        // 35 MB decode, 36 frames, one full-colour frame, 32 MB preload
        assertEquals(80, strong)
        assertEquals(137, MemoryGovernor.displayChargeMb(rgb565 = false, fourK = true, strong = true))
    }

    @Test
    fun placesPicturesRowByRowAcrossSheets() {
        val first = TrickplayGrid.cell(0, 10, 10)
        assertEquals(listOf(0, 0, 0), listOf(first.sheet, first.column, first.row))
        val c = TrickplayGrid.cell(23, 10, 10)
        assertEquals(listOf(0, 3, 2), listOf(c.sheet, c.column, c.row))
        val last = TrickplayGrid.cell(99, 10, 10)
        assertEquals(listOf(0, 9, 9), listOf(last.sheet, last.column, last.row))
        val next = TrickplayGrid.cell(100, 10, 10)
        assertEquals(listOf(1, 0, 0), listOf(next.sheet, next.column, next.row))
        val narrow = TrickplayGrid.cell(17, 4, 3)
        assertEquals(listOf(1, 1, 1), listOf(narrow.sheet, narrow.column, narrow.row))
    }

    @Test
    fun cutsTheRegionOutOfTheSheet() {
        assertArrayEquals(intArrayOf(960, 360, 1280, 540), TrickplayGrid.region(TrickplayGrid.cell(23, 10, 10), 320, 180))
    }

    @Test
    fun countsSheets() {
        assertEquals(8, TrickplayGrid.sheetCount(720, 10, 10))
        assertEquals(8, TrickplayGrid.sheetCount(701, 10, 10))
        assertEquals(1, TrickplayGrid.sheetCount(1, 10, 10))
        assertEquals(0, TrickplayGrid.sheetCount(0, 10, 10))
    }

    @Test
    fun mapsPositionsToPictures() {
        assertEquals(0, TrickplayGrid.indexFor(0L, 10_000L, 720))
        assertEquals(0, TrickplayGrid.indexFor(9_999L, 10_000L, 720))
        assertEquals(1, TrickplayGrid.indexFor(10_000L, 10_000L, 720))
        assertEquals(719, TrickplayGrid.indexFor(99_000_000L, 10_000L, 720))
        assertEquals(0, TrickplayGrid.indexFor(-5L, 10_000L, 720))
        assertEquals(-1, TrickplayGrid.indexFor(5L, 0L, 720))
    }

    @Test
    fun parsesABifFile() {
        val bif = bif(count = 3, unitMs = 0, pictures = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4, 5), byteArrayOf(6)))
        val index = BifIndex.parse(bif, fileLength = bif.size.toLong())
        assertNotNull(index)
        index!!
        assertEquals(3, index.count)
        assertEquals(1000L, index.unitMs)
        assertEquals(listOf(0L, 10_000L, 20_000L), (0 until 3).map(index::timeMs))
        assertEquals(10_000L, index.intervalMs())
        val first = index.start(0).toInt()
        assertEquals(BifIndex.indexBytes(3), index.start(0))
        assertArrayEquals(byteArrayOf(3, 4, 5), bif.copyOfRange(index.start(1).toInt(), index.end(1).toInt()))
        assertEquals(first + 6L, index.end(2))
        assertEquals(0, index.indexFor(0L))
        assertEquals(0, index.indexFor(9_999L))
        assertEquals(1, index.indexFor(10_000L))
        assertEquals(2, index.indexFor(50_000L))
    }

    @Test
    fun honoursTheTimeUnit() {
        val index = BifIndex.parse(bif(count = 2, unitMs = 500, pictures = listOf(byteArrayOf(1), byteArrayOf(2)), step = 4))!!
        assertEquals(500L, index.unitMs)
        assertEquals(2_000L, index.timeMs(1))
    }

    @Test
    fun rejectsMalformedBifFiles() {
        val good = bif(count = 3, unitMs = 1000, pictures = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        assertNull(BifIndex.parse(good.copyOf().also { it[1] = 0x43 }))
        assertNull(BifIndex.parse(good, length = 64 + 8))
        assertNull(BifIndex.parse(good, fileLength = good.size - 1L))
        assertNull(BifIndex.parse(ByteArray(10)))
        assertNull(BifIndex.parse("<html>Not found</html>".toByteArray()))
        val backwards = good.copyOf()
        putU32(backwards, 64 + 8 + 4, 1_000_000)
        assertNull(BifIndex.parse(backwards))
        val intoIndex = good.copyOf()
        putU32(intoIndex, 64 + 4, 8)
        assertNull(BifIndex.parse(intoIndex))
        val none = good.copyOf()
        putU32(none, 12, 0)
        assertNull(BifIndex.headerCount(none))
        assertEquals(3, BifIndex.headerCount(good))
    }

    @Test
    fun fetchesNearestFirstWithinTheCap() {
        val none = emptySet<Int>()
        assertEquals(4, ServerThumbOrder.next(8, 4, none, none, 0L, 0L, 100L))
        assertEquals(5, ServerThumbOrder.next(8, 4, setOf(4), none, 10L, 10L, 100L))
        assertEquals(3, ServerThumbOrder.next(8, 4, setOf(4, 5), none, 20L, 10L, 100L))
        assertEquals(6, ServerThumbOrder.next(8, 4, setOf(3, 4, 5), setOf(2), 30L, 10L, 100L))
        assertEquals(-1, ServerThumbOrder.next(2, 0, setOf(0, 1), none, 20L, 10L, 100L))
        assertEquals(7, ServerThumbOrder.next(8, 20, none, none, 0L, 0L, 100L))
    }

    @Test
    fun stopsAtTheCapButStillFetchesNearerChunks() {
        val none = emptySet<Int>()
        assertEquals(-1, ServerThumbOrder.next(10, 4, setOf(3, 4, 5), none, 90L, 30L, 100L))
        assertEquals(4, ServerThumbOrder.next(10, 4, setOf(0, 1, 2), none, 90L, 30L, 100L))
        assertEquals(0, ServerThumbOrder.evict(setOf(0, 1, 4), focus = 4, keep = 4))
        assertEquals(1, ServerThumbOrder.evict(setOf(0, 1, 4), focus = 4, keep = 0))
        assertNull(ServerThumbOrder.evict(setOf(4), focus = 4, keep = 4))
    }

    private fun bif(count: Int, unitMs: Int, pictures: List<ByteArray>, step: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(u32(0))
        out.write(u32(count))
        out.write(u32(unitMs))
        out.write(ByteArray(44))
        var offset = 64 + (count + 1) * 8
        for (i in 0 until count) {
            out.write(u32(i * step))
            out.write(u32(offset))
            offset += pictures[i].size
        }
        out.write(u32(-1))
        out.write(u32(offset))
        pictures.forEach(out::write)
        return out.toByteArray()
    }

    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun putU32(b: ByteArray, at: Int, v: Int) = u32(v).copyInto(b, at)
}
