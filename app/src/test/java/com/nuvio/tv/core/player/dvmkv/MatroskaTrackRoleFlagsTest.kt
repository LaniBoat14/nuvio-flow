package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class MatroskaTrackRoleFlagsTest {

    @Test
    fun `no flags means no role`() {
        assertEquals(0, MatroskaExtractor.roleFlagsFor(false, false, false, false))
    }

    @Test
    fun `each flag maps to its role`() {
        assertEquals(C.ROLE_FLAG_COMMENTARY, MatroskaExtractor.roleFlagsFor(true, false, false, false))
        assertEquals(C.ROLE_FLAG_DESCRIBES_VIDEO, MatroskaExtractor.roleFlagsFor(false, true, false, false))
        assertEquals(
            C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND,
            MatroskaExtractor.roleFlagsFor(false, false, true, false)
        )
        assertEquals(C.ROLE_FLAG_MAIN, MatroskaExtractor.roleFlagsFor(false, false, false, true))
    }

    @Test
    fun `original does not make a commentary track main`() {
        assertEquals(C.ROLE_FLAG_COMMENTARY, MatroskaExtractor.roleFlagsFor(true, false, false, true))
    }

    @Test
    fun `track flags in a file reach the published formats`() {
        val file = mkv(
            trackEntry(1, TYPE_AUDIO, "A_AC3", uint(0x55AF, 1)),
            trackEntry(2, TYPE_AUDIO, "A_AC3"),
            trackEntry(3, TYPE_AUDIO, "A_AC3", uint(0x55AC, 1), name("Descriptive")),
            trackEntry(4, TYPE_SUBTITLE, "S_TEXT/UTF8", uint(0x55AB, 1)),
            trackEntry(5, TYPE_AUDIO, "A_AC3", uint(0x55AE, 1)),
            trackEntry(6, TYPE_AUDIO, "A_AC3", uint(0x55AF, 0), uint(0x55AD, 1))
        )

        val formats = extractFormats(file, expectedTracks = 6)

        assertEquals(C.ROLE_FLAG_COMMENTARY, formats.getValue(1).roleFlags)
        assertEquals(0, formats.getValue(2).roleFlags)
        assertEquals(C.ROLE_FLAG_DESCRIBES_VIDEO, formats.getValue(3).roleFlags)
        assertEquals("Descriptive", formats.getValue(3).label)
        assertEquals(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, formats.getValue(4).roleFlags)
        assertEquals(C.ROLE_FLAG_MAIN, formats.getValue(5).roleFlags)
        assertEquals(C.ROLE_FLAG_DESCRIBES_VIDEO, formats.getValue(6).roleFlags)
        assertEquals(C.SELECTION_FLAG_DEFAULT, formats.getValue(1).selectionFlags)
    }

    private fun extractFormats(file: ByteArray, expectedTracks: Int): Map<Int, Format> {
        val formats = mutableMapOf<Int, Format>()
        val output = mockk<ExtractorOutput>(relaxed = true)
        every { output.track(any(), any()) } answers {
            val id = firstArg<Int>()
            val trackOutput = mockk<TrackOutput>(relaxed = true)
            val captured = slot<Format>()
            every { trackOutput.format(capture(captured)) } answers { formats[id] = captured.captured }
            trackOutput
        }
        val extractor = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, null)
        extractor.init(output)
        var position = 0
        val reader = object : DataReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= file.size) return C.RESULT_END_OF_INPUT
                val count = minOf(length, file.size - position)
                System.arraycopy(file, position, buffer, offset, count)
                position += count
                return count
            }
        }
        val input = DefaultExtractorInput(reader, 0, file.size.toLong())
        val holder = PositionHolder()
        try {
            repeat(50) {
                if (formats.size == expectedTracks) return formats
                if (extractor.read(input, holder) == Extractor.RESULT_END_OF_INPUT) return formats
            }
        } catch (e: ParserException) {
            // android.util.SparseArray is a no-op stub in JVM tests, so the extractor finds no tracks when Tracks
            // ends; every format has been published by then.
            if (formats.size != expectedTracks) throw e
        }
        assertEquals(expectedTracks, formats.size)
        return formats
    }

    private fun mkv(vararg trackEntries: ByteArray): ByteArray {
        val header = element(
            0x1A45DFA3,
            element(0x4282, "matroska".toByteArray()),
            uint(0x4287, 4),
            uint(0x4285, 2)
        )
        val info = element(0x1549A966, uint(0x2AD7B1, 1_000_000))
        val tracks = element(0x1654AE6B, *trackEntries)
        return header + element(0x18538067, info, tracks)
    }

    private fun trackEntry(number: Int, type: Int, codecId: String, vararg extra: ByteArray): ByteArray {
        val parts = mutableListOf(
            uint(0xD7, number.toLong()),
            uint(0x83, type.toLong()),
            element(0x86, codecId.toByteArray())
        )
        if (type == TYPE_AUDIO) {
            parts += element(0xE1, element(0xB5, floatBytes(48_000f)), uint(0x9F, 2))
        }
        parts += extra
        return element(0xAE, *parts.toTypedArray())
    }

    private fun name(value: String) = element(0x536E, value.toByteArray())

    private fun uint(id: Int, value: Long): ByteArray {
        val bytes = ByteArray(8) { i -> (value ushr (8 * (7 - i))).toByte() }
        return element(id, bytes.copyOfRange(bytes.indexOfFirst { it != 0.toByte() }.coerceIn(0, 7), 8))
    }

    private fun uint(id: Int, value: Int) = uint(id, value.toLong())

    private fun floatBytes(value: Float): ByteArray {
        val bits = java.lang.Float.floatToIntBits(value)
        return ByteArray(4) { i -> (bits ushr (8 * (3 - i))).toByte() }
    }

    private fun element(id: Int, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, part -> acc + part }
        val out = ByteArrayOutputStream()
        val idBytes = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        for (i in idBytes - 1 downTo 0) out.write((id ushr (8 * i)) and 0xFF)
        out.write(0x01)
        val size = body.size.toLong()
        for (i in 6 downTo 0) out.write(((size ushr (8 * i)) and 0xFF).toInt())
        out.write(body)
        return out.toByteArray()
    }

    companion object {
        private const val TYPE_AUDIO = 2
        private const val TYPE_SUBTITLE = 0x11
    }
}
