package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters

/**
 * Where a committed seek lands. When the thumbnail slot's keyframe is known the seek goes to that frame, the one the
 * thumbnail showed. A tap without one snaps in the tap's direction: the keyframe nearest to 10 s ahead can be the
 * one already playing when keyframes lie 20 s or more apart, and the position would not move at all.
 */
internal object SeekLanding {
    class Landing(val positionMs: Long, val parameters: SeekParameters)

    /**
     * [targetMs] is the grid point of a tap or where a held seek stopped, [keyframeMs] its slot's keyframe when known,
     * [tapDeltaMs] how far taps moved the target from where playback was at the first of them, null after a hold.
     */
    fun of(targetMs: Long, keyframeMs: Long?, tapDeltaMs: Long?, durationMs: Long): Landing {
        if (keyframeMs != null) return Landing(keyframeMs, SeekParameters.CLOSEST_SYNC)
        val delta = tapDeltaMs ?: 0L
        val atEdge = targetMs < START_MS || (durationMs > 0L && targetMs >= durationMs)
        val parameters = when {
            delta == 0L || atEdge -> SeekParameters.CLOSEST_SYNC
            delta > 0L -> SeekParameters.NEXT_SYNC
            else -> SeekParameters.PREVIOUS_SYNC
        }
        return Landing(targetMs, parameters)
    }

    /** Below this the nearest keyframe is the first one; a step back there could land before it. */
    private const val START_MS = 1_000L
}
