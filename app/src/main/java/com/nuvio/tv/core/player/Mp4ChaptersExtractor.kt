package com.nuvio.tv.core.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import java.io.IOException

/**
 * Adds MP4 chapters to the track formats of the stock MP4 extractor. It keeps a copy of the moov box while the
 * extractor reads it and finds the chapters there. A QuickTime chapter track keeps its names in the media data: then
 * the seek map is held back, the names are read with one small range read and playback carries on where the
 * extractor was going next.
 */
@UnstableApi
internal class Mp4ChaptersExtractor(private val delegate: Extractor) : Extractor {

    private enum class Phase { SCAN, PENDING, DETOUR, RETURN, DONE }

    private class PauseForChapters : IOException()

    private val output = ChapterOutput()
    private val tee = TeeInput()
    private var phase = Phase.SCAN
    private var chapters: ChaptersMetadata? = null
    private var samples: Mp4ChapterSamples? = null
    private var returnPosition = UNSET

    private var nextTopLevel = 0L
    private var largeSizeAt = UNSET
    private var largeSizeType = 0
    private var moovStart = UNSET
    private var moovEnd = UNSET
    private var moov: ByteArray? = null

    override fun init(output: ExtractorOutput) {
        this.output.target = output
        delegate.init(this.output)
    }

    @Throws(IOException::class)
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun getSniffFailureDetails() = delegate.sniffFailureDetails

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        when (phase) {
            Phase.DETOUR -> return readChapterSamples(input, seekPosition)
            Phase.RETURN -> {
                if (input.position != returnPosition) {
                    seekPosition.position = returnPosition
                    return Extractor.RESULT_SEEK
                }
                phase = Phase.DONE
            }
            else -> Unit
        }
        if (phase == Phase.SCAN && moovStart == UNSET && (nextTopLevel == UNSET || input.position > nextTopLevel)) {
            phase = Phase.DONE
        }
        if (phase != Phase.SCAN) return delegate.read(input, seekPosition)
        tee.input = input
        val result = try {
            delegate.read(tee, seekPosition)
        } catch (pause: PauseForChapters) {
            return startDetour(input.position, seekPosition)
        } finally {
            tee.input = null
        }
        if (phase == Phase.SCAN && moovEnd != UNSET &&
            (moov == null || (result == Extractor.RESULT_SEEK && seekPosition.position >= moovEnd))
        ) {
            moov = null
            phase = Phase.DONE
        }
        if (phase == Phase.PENDING) {
            if (result == Extractor.RESULT_END_OF_INPUT) {
                finishDetour(null)
                return result
            }
            return startDetour(if (result == Extractor.RESULT_SEEK) seekPosition.position else input.position, seekPosition)
        }
        return result
    }

    override fun seek(position: Long, timeUs: Long) {
        if (phase == Phase.PENDING || phase == Phase.DETOUR) finishDetour(null)
        if (position == 0L) {
            phase = Phase.SCAN
            nextTopLevel = 0L
            largeSizeAt = UNSET
            moovStart = UNSET
            moovEnd = UNSET
            moov = null
        } else if (phase != Phase.SCAN) {
            phase = Phase.DONE
        }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation

    private fun startDetour(resumeAt: Long, seekPosition: PositionHolder): Int {
        val plan = samples
        if (plan == null) {
            finishDetour(null)
            return Extractor.RESULT_CONTINUE
        }
        returnPosition = resumeAt
        phase = Phase.DETOUR
        seekPosition.position = plan.position
        return Extractor.RESULT_SEEK
    }

    private fun readChapterSamples(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val plan = samples
        if (plan != null && input.position != plan.position) {
            seekPosition.position = plan.position
            return Extractor.RESULT_SEEK
        }
        val found = plan?.let {
            val bytes = ByteArray(it.length)
            try {
                if (input.readFully(bytes, 0, it.length, true)) it.chapters(bytes) else null
            } catch (e: IOException) {
                Log.w(TAG, "MP4 chapters at ${it.position} unreadable: ${e.message}")
                null
            }
        }
        if (found != null) Log.i(TAG, "MP4 chapters: ${found.chapters.size} (chapter track)")
        finishDetour(found)
        phase = Phase.RETURN
        seekPosition.position = returnPosition
        return Extractor.RESULT_SEEK
    }

    private fun finishDetour(found: ChaptersMetadata?) {
        samples = null
        phase = Phase.DONE
        if (found != null) {
            chapters = found
            output.republish()
        }
        output.release()
    }

    private fun observe(position: Long, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (moovStart == UNSET) {
            if (position == nextTopLevel && length >= 8) {
                val size = Mp4ChapterReader.u32(data, offset)
                val type = Mp4ChapterReader.u32(data, offset + 4).toInt()
                when (size) {
                    0L -> nextTopLevel = UNSET
                    1L -> {
                        largeSizeAt = position + 8
                        largeSizeType = type
                        if (length >= 16) topLevelBox(position, type, readLong(data, offset + 8))
                    }
                    else -> topLevelBox(position, type, size)
                }
            } else if (position == largeSizeAt && length >= 8) {
                topLevelBox(position - 8, largeSizeType, readLong(data, offset))
            }
        }
        val copy = moov ?: return
        val from = maxOf(position, moovStart)
        val to = minOf(position + length, moovEnd)
        if (to > from) System.arraycopy(data, offset + (from - position).toInt(), copy, (from - moovStart).toInt(), (to - from).toInt())
        if (position + length >= moovEnd) moovRead(copy)
    }

    private fun topLevelBox(position: Long, type: Int, size: Long) {
        largeSizeAt = UNSET
        if (size < 8) {
            nextTopLevel = UNSET
            return
        }
        nextTopLevel = position + size
        if (type != MOOV) return
        moovStart = position
        moovEnd = position + size
        if (size <= MAX_MOOV_BYTES) moov = ByteArray(size.toInt())
    }

    private fun moovRead(copy: ByteArray) {
        moov = null
        if (chapters != null) {
            phase = Phase.DONE
            return
        }
        val result = try {
            Mp4ChapterReader.read(copy)
        } catch (e: RuntimeException) {
            Log.w(TAG, "MP4 chapters unreadable: ${e.message}")
            null
        }
        when (result) {
            is Mp4ChapterReader.Found -> {
                chapters = result.chapters
                Log.i(TAG, "MP4 chapters: ${result.chapters.chapters.size} (${result.kind})")
                phase = Phase.DONE
            }
            is Mp4ChapterReader.NeedsSamples -> {
                val length = tee.input?.length ?: C.LENGTH_UNSET.toLong()
                val plan = result.samples
                if (length != C.LENGTH_UNSET.toLong() && plan.position + plan.length > length) {
                    phase = Phase.DONE
                } else {
                    samples = plan
                    phase = Phase.PENDING
                }
            }
            null -> phase = Phase.DONE
        }
    }

    private fun readLong(data: ByteArray, at: Int): Long =
        (Mp4ChapterReader.u32(data, at) shl 32) or Mp4ChapterReader.u32(data, at + 4)

    private inner class ChapterOutput : ExtractorOutput {
        lateinit var target: ExtractorOutput
        private val tracks = LinkedHashMap<Int, ChapterTrackOutput>()
        private var heldEndTracks = false
        private var heldSeekMap: SeekMap? = null

        private val holding: Boolean get() = phase == Phase.PENDING || phase == Phase.DETOUR

        override fun track(id: Int, type: Int): TrackOutput {
            val track = ChapterTrackOutput(target.track(id, type))
            tracks[id] = track
            return track
        }

        override fun endTracks() {
            if (holding) heldEndTracks = true else target.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) {
            if (holding) heldSeekMap = seekMap else target.seekMap(seekMap)
        }

        fun republish() = tracks.values.forEach { it.republish() }

        fun release() {
            if (heldEndTracks) {
                heldEndTracks = false
                target.endTracks()
            }
            heldSeekMap?.let {
                heldSeekMap = null
                target.seekMap(it)
            }
        }
    }

    private inner class ChapterTrackOutput(private val target: TrackOutput) : TrackOutput by target {
        private var last: Format? = null

        override fun format(format: Format) {
            last = format
            target.format(withChapters(format))
        }

        fun republish() {
            last?.let { target.format(withChapters(it)) }
        }

        private fun withChapters(format: Format): Format {
            val found = chapters ?: return format
            val metadata = format.metadata
            if (metadata != null && (0 until metadata.length()).any { metadata[it] is ChaptersMetadata }) return format
            return format.buildUpon()
                .setMetadata(metadata?.copyWithAppendedEntries(found) ?: Metadata(found))
                .build()
        }
    }

    /** Passes reads through and keeps what falls inside the moov box; skips there become reads. */
    private inner class TeeInput : ExtractorInput {
        var input: ExtractorInput? = null
        private val scratch = ByteArray(16 * 1024)

        private val source: ExtractorInput
            get() {
                if (phase == Phase.PENDING) throw PauseForChapters()
                return input ?: throw IOException("no input")
            }

        private fun inMoov(position: Long): Boolean =
            moov != null && position >= moovStart && position < moovEnd

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val from = source
            val position = from.position
            val read = from.read(buffer, offset, length)
            if (read > 0) observe(position, buffer, offset, read)
            return read
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            val from = source
            val position = from.position
            val done = from.readFully(target, offset, length, allowEndOfInput)
            if (done) observe(position, target, offset, length)
            return done
        }

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            readFully(target, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val from = source
            if (!inMoov(from.position)) return from.skip(length)
            return read(scratch, 0, minOf(length, scratch.size))
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            val from = source
            if (!inMoov(from.position)) return from.skipFully(length, allowEndOfInput)
            var left = length
            var first = true
            while (left > 0) {
                val chunk = minOf(left, scratch.size)
                if (!readFully(scratch, 0, chunk, allowEndOfInput && first)) return false
                left -= chunk
                first = false
            }
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(target: ByteArray, offset: Int, length: Int): Int = source.peek(target, offset, length)

        override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
            source.peekFully(target, offset, length, allowEndOfInput)

        override fun peekFully(target: ByteArray, offset: Int, length: Int) = source.peekFully(target, offset, length)

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean =
            source.advancePeekPosition(length, allowEndOfInput)

        override fun advancePeekPosition(length: Int) = source.advancePeekPosition(length)

        override fun resetPeekPosition() = checkNotNull(input).resetPeekPosition()

        override fun getPeekPosition(): Long = checkNotNull(input).peekPosition

        override fun getPosition(): Long = checkNotNull(input).position

        override fun getLength(): Long = checkNotNull(input).length

        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            checkNotNull(input).setRetryPosition(position, e)
        }
    }

    companion object {
        const val STOCK_MP4_EXTRACTOR = "androidx.media3.extractor.mp4.Mp4Extractor"
        private const val TAG = "Mp4Chapters"
        private const val MOOV = 0x6D6F6F76
        private const val UNSET = C.INDEX_UNSET.toLong()
        private const val MAX_MOOV_BYTES = 16L * 1024 * 1024
    }
}
