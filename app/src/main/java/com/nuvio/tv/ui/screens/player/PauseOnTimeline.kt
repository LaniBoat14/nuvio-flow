package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent

/**
 * A pause made at the remote lands on the timeline, so Left and Right step through the thumbnails at once and OK
 * plays on from wherever the viewer stopped. The release of the key that paused must not click whatever took focus
 * in the meantime.
 */
internal object PauseOnTimeline {

    fun isConfirmKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Only the first key-down of a press counts; a held key repeats and the repeats do nothing. */
    fun firstPress(action: Int, repeatCount: Int): Boolean = action == KeyEvent.ACTION_DOWN && repeatCount == 0

    enum class Settle { TIMELINE, USUAL, STAY }

    /**
     * Where focus goes once the controls settle. A pause the viewer just made lands on the timeline when there is one
     * and thumbnails are on. A key pressed since that pause wins: focus stays on the control it reached, or takes the
     * usual place when it is still on no control at all.
     */
    fun afterSettle(
        pauseRequest: Int,
        seenRequest: Int,
        hasTimeline: Boolean,
        thumbnailsOn: Boolean,
        pressedSincePause: Boolean,
        onControl: Boolean,
    ): Settle = when {
        pauseRequest == seenRequest -> Settle.USUAL
        pressedSincePause -> if (onControl) Settle.STAY else Settle.USUAL
        hasTimeline && thumbnailsOn -> Settle.TIMELINE
        else -> Settle.USUAL
    }

    enum class Release { PASS, SWALLOW, FORGET }

    /** What the screen does with a key while the release of [armedKeyCode] is still awaited. */
    fun release(armedKeyCode: Int, keyCode: Int, action: Int, repeatCount: Int): Release = when {
        armedKeyCode < 0 || keyCode != armedKeyCode -> Release.PASS
        action == KeyEvent.ACTION_UP -> Release.SWALLOW
        // A fresh press of the same key: its earlier release never arrived.
        action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> Release.FORGET
        else -> Release.PASS
    }

    /** The position whose thumbnail stays above the bar: the glide's, or the last tap's while paused. */
    fun thumbnailPositionMs(
        thumbPositionMs: Long?,
        pendingPositionMs: Long?,
        gliding: Boolean,
        playing: Boolean,
        barShown: Boolean
    ): Long? = when {
        thumbPositionMs == null -> null
        gliding && pendingPositionMs != null -> thumbPositionMs
        !playing && barShown -> thumbPositionMs
        else -> null
    }

    /** After a held seek showed the strip, the picture keeps the strip's centre place until it goes away. */
    fun keepsStripPlace(keptBefore: Boolean, stripShown: Boolean, thumbnailShown: Boolean): Boolean =
        thumbnailShown && (stripShown || keptBefore)
}
