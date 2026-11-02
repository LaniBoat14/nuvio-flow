package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.Format
import com.nuvio.tv.core.player.avi.ArraySource
import com.nuvio.tv.core.player.avi.ByteSource
import com.nuvio.tv.core.player.avi.FileSource
import com.nuvio.tv.core.player.avi.RecordingOutput
import com.nuvio.tv.core.player.avi.runExtractor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MatroskaChaptersTest {

    @Test
    fun `default edition is used and hidden, disabled and nested chapters are left out`() {
        val output = read(resource("front.mkv"))
        val chapters = chaptersOf(output.track(C.TRACK_TYPE_VIDEO).format)
        assertNotNull(chapters)
        assertEquals(listOf(0L, 2_000_000L, 4_000_000L), chapters!!.chapters.map { it.startUs })
        assertEquals(listOf("Overture", "One Day More", "Finale"), chapters.chapters.map { it.names.first().text })
        assertEquals(listOf("Overture", "Ouverture"), chapters.chapters[0].names.map { it.text })
        assertEquals(4_000_000L, chapters.chapters[1].endUs)
        assertEquals(C.TIME_UNSET, chapters.chapters[2].endUs)
        assertTrue(output.seekMap!!.isSeekable)
    }

    @Test
    fun `chapters after the clusters are fetched and reading resumes at the first cluster`() {
        val output = read(resource("tail.mkv"))
        val video = output.track(C.TRACK_TYPE_VIDEO)
        val chapters = chaptersOf(video.format)
        assertNotNull(chapters)
        assertEquals(60, chapters!!.chapters.size)
        assertEquals("Tail chapter 60 with a fairly long name to fill space", chapters.chapters.last().names[0].text)
        assertTrue(output.seekMap!!.isSeekable)
        assertEquals(60, video.samples.size)
        assertEquals(0L, video.samples.first().timeUs)
    }

    @Test
    fun `chapters after the clusters without Cues still play from the start`() {
        val output = read(resource("tail-nocues.mkv"))
        val video = output.track(C.TRACK_TYPE_VIDEO)
        assertEquals(60, chaptersOf(video.format)!!.chapters.size)
        assertFalse(output.seekMap!!.isSeekable)
        assertEquals(60, video.samples.size)
        assertEquals(0L, video.samples.first().timeUs)
    }

    @Test
    fun `ordered editions are not shown`() {
        val output = read(resource("ordered.mkv"))
        assertNull(chaptersOf(output.track(C.TRACK_TYPE_VIDEO).format))
        assertEquals(60, output.track(C.TRACK_TYPE_VIDEO).samples.size)
    }

    @Test
    fun `a chapters pointer to the wrong place falls back to the normal index`() {
        val bytes = resource("tail.mkv")
        val chaptersAt = indexOf(bytes, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70), from = 0x1000)
        // Turn the Chapters element into a Tags element so the pointer lands on something else.
        byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67).copyInto(bytes, chaptersAt)
        val output = read(bytes)
        assertNull(chaptersOf(output.track(C.TRACK_TYPE_VIDEO).format))
        assertTrue(output.seekMap!!.isSeekable)
        assertEquals(60, output.track(C.TRACK_TYPE_VIDEO).samples.size)
        assertEquals(0L, output.track(C.TRACK_TYPE_VIDEO).samples.first().timeUs)
    }

    @Test
    fun `chapters detour is only taken for a pointer after the first cluster`() {
        assertTrue(MatroskaExtractor.shouldSeekForChapters(true, false, 5_000L, 1_000L, 10_000L))
        assertFalse(MatroskaExtractor.shouldSeekForChapters(true, false, 500L, 1_000L, 10_000L))
        assertFalse(MatroskaExtractor.shouldSeekForChapters(true, true, 5_000L, 1_000L, 10_000L))
        assertFalse(MatroskaExtractor.shouldSeekForChapters(false, false, 5_000L, 1_000L, 10_000L))
        assertFalse(MatroskaExtractor.shouldSeekForChapters(true, false, 20_000L, 1_000L, 10_000L))
        assertTrue(MatroskaExtractor.shouldSeekForChapters(true, false, 5_000L, 1_000L, C.LENGTH_UNSET.toLong()))
    }

    /** Real films with chapters on this machine (CHAPTER_SAMPLES, ';' separated, each "path=count"). */
    @Test
    fun `real files`() {
        val samples = (System.getenv("CHAPTER_SAMPLES") ?: "").split(';').filter { it.contains('=') }
            .map { File(it.substringBeforeLast('=')) to it.substringAfterLast('=').toInt() }
            .filter { it.first.isFile }
        assumeTrue(samples.isNotEmpty() && realSparseArray())
        samples.forEach { (file, expected) ->
            val output = RecordingOutput()
            val extractor = MatroskaExtractor().apply { init(output) }
            FileSource(file).use { source ->
                runExtractor(extractor, source) {
                    output.tracks.values.any { it.type == C.TRACK_TYPE_VIDEO && it.samples.size >= 5 }
                }
            }
            val chapters = chaptersOf(output.track(C.TRACK_TYPE_VIDEO).format)
            println("${file.name}: ${chapters?.chapters?.size} chapters, seekable ${output.seekMap?.isSeekable}")
            chapters?.chapters?.forEach { println("  ${it.startUs / 1000} ms ${it.names.map { n -> "${n.text} [${n.language}]" }}") }
            assertEquals(expected, chapters?.chapters?.size)
        }
    }

    private fun read(bytes: ByteArray): RecordingOutput {
        assumeTrue(realSparseArray())
        val output = RecordingOutput()
        val extractor = MatroskaExtractor().apply { init(output) }
        runExtractor(extractor, ArraySource(bytes) as ByteSource)
        return output
    }

    /** Gradle's stub android.jar answers SparseArray with defaults; the reader needs a working one. */
    private fun realSparseArray(): Boolean = runCatching {
        android.util.SparseArray<Int>().apply { put(1, 1) }.size() == 1
    }.getOrDefault(false)

    private fun chaptersOf(format: Format?): ChaptersMetadata? {
        val metadata = format?.metadata ?: return null
        return (0 until metadata.length()).map { metadata.get(it) }.filterIsInstance<ChaptersMetadata>().firstOrNull()
    }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("mkv-chapters/$name")!!.use { it.readBytes() }

    private fun indexOf(bytes: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..bytes.size - pattern.size) {
            for (j in pattern.indices) if (bytes[i + j] != pattern[j]) continue@outer
            return i
        }
        error("pattern not found")
    }
}
