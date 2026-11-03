package com.nuvio.tv.core.player.thumbnail

/** Jellyfin / Silo trickplay sheets: picture n sits on sheet n / (tileWidth * tileHeight), filled row by row. */
internal object TrickplayGrid {
    class Cell(val sheet: Int, val column: Int, val row: Int)

    /** The largest width up to [max]; the smallest one when every width is larger. Null when none is usable. */
    fun chooseWidth(widths: Collection<Int>, max: Int): Int? {
        val usable = widths.filter { it > 0 }
        return usable.filter { it <= max }.maxOrNull() ?: usable.minOrNull()
    }

    /** Power-of-two decode step that brings [width] down to [max] or below. */
    fun sampleSize(width: Int, max: Int): Int {
        var sample = 1
        while (max > 0 && width / sample > max) sample *= 2
        return sample
    }

    fun cell(n: Int, tileWidth: Int, tileHeight: Int): Cell {
        val perSheet = tileWidth * tileHeight
        val within = n % perSheet
        return Cell(n / perSheet, within % tileWidth, within / tileWidth)
    }

    fun sheetCount(count: Int, tileWidth: Int, tileHeight: Int): Int {
        val perSheet = tileWidth * tileHeight
        return if (count <= 0 || perSheet <= 0) 0 else (count + perSheet - 1) / perSheet
    }

    /** The picture shown for [positionMs]: picture n covers n * interval up to the next one. */
    fun indexFor(positionMs: Long, intervalMs: Long, count: Int): Int {
        if (count <= 0 || intervalMs <= 0) return -1
        return (positionMs.coerceAtLeast(0L) / intervalMs).coerceAtMost(count - 1L).toInt()
    }

    /** Left, top, right, bottom of picture [cell] inside its sheet. */
    fun region(cell: Cell, width: Int, height: Int): IntArray {
        val left = cell.column * width
        val top = cell.row * height
        return intArrayOf(left, top, left + width, top + height)
    }
}

/**
 * The index of a BIF file (Roku's format, as Emby serves it): an 8-byte magic, version, picture count and a time
 * unit in ms (0 = 1000), 44 reserved bytes, then count + 1 entries of (time in units, absolute offset). Picture i is
 * the JPEG from offset i to offset i + 1. Little-endian.
 */
internal class BifIndex(val unitMs: Long, private val times: LongArray, private val offsets: LongArray) {
    val count: Int get() = times.size

    fun timeMs(i: Int): Long = times[i] * unitMs

    fun start(i: Int): Long = offsets[i]

    fun end(i: Int): Long = offsets[i + 1]

    /** Bytes the index covers, so the first picture starts no earlier. */
    val headerBytes: Long get() = indexBytes(count)

    /** The last picture at or before [positionMs], 0 before the first. */
    fun indexFor(positionMs: Long): Int {
        if (count == 0) return -1
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (timeMs(mid) <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Typical spacing of the pictures, for the seek strip. */
    fun intervalMs(): Long {
        if (count < 2) return unitMs
        val span = timeMs(count - 1) - timeMs(0)
        return (span / (count - 1)).coerceAtLeast(1L)
    }

    companion object {
        const val HEADER_BYTES = 64
        const val MAX_PICTURES = 200_000
        private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0D, 0x0A, 0x1A, 0x0A)

        fun indexBytes(count: Int): Long = HEADER_BYTES + (count + 1L) * 8L

        /** The picture count from the header, or null when [head] does not start a BIF file. */
        fun headerCount(head: ByteArray, length: Int = head.size): Int? {
            if (length < HEADER_BYTES || head.size < HEADER_BYTES) return null
            for (i in MAGIC.indices) if (head[i] != MAGIC[i]) return null
            val count = u32(head, 12)
            return if (count in 1..MAX_PICTURES) count.toInt() else null
        }

        /**
         * Parses the header and index out of [data] (the file's first [length] bytes). Null when it is not a BIF,
         * the index is cut short, or the offsets run backwards or into the index. [fileLength], when known, bounds
         * the last offset.
         */
        fun parse(data: ByteArray, length: Int = data.size, fileLength: Long? = null): BifIndex? {
            val count = headerCount(data, length) ?: return null
            val need = indexBytes(count)
            if (need > length) return null
            val unit = u32(data, 16).takeIf { it > 0 } ?: 1000L
            val times = LongArray(count)
            val offsets = LongArray(count + 1)
            for (i in 0..count) {
                val at = HEADER_BYTES + i * 8
                if (i < count) times[i] = u32(data, at)
                offsets[i] = u32(data, at + 4)
            }
            if (offsets[0] < need) return null
            for (i in 0 until count) {
                if (offsets[i + 1] < offsets[i]) return null
                if (i > 0 && times[i] < times[i - 1]) return null
            }
            if (fileLength != null && offsets[count] > fileLength) return null
            return BifIndex(unit, times, offsets)
        }

        private fun u32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or
                ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or
                ((b[at + 3].toLong() and 0xFF) shl 24)
    }
}

/** Which chunk to fetch next: nearest to [focus] first, never one farther than what the memory cap keeps. */
internal object ServerThumbOrder {
    /**
     * [stored] chunks and their total [storedBytes]; [averageBytes] estimates the next one. Returns -1 when all
     * chunks are in or the cap is reached by nearer ones.
     */
    fun next(
        chunkCount: Int,
        focus: Int,
        stored: Set<Int>,
        failed: Set<Int>,
        storedBytes: Long,
        averageBytes: Long,
        capBytes: Long
    ): Int {
        if (chunkCount <= 0) return -1
        val f = focus.coerceIn(0, chunkCount - 1)
        val farthestKept = stored.maxOfOrNull { kotlin.math.abs(it - f) } ?: -1
        val full = storedBytes + averageBytes > capBytes
        for (d in 0 until chunkCount) {
            for (c in if (d == 0) intArrayOf(f) else intArrayOf(f + d, f - d)) {
                if (c !in 0 until chunkCount || c in stored || c in failed) continue
                if (full && d >= farthestKept) return -1
                return c
            }
        }
        return -1
    }

    /** The stored chunk to drop when over the cap: the one farthest from [focus], never [keep]. */
    fun evict(stored: Set<Int>, focus: Int, keep: Int): Int? =
        stored.filter { it != keep }.maxByOrNull { kotlin.math.abs(it - focus) }
}
