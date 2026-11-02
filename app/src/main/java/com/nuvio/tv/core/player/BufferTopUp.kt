package com.nuvio.tv.core.player

/**
 * Keeps the buffer topped up. media3 fills to the maximum and then waits until the buffer has fallen to the minimum;
 * with this the player loads again as soon as the buffer is [MARGIN_MS] below the maximum and one download piece
 * below the byte target. Memory limits are checked before this, so it never loads past them.
 */
class BufferTopUp(val maxBufferMs: Int, minBufferMs: Int, pieceBytes: Long = DEFAULT_PIECE_BYTES) {
    val pieceBytes: Long = pieceBytes.coerceAtLeast(MIN_PIECE_BYTES)
    val resumeBelowMs: Int = maxOf(minBufferMs, maxBufferMs - MARGIN_MS)
    private val resumeBelowUs: Long = resumeBelowMs * 1000L

    fun wantsLoading(bufferedDurationUs: Long, allocatedBytes: Long, targetBytes: Long): Boolean {
        if (bufferedDurationUs >= resumeBelowUs || targetBytes <= 0L) return false
        val piece = pieceBytes.coerceAtMost(targetBytes / 4)
        return allocatedBytes + piece <= targetBytes
    }

    fun describe(): String = "aheadMs=$maxBufferMs resumeBelowMs=$resumeBelowMs pieceKb=${pieceBytes / 1024}"

    companion object {
        const val MARGIN_MS = 3_000
        /** Two of the extractor's 1 MB loading steps, when no parallel piece size applies. */
        const val DEFAULT_PIECE_BYTES = 2L * 1024L * 1024L
        private const val MIN_PIECE_BYTES = 64L * 1024L
    }
}

/** The single Buffer ahead setting and the load-control durations the player derives from it. */
object BufferAhead {
    const val MIN_MS = 10_000
    const val MAX_MS = 120_000
    const val NATIVE_MEMORY_MAX_MS = 600_000

    /**
     * Below the load control's minimum the custom engine loads past its byte target (time before size), so the
     * minimum stays at the app's own 15 s however long Buffer ahead is.
     */
    const val LOAD_CONTROL_MIN_MS = 15_000

    fun limitMs(nativeMemory: Boolean): Int = if (nativeMemory) NATIVE_MEMORY_MAX_MS else MAX_MS

    /** Stored Min and Max become one value: the Max, which never sits below the Min. */
    fun fromStored(minBufferMs: Int, maxBufferMs: Int): Int = maxOf(minBufferMs, maxBufferMs)

    data class Durations(val minBufferMs: Int, val maxBufferMs: Int)

    /** media3 needs the minimum to cover both start thresholds and the maximum to cover the minimum. */
    fun durations(aheadMs: Int, bufferForPlaybackMs: Int, bufferForPlaybackAfterRebufferMs: Int): Durations {
        val min = maxOf(minOf(aheadMs, LOAD_CONTROL_MIN_MS), bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
        return Durations(minBufferMs = min, maxBufferMs = maxOf(aheadMs, min))
    }
}
