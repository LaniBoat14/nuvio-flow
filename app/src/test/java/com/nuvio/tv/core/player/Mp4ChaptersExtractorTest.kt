package com.nuvio.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import com.nuvio.tv.core.player.avi.ArraySource
import com.nuvio.tv.core.player.avi.runExtractor
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures: 4 s of 32x32 test picture made with ffmpeg, three made-up chapters, moov at the front or at the end. */
class Mp4ChaptersExtractorTest {

    @Test
    fun `nero list at the front reaches every track`() {
        val run = run(resource("front.mp4"))
        assertChapters(run)
        assertEquals(plain(resource("front.mp4")), run.sampleTimes())
    }

    @Test
    fun `nero list with the moov at the end`() {
        val run = run(resource("tail.mp4"))
        assertChapters(run)
        assertEquals(plain(resource("tail.mp4")), run.sampleTimes())
    }

    @Test
    fun `chapter track names are fetched before the tracks are published`() {
        for (name in listOf("front.mp4", "tail.mp4")) {
            val bytes = rename(resource(name), "chpl", "free")
            val run = run(bytes)
            assertChapters(run)
            assertEquals(plain(bytes), run.sampleTimes())
            val seekMapAt = run.events.indexOf("seekMap")
            val lastFormatAt = run.events.lastIndexOf("format")
            val firstSampleAt = run.events.indexOf("sample")
            assertTrue(name, lastFormatAt < seekMapAt && seekMapAt < firstSampleAt)
        }
    }

    @Test
    fun `chapter names past the end of the file are not fetched`() {
        val bytes = rename(resource("front.mp4"), "chpl", "free")
        val stco = String(bytes, Charsets.ISO_8859_1).lastIndexOf("stco")
        Mp4ChapterReaderTest.u32(bytes.size - 10).copyInto(bytes, stco + 12)
        val run = run(bytes, stopAtEnd = true)
        assertTrue(run.tracks.values.all { it.chapters() == null })
        assertNotNull(run.seekMap)
    }

    @Test
    fun `files without chapters come through unchanged`() {
        for (name in listOf("front.mp4", "tail.mp4")) {
            val bytes = rename(rename(resource(name), "chpl", "free"), "tref", "free")
            val run = run(bytes)
            assertTrue(run.tracks.values.all { it.chapters() == null })
            assertEquals(plain(bytes), run.sampleTimes())
            assertNotNull(run.seekMap)
        }
    }

    private fun assertChapters(run: EventOutput) {
        assertNotNull(run.seekMap)
        assertTrue(run.tracks.isNotEmpty())
        for (track in run.tracks.values) {
            val chapters = track.chapters()
            assertNotNull(chapters)
            assertEquals(ChaptersMetadata.SOURCE_MP4, chapters!!.source)
            assertEquals(listOf(0L, 1_000_000L, 2_500_000L), chapters.chapters.map { it.startUs })
            assertEquals(listOf("Opening", "The Harbour", "Night Train"), chapters.chapters.map { it.names.single().text })
        }
    }

    private fun run(bytes: ByteArray, stopAtEnd: Boolean = false): EventOutput {
        val output = EventOutput()
        val extractor = Mp4ChaptersExtractor(Mp4Extractor())
        extractor.init(output)
        try {
            runExtractor(extractor, ArraySource(bytes))
        } catch (e: EOFException) {
            if (!stopAtEnd) throw e
        }
        return output
    }

    private fun plain(bytes: ByteArray): Map<Int, List<Long>> {
        val output = EventOutput()
        val extractor = Mp4Extractor()
        extractor.init(output)
        runExtractor(extractor, ArraySource(bytes))
        assertNull(output.tracks.values.firstNotNullOfOrNull { it.chapters() })
        return output.sampleTimes()
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("mp4-chapters/$name")!!.use { it.readBytes() }

    private fun rename(bytes: ByteArray, from: String, to: String): ByteArray {
        val copy = bytes.copyOf()
        val pattern = from.toByteArray(Charsets.ISO_8859_1)
        var renamed = 0
        outer@ for (i in 0..copy.size - 4) {
            for (j in 0 until 4) if (copy[i + j] != pattern[j]) continue@outer
            to.toByteArray(Charsets.ISO_8859_1).copyInto(copy, i)
            renamed++
        }
        assertEquals(1, renamed)
        return copy
    }

    private class EventTrack(private val events: MutableList<String>) : TrackOutput {
        var format: Format? = null
        val times = ArrayList<Long>()
        private val scratch = ByteArray(64 * 1024)

        fun chapters(): ChaptersMetadata? {
            val metadata = format?.metadata ?: return null
            return (0 until metadata.length()).map { metadata[it] }.filterIsInstance<ChaptersMetadata>().singleOrNull()
        }

        override fun format(format: Format) {
            this.format = format
            events += "format"
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val read = input.read(scratch, 0, minOf(length, scratch.size))
            if (read == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
            return read
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            times += timeUs
            events += "sample"
        }
    }

    private class EventOutput : ExtractorOutput {
        val events = ArrayList<String>()
        val tracks = LinkedHashMap<Int, EventTrack>()
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { EventTrack(events) }

        override fun endTracks() {
            events += "endTracks"
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
            events += "seekMap"
        }

        fun sampleTimes(): Map<Int, List<Long>> = tracks.mapValues { it.value.times }
    }
}
