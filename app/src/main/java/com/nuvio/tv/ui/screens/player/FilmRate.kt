package com.nuvio.tv.ui.screens.player

/**
 * The film's own rate over its last [MIN_MEDIA_MS] to [MAX_MEDIA_MS] of media: the bytes the player read for the
 * media it buffered, so a busy scene reads higher than the file's average. A seek starts it again.
 */
internal class FilmRate {
    private class Point(val bytes: Long, val bufferedMs: Long)

    private val points = ArrayDeque<Point>()

    /** One look: the bytes the player has read so far and the media position buffered to. */
    fun note(playerBytes: Long, bufferedMs: Long) {
        if (playerBytes < 0L || bufferedMs < 0L) return
        val last = points.lastOrNull()
        if (last != null) {
            val media = bufferedMs - last.bufferedMs
            if (playerBytes < last.bytes || media < 0L || media > MAX_STEP_MS) points.clear()
            else if (media == 0L) return
        }
        points.addLast(Point(playerBytes, bufferedMs))
        while (points.size > 2 && bufferedMs - points[1].bufferedMs >= MAX_MEDIA_MS) points.removeFirst()
    }

    fun clear() = points.clear()

    /** Bits per second of media, 0 until [MIN_MEDIA_MS] of media were read since the start or the last seek. */
    fun bps(): Long {
        val a = points.firstOrNull() ?: return 0L
        val b = points.last()
        val media = b.bufferedMs - a.bufferedMs
        if (media < MIN_MEDIA_MS) return 0L
        return (b.bytes - a.bytes) * 8_000L / media
    }

    companion object {
        const val MIN_MEDIA_MS = 30_000L
        const val MAX_MEDIA_MS = 60_000L
        /** More media than this between two looks half a second apart is a seek, not loading. */
        const val MAX_STEP_MS = 30_000L
    }
}
