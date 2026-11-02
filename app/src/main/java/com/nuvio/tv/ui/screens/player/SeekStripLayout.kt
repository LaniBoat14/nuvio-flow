package com.nuvio.tv.ui.screens.player

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Time layout of the seek strip. Tile i sits at anchorMs + i * spacingMs and shows the stored thumbnail
 * nearest that time.
 */
internal object SeekStripLayout {

    private const val MAX_TILES_PER_SECOND = 5.0
    private val MULTIPLES = longArrayOf(1L, 3L, 6L, 12L, 30L, 60L, 120L)

    /**
     * Grid multiple of the tile spacing for a glide at [speed] (film ms per ms): the smallest of a few round multiples
     * that passes at most [MAX_TILES_PER_SECOND] tiles a second, so the strip stays readable.
     */
    fun multipleForSpeed(speed: Float, baseSpacingMs: Long): Long {
        if (baseSpacingMs <= 0L || speed <= 0f) return 1L
        val filmMsPerSecond = speed * 1000.0
        return MULTIPLES.firstOrNull { filmMsPerSecond / (baseSpacingMs * it) <= MAX_TILES_PER_SECOND }
            ?: MULTIPLES.last()
    }

    /** Anchor for [newSpacingMs] that leaves every tile where it was on screen at [glideMs]. */
    fun respacedAnchor(anchorMs: Double, glideMs: Double, oldSpacingMs: Double, newSpacingMs: Double): Double {
        if (oldSpacingMs <= 0.0 || newSpacingMs <= 0.0) return anchorMs
        val near = anchorMs + Math.round((glideMs - anchorMs) / oldSpacingMs) * oldSpacingMs
        return glideMs + (near - glideMs) * newSpacingMs / oldSpacingMs
    }

    /** Indices of the tiles within [reachMs] of [posMs]. */
    fun tileIndices(anchorMs: Double, posMs: Double, reachMs: Double, spacingMs: Double): LongRange {
        if (spacingMs <= 0.0) return LongRange.EMPTY
        return ceil((posMs - reachMs - anchorMs) / spacingMs).toLong()..floor((posMs + reachMs - anchorMs) / spacingMs).toLong()
    }

    /**
     * A tile [dxPx] from the centre fades in as it comes out from behind the centre picture and out as it slides
     * under it, so no tile pops in or out at the centre frame's edges.
     */
    fun tileAlpha(dxPx: Float, centreWPx: Float, tileWPx: Float): Float {
        if (tileWPx <= 0f) return 1f
        return ((dxPx - (centreWPx - tileWPx) / 2f) / tileWPx).coerceIn(0f, 1f)
    }

    /** Stored thumbnail time for a tile at [tileMs]. */
    fun pictureMs(tileMs: Double, baseSpacingMs: Long): Long =
        if (baseSpacingMs <= 0L) tileMs.roundToLong() else (tileMs / baseSpacingMs).roundToLong() * baseSpacingMs
}
