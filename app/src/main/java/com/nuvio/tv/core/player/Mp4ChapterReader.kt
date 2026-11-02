package com.nuvio.tv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata

/** Where the chapter names of a QuickTime chapter track sit in the file, read in one go. */
internal class Mp4ChapterSamples(
    val position: Long,
    val length: Int,
    private val starts: LongArray,
    private val offsets: IntArray,
    private val sizes: IntArray,
    private val language: String?
) {
    val count: Int get() = starts.size

    @OptIn(UnstableApi::class)
    fun chapters(bytes: ByteArray): ChaptersMetadata? {
        if (bytes.size < length) return null
        val chapters = starts.indices.map { i ->
            val name = Mp4ChapterReader.textSample(bytes, offsets[i], sizes[i])
            ChaptersMetadata.Chapter(
                starts[i],
                C.TIME_UNSET,
                listOfNotNull(name?.let { ChaptersMetadata.Name(it, language) })
            )
        }
        return Mp4ChapterReader.finish(chapters)
    }
}

/**
 * Reads MP4 chapters from the moov box: the Nero chapter list (moov/udta/chpl) when there is one, else the
 * QuickTime chapter track (tref 'chap'), whose names live in the media data and need [Mp4ChapterSamples].
 */
@OptIn(UnstableApi::class)
internal object Mp4ChapterReader {

    sealed interface Result
    class Found(val chapters: ChaptersMetadata, val kind: String) : Result
    class NeedsSamples(val samples: Mp4ChapterSamples) : Result

    const val MAX_CHAPTERS = 999
    const val MAX_SAMPLE_SPAN = 256 * 1024

    private const val NERO_TICKS_PER_US = 10L

    private class Box(val type: Int, val start: Int, val payload: Int, val end: Int)

    private class Trak(
        val id: Int,
        val chapterRefs: List<Int>,
        val handler: Int,
        val timescale: Long,
        val language: String?,
        val stbl: Box?
    )

    /** [moov] holds the whole moov box, header included. */
    fun read(moov: ByteArray): Result? {
        val root = boxAt(moov, 0, moov.size) ?: return null
        if (root.type != type("moov")) return null
        val children = children(moov, root.payload, root.end)
        val udta = children.firstOrNull { it.type == type("udta") }
        val chpl = udta?.let { box -> children(moov, box.payload, box.end).firstOrNull { it.type == type("chpl") } }
        chpl?.let { neroChapters(moov, it) }?.let { return Found(it, "chpl") }

        val traks = children.filter { it.type == type("trak") }.mapNotNull { trak(moov, it) }
        val referenced = traks.flatMap { it.chapterRefs }.distinct()
        val chapterTrak = referenced.firstNotNullOfOrNull { id ->
            traks.firstOrNull { it.id == id && it.handler in TEXT_HANDLERS && it.stbl != null && it.timescale > 0 }
        } ?: return null
        return chapterSamples(moov, chapterTrak)?.let(::NeedsSamples)
    }

    /** Sorted, one chapter per start time, at most [MAX_CHAPTERS]; null below two, like the MKV reader. */
    fun finish(chapters: List<ChaptersMetadata.Chapter>): ChaptersMetadata? {
        val unique = chapters.filter { it.startUs >= 0 }.sortedBy { it.startUs }.distinctBy { it.startUs }.take(MAX_CHAPTERS)
        return if (unique.size < 2) null else ChaptersMetadata(unique, ChaptersMetadata.SOURCE_MP4)
    }

    private fun neroChapters(data: ByteArray, chpl: Box): ChaptersMetadata? {
        var at = chpl.payload
        if (at + 4 > chpl.end) return null
        val version = data[at].toInt() and 0xFF
        at += 4
        val count: Int
        if (version == 0) {
            if (at + 1 > chpl.end) return null
            count = data[at].toInt() and 0xFF
            at += 1
        } else {
            if (at + 5 > chpl.end) return null
            count = u32(data, at + 1).coerceAtMost(MAX_CHAPTERS.toLong() * 4).toInt()
            at += 5
        }
        val chapters = ArrayList<ChaptersMetadata.Chapter>()
        repeat(count) {
            if (at + 9 > chpl.end) return@repeat
            val start = u64(data, at)
            val length = data[at + 8].toInt() and 0xFF
            at += 9
            if (at + length > chpl.end) {
                at = chpl.end
                return@repeat
            }
            val name = String(data, at, length, Charsets.UTF_8).trim().trimEnd('\u0000')
            at += length
            if (start >= 0) {
                chapters += ChaptersMetadata.Chapter(
                    start / NERO_TICKS_PER_US,
                    C.TIME_UNSET,
                    if (name.isBlank()) emptyList() else listOf(ChaptersMetadata.Name(name, null))
                )
            }
        }
        return finish(chapters)
    }

    private fun trak(data: ByteArray, trak: Box): Trak? {
        val children = children(data, trak.payload, trak.end)
        val tkhd = children.firstOrNull { it.type == type("tkhd") } ?: return null
        val tkhdVersion = data[tkhd.payload].toInt() and 0xFF
        val idAt = tkhd.payload + if (tkhdVersion == 1) 20 else 12
        if (idAt + 4 > tkhd.end) return null
        val id = u32(data, idAt).toInt()
        val refs = children.firstOrNull { it.type == type("tref") }?.let { tref ->
            children(data, tref.payload, tref.end).filter { it.type == type("chap") }.flatMap { chap ->
                (chap.payload until chap.end - 3 step 4).map { u32(data, it).toInt() }
            }
        }.orEmpty()
        val mdia = children.firstOrNull { it.type == type("mdia") }
        val mdiaChildren = mdia?.let { children(data, it.payload, it.end) }.orEmpty()
        val hdlr = mdiaChildren.firstOrNull { it.type == type("hdlr") }
        val handler = hdlr?.takeIf { it.payload + 12 <= it.end }?.let { u32(data, it.payload + 8).toInt() } ?: 0
        val mdhd = mdiaChildren.firstOrNull { it.type == type("mdhd") }
        var timescale = 0L
        var language: String? = null
        if (mdhd != null) {
            val v1 = (data[mdhd.payload].toInt() and 0xFF) == 1
            val timescaleAt = mdhd.payload + if (v1) 20 else 12
            val languageAt = timescaleAt + if (v1) 12 else 8
            if (languageAt + 2 <= mdhd.end) {
                timescale = u32(data, timescaleAt)
                language = packedLanguage(u16(data, languageAt))
            }
        }
        val stbl = mdiaChildren.firstOrNull { it.type == type("minf") }
            ?.let { minf -> children(data, minf.payload, minf.end).firstOrNull { it.type == type("stbl") } }
        return Trak(id, refs, handler, timescale, language, stbl)
    }

    private fun chapterSamples(data: ByteArray, trak: Trak): Mp4ChapterSamples? {
        val stbl = trak.stbl ?: return null
        val boxes = children(data, stbl.payload, stbl.end)
        fun box(name: String) = boxes.firstOrNull { it.type == type(name) }
        val stts = box("stts") ?: return null
        val stsz = box("stsz") ?: return null
        val stsc = box("stsc") ?: return null
        val chunkOffsets = (box("stco")?.let { offsets(data, it, false) } ?: box("co64")?.let { offsets(data, it, true) })
            ?: return null

        if (stsz.payload + 12 > stsz.end) return null
        val fixedSize = u32(data, stsz.payload + 4)
        val sampleCount = u32(data, stsz.payload + 8).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()
        if (sampleCount < 2) return null
        if (fixedSize == 0L && stsz.payload + 12 + sampleCount * 4 > stsz.end) return null
        val sizes = IntArray(sampleCount) { i ->
            if (fixedSize != 0L) fixedSize.toInt() else u32(data, stsz.payload + 12 + i * 4).toInt()
        }
        if (sizes.any { it < 0 || it > MAX_SAMPLE_SPAN }) return null

        val starts = LongArray(sampleCount)
        var sample = 0
        var time = 0L
        if (stts.payload + 8 > stts.end) return null
        val sttsEntries = u32(data, stts.payload + 4)
        var at = stts.payload + 8
        var entry = 0L
        while (entry < sttsEntries && sample < sampleCount && at + 8 <= stts.end) {
            val runCount = u32(data, at)
            val delta = u32(data, at + 4)
            var run = 0L
            while (run < runCount && sample < sampleCount) {
                starts[sample++] = scaleToUs(time, trak.timescale)
                time += delta
                run++
            }
            at += 8
            entry++
        }
        if (sample < sampleCount) return null

        if (stsc.payload + 8 > stsc.end) return null
        val stscEntries = u32(data, stsc.payload + 4).toInt().coerceAtLeast(0)
        val firstChunks = ArrayList<Long>()
        val perChunk = ArrayList<Long>()
        for (i in 0 until stscEntries) {
            val entryAt = stsc.payload + 8 + i * 12
            if (entryAt + 12 > stsc.end) break
            firstChunks += u32(data, entryAt)
            perChunk += u32(data, entryAt + 4)
        }
        if (firstChunks.isEmpty()) return null

        val fileOffsets = LongArray(sampleCount)
        sample = 0
        var stscIndex = 0
        for (chunk in chunkOffsets.indices) {
            if (sample >= sampleCount) break
            val chunkNumber = chunk + 1L
            while (stscIndex + 1 < firstChunks.size && firstChunks[stscIndex + 1] <= chunkNumber) stscIndex++
            var offset = chunkOffsets[chunk]
            var inChunk = 0L
            while (inChunk < perChunk[stscIndex] && sample < sampleCount) {
                fileOffsets[sample] = offset
                offset += sizes[sample]
                sample++
                inChunk++
            }
        }
        if (sample < sampleCount) return null

        val first = fileOffsets.min()
        val end = fileOffsets.indices.maxOf { fileOffsets[it] + sizes[it] }
        if (first < 0 || end - first > MAX_SAMPLE_SPAN) return null
        return Mp4ChapterSamples(
            position = first,
            length = (end - first).toInt(),
            starts = starts,
            offsets = IntArray(sampleCount) { (fileOffsets[it] - first).toInt() },
            sizes = sizes,
            language = trak.language
        )
    }

    /** A QuickTime / 3GPP text sample: 16-bit length, then UTF-8 or UTF-16 with a byte order mark. */
    fun textSample(data: ByteArray, offset: Int, size: Int): String? {
        if (size < 2 || offset < 0 || offset + size > data.size) return null
        val length = minOf(u16(data, offset), size - 2)
        if (length <= 0) return null
        val at = offset + 2
        val text = when {
            length >= 2 && data[at] == 0xFE.toByte() && data[at + 1] == 0xFF.toByte() ->
                String(data, at + 2, length - 2, Charsets.UTF_16BE)
            length >= 2 && data[at] == 0xFF.toByte() && data[at + 1] == 0xFE.toByte() ->
                String(data, at + 2, length - 2, Charsets.UTF_16LE)
            else -> String(data, at, length, Charsets.UTF_8)
        }
        return text.trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun offsets(data: ByteArray, box: Box, wide: Boolean): LongArray? {
        if (box.payload + 8 > box.end) return null
        val count = u32(data, box.payload + 4)
        val step = if (wide) 8 else 4
        if (count < 0 || box.payload + 8 + count * step > box.end) return null
        return LongArray(count.toInt()) { i ->
            val at = box.payload + 8 + i * step
            if (wide) u64(data, at) else u32(data, at)
        }
    }

    private fun scaleToUs(time: Long, timescale: Long): Long =
        time / timescale * 1_000_000L + time % timescale * 1_000_000L / timescale

    private fun packedLanguage(packed: Int): String? {
        if (packed < 0x400 || packed == 0x7FFF) return null
        val chars = charArrayOf(
            ((packed shr 10 and 0x1F) + 0x60).toChar(),
            ((packed shr 5 and 0x1F) + 0x60).toChar(),
            ((packed and 0x1F) + 0x60).toChar()
        )
        if (chars.any { it !in 'a'..'z' }) return null
        return String(chars).takeUnless { it == "und" }
    }

    private fun children(data: ByteArray, start: Int, end: Int): List<Box> {
        val boxes = ArrayList<Box>()
        var at = start
        while (at + 8 <= end) {
            val box = boxAt(data, at, end) ?: break
            boxes += box
            at = box.end
        }
        return boxes
    }

    private fun boxAt(data: ByteArray, at: Int, limit: Int): Box? {
        if (at + 8 > limit) return null
        val size32 = u32(data, at)
        val type = u32(data, at + 4).toInt()
        val (size, header) = when (size32) {
            0L -> (limit - at).toLong() to 8
            1L -> {
                if (at + 16 > limit) return null
                u64(data, at + 8) to 16
            }
            else -> size32 to 8
        }
        if (size < header || at + size > limit) return null
        return Box(type, at, at + header, (at + size).toInt())
    }

    private val TEXT_HANDLERS = setOf(type("text"), type("sbtl"), type("subt"))

    private fun type(name: String): Int =
        (name[0].code shl 24) or (name[1].code shl 16) or (name[2].code shl 8) or name[3].code

    private fun u16(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF shl 8) or (data[at + 1].toInt() and 0xFF)

    internal fun u32(data: ByteArray, at: Int): Long =
        (data[at].toLong() and 0xFF shl 24) or (data[at + 1].toLong() and 0xFF shl 16) or
            (data[at + 2].toLong() and 0xFF shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)
}
