package com.nuvio.tv.core.player.thumbnail

import okio.BufferedSource
import java.io.IOException

/** Reads a multipart/byteranges body: each part's Content-Range and bytes. */
internal object MultipartRanges {
    private const val MAX_LINE = 1024L
    private const val MAX_HEADER_LINES = 16

    fun boundary(contentType: String): String? =
        Regex("boundary=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)?.trim()

    /** (first, last, total) from "bytes first-last/total"; total -1 when "*". */
    fun contentRange(value: String?): Triple<Long, Long, Long>? {
        val m = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).find(value ?: return null) ?: return null
        val first = m.groupValues[1].toLongOrNull() ?: return null
        val last = m.groupValues[2].toLongOrNull() ?: return null
        if (last < first) return null
        return Triple(first, last, m.groupValues[3].toLongOrNull() ?: -1L)
    }

    /** The parts in the order sent; more than [maxBytes] in all, or a part without a range, is an error. */
    fun read(source: BufferedSource, boundary: String, maxBytes: Long, total: (Long) -> Unit = {}): List<Pair<Long, ByteArray>> {
        val delimiter = "--$boundary"
        val parts = ArrayList<Pair<Long, ByteArray>>()
        var bytes = 0L
        while (true) {
            val line = source.readUtf8LineStrict(MAX_LINE).trimEnd()
            if (line.isEmpty()) continue
            if (line == "$delimiter--") break
            if (line != delimiter) throw IOException("unexpected line in multipart body")
            var range: Triple<Long, Long, Long>? = null
            var headers = 0
            while (true) {
                val h = source.readUtf8LineStrict(MAX_LINE).trimEnd()
                if (h.isEmpty()) break
                if (++headers > MAX_HEADER_LINES) throw IOException("too many part headers")
                if (h.startsWith("Content-Range:", ignoreCase = true)) range = contentRange(h.substringAfter(':'))
            }
            val r = range ?: throw IOException("part without Content-Range")
            val len = r.second - r.first + 1
            bytes += len
            if (bytes > maxBytes) throw IOException("multipart answer above ${maxBytes / 1024} KB")
            if (r.third > 0) total(r.third)
            parts += r.first to source.readByteArray(len)
        }
        return parts
    }
}
