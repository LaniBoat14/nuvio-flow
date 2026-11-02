package com.nuvio.tv.core.player

import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4ChapterReaderTest {

    @Test
    fun `nero chapter list is read in start order`() {
        val moov = box("moov", trak(1), box("udta", chpl(1, 25_000_000L to "The Harbour", 0L to "Opening", 61_500_000L to "")))

        val found = Mp4ChapterReader.read(moov) as Mp4ChapterReader.Found

        assertEquals("chpl", found.kind)
        assertEquals(ChaptersMetadata.SOURCE_MP4, found.chapters.source)
        assertEquals(listOf(0L, 2_500_000L, 6_150_000L), found.chapters.chapters.map { it.startUs })
        assertEquals(listOf(listOf("Opening"), listOf("The Harbour"), emptyList()), names(found.chapters))
    }

    @Test
    fun `version 0 nero list and fewer than two chapters`() {
        val two = box("moov", box("udta", chpl(0, 0L to "Opening", 10_000_000L to "Night Train")))
        assertEquals(listOf(0L, 1_000_000L), (Mp4ChapterReader.read(two) as Mp4ChapterReader.Found).chapters.chapters.map { it.startUs })

        assertNull(Mp4ChapterReader.read(box("moov", box("udta", chpl(1, 0L to "Opening")))))
        assertNull(Mp4ChapterReader.read(box("moov", trak(1))))
        assertNull(Mp4ChapterReader.read(box("free", trak(1))))
    }

    @Test
    fun `a truncated nero list keeps what is whole`() {
        val list = chpl(1, 0L to "Opening", 10_000_000L to "The Harbour", 20_000_000L to "Night Train")
        val cut = list.copyOf(list.size - 5)
        cut[3] = cut.size.toByte()
        val found = Mp4ChapterReader.read(box("moov", box("udta", cut))) as Mp4ChapterReader.Found
        assertEquals(2, found.chapters.chapters.size)
    }

    @Test
    fun `quickTime chapter track points at its names in the media data`() {
        val texts = listOf(text("Opening"), text("The Harbour"), utf16("Night Train"), ByteArray(2))
        val base = 5_000L
        val moov = box(
            "moov",
            trak(1, chapterRef = 2),
            chapterTrak(2, timescale = 600, durations = listOf(600, 900, 300, 1200), sizes = texts.map { it.size }, chunkOffset = base, language = "eng"),
        )

        val samples = (Mp4ChapterReader.read(moov) as Mp4ChapterReader.NeedsSamples).samples

        assertEquals(base, samples.position)
        assertEquals(texts.sumOf { it.size }, samples.length)
        val chapters = samples.chapters(texts.reduce { a, b -> a + b })!!
        assertEquals(listOf(0L, 1_000_000L, 2_500_000L, 3_000_000L), chapters.chapters.map { it.startUs })
        assertEquals(listOf(listOf("Opening"), listOf("The Harbour"), listOf("Night Train"), emptyList()), names(chapters))
        assertEquals("eng", chapters.chapters.first().names.single().language)
        assertNull(samples.chapters(ByteArray(3)))
    }

    @Test
    fun `nero list wins over the chapter track`() {
        val moov = box(
            "moov",
            trak(1, chapterRef = 2),
            chapterTrak(2, timescale = 1000, durations = listOf(1000, 1000), sizes = listOf(9, 9), chunkOffset = 100),
            box("udta", chpl(1, 0L to "Opening", 10_000_000L to "The Harbour")),
        )
        assertTrue(Mp4ChapterReader.read(moov) is Mp4ChapterReader.Found)
    }

    @Test
    fun `chapter track with names spread too far apart is left alone`() {
        val moov = box(
            "moov",
            trak(1, chapterRef = 2),
            chapterTrak(2, timescale = 1000, durations = listOf(1000, 1000), sizes = listOf(9, 9), chunkOffset = 100, chunks = listOf(100L, 10_000_000L), perChunk = 1),
        )
        assertNull(Mp4ChapterReader.read(moov))
    }

    @Test
    fun `chapter samples spread over several chunks`() {
        val moov = box(
            "moov",
            trak(1, chapterRef = 2),
            chapterTrak(2, timescale = 1000, durations = listOf(1000, 2000, 3000), sizes = listOf(4, 5, 6), chunkOffset = 0, chunks = listOf(200L, 300L), perChunk = 2),
        )
        val samples = (Mp4ChapterReader.read(moov) as Mp4ChapterReader.NeedsSamples).samples
        assertEquals(200L, samples.position)
        assertEquals(106, samples.length)
        assertEquals(3, samples.count)
    }

    @Test
    fun `text samples decode utf-8 and utf-16 and skip empty ones`() {
        assertEquals("Opening", Mp4ChapterReader.textSample(text("Opening"), 0, 9))
        assertEquals("Night Train", Mp4ChapterReader.textSample(utf16("Night Train"), 0, 26))
        assertNull(Mp4ChapterReader.textSample(ByteArray(2), 0, 2))
        assertEquals("Ope", Mp4ChapterReader.textSample(text("Opening"), 0, 5))
        assertNull(Mp4ChapterReader.textSample(text("Opening"), 4, 9))
    }

    private fun names(chapters: ChaptersMetadata) = chapters.chapters.map { chapter -> chapter.names.map { it.text } }

    companion object {
        fun box(type: String, vararg children: ByteArray): ByteArray {
            val body = children.fold(ByteArray(0)) { a, b -> a + b }
            return u32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
        }

        private fun full(type: String, version: Int, vararg fields: ByteArray): ByteArray =
            box(type, byteArrayOf(version.toByte(), 0, 0, 0), *fields)

        fun chpl(version: Int, vararg chapters: Pair<Long, String>): ByteArray {
            val out = ByteArrayOutputStream()
            if (version == 1) out.write(u32(0))
            out.write(chapters.size)
            for ((start, name) in chapters) {
                out.write(u64(start))
                val bytes = name.toByteArray(Charsets.UTF_8)
                out.write(bytes.size)
                out.write(bytes)
            }
            return full("chpl", version, out.toByteArray())
        }

        fun trak(id: Int, chapterRef: Int? = null): ByteArray {
            val tkhd = full("tkhd", 0, u32(0), u32(0), u32(id), ByteArray(68))
            val tref = chapterRef?.let { box("tref", box("chap", u32(it))) } ?: ByteArray(0)
            return box("trak", tkhd, tref, box("mdia", full("hdlr", 0, u32(0), "vide".toByteArray(), ByteArray(12))))
        }

        fun chapterTrak(
            id: Int,
            timescale: Int,
            durations: List<Int>,
            sizes: List<Int>,
            chunkOffset: Long,
            language: String = "und",
            chunks: List<Long> = listOf(chunkOffset),
            perChunk: Int = durations.size,
        ): ByteArray {
            val tkhd = full("tkhd", 0, u32(0), u32(0), u32(id), ByteArray(68))
            val packed = language.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) }
            val mdhd = full("mdhd", 0, u32(0), u32(0), u32(timescale), u32(durations.sum()), u16(packed), u16(0))
            val hdlr = full("hdlr", 0, u32(0), "text".toByteArray(), ByteArray(12))
            val stts = full("stts", 0, u32(durations.size), *durations.flatMap { listOf(u32(1), u32(it)) }.toTypedArray())
            val stsz = full("stsz", 0, u32(0), u32(sizes.size), *sizes.map { u32(it) }.toTypedArray())
            val stsc = full("stsc", 0, u32(1), u32(1), u32(perChunk), u32(1))
            val stco = full("stco", 0, u32(chunks.size), *chunks.map { u32(it.toInt()) }.toTypedArray())
            val stbl = box("stbl", stts, stsz, stsc, stco)
            return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", stbl)))
        }

        fun text(value: String): ByteArray {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return u16(bytes.size) + bytes
        }

        fun utf16(value: String): ByteArray {
            val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.toByteArray(Charsets.UTF_16BE)
            return u16(bytes.size) + bytes
        }

        fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

        fun u32(value: Int) = byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

        fun u64(value: Long) = u32((value shr 32).toInt()) + u32(value.toInt())
    }
}
