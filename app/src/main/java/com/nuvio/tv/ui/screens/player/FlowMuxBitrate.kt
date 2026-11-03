package com.nuvio.tv.ui.screens.player

/** Container bytes per buffered media duration, never bytes per wall-clock download time. */
internal class FlowMuxBitrate {
    private data class Point(val timeMs: Long, val positionMs: Long, val bytes: Long)
    private val points = ArrayDeque<Point>()
    private var epoch: Point? = null
    var averageBps: Long? = null
        private set
    var currentBps: Long? = null
        private set

    fun reset() { points.clear(); epoch = null; averageBps = null; currentBps = null }

    fun sample(timeMs: Long, positionMs: Long, bytes: Long, discontinuity: Boolean = false) {
        val last = points.lastOrNull()
        if (discontinuity || (last != null && (bytes < last.bytes || positionMs < last.positionMs ||
                    positionMs - last.positionMs > 60_000 || timeMs < last.timeMs))) reset()
        val point = Point(timeMs, positionMs, bytes)
        if (epoch == null) epoch = point
        points.addLast(point)
        while (points.size > 1 && timeMs - points.first().timeMs > 10_000) points.removeFirst()
        fun rate(start: Point?, minimumMs: Long): Long? {
            if (start == null) return null
            val duration = positionMs - start.positionMs
            val size = bytes - start.bytes
            return if (duration >= minimumMs && size > 0) size * 8000 / duration else null
        }
        averageBps = rate(epoch, 5_000)
        currentBps = rate(points.firstOrNull(), 3_000)
    }
}
