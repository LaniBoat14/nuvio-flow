package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import com.nuvio.tv.data.local.RemoteKeyJobs

/**
 * Tells a press from a hold for a button that has a hold job. The hold fires once, on the first key repeat that
 * arrives [holdMs] after the key went down (or on the release, when a remote sends no repeats). A press fires on the
 * release when no hold fired. Nothing waits on a timer: a button without a hold job never comes here. The hold time
 * is measured from the first key-down's own event time: a TV remote over HDMI-CEC stamps every repeat with
 * downTime == eventTime, so the events' own down time says nothing.
 */
internal class RemotePressTracker(private val holdMs: Long) {
    enum class Outcome { NONE, WAITING, HOLD, HOLD_REPEAT, PRESS, RELEASED }

    var keyCode: Int = KeyEvent.KEYCODE_UNKNOWN
        private set
    var jobs: RemoteKeyJobs? = null
        private set
    private var downAt = 0L
    private var holdFired = false

    /** A key that was still down when another one started tracking; its leftovers are swallowed until it is let go. */
    private var leftover = KeyEvent.KEYCODE_UNKNOWN

    fun isTracking(keyCode: Int): Boolean =
        keyCode != KeyEvent.KEYCODE_UNKNOWN && (keyCode == this.keyCode || keyCode == leftover)

    fun start(keyCode: Int, downAt: Long, jobs: RemoteKeyJobs? = null) {
        if (this.keyCode != KeyEvent.KEYCODE_UNKNOWN && this.keyCode != keyCode) leftover = this.keyCode
        this.keyCode = keyCode
        this.downAt = downAt
        this.jobs = jobs
        holdFired = false
    }

    /** A fresh key-down of [keyCode]: whatever was left of its earlier press is over. */
    fun forget(keyCode: Int) {
        if (keyCode == this.keyCode) reset() else if (keyCode == leftover) leftover = KeyEvent.KEYCODE_UNKNOWN
    }

    fun reset() {
        keyCode = KeyEvent.KEYCODE_UNKNOWN
        jobs = null
        downAt = 0L
        holdFired = false
    }

    fun onKey(event: KeyEvent): Outcome = onKey(event.keyCode, event.action, event.repeatCount, event.eventTime - downAt)

    fun onKey(keyCode: Int, action: Int, repeatCount: Int, heldMs: Long): Outcome {
        if (keyCode == leftover && keyCode != this.keyCode) {
            if (action == KeyEvent.ACTION_UP) leftover = KeyEvent.KEYCODE_UNKNOWN
            return Outcome.WAITING
        }
        if (keyCode != this.keyCode || keyCode == KeyEvent.KEYCODE_UNKNOWN) return Outcome.NONE
        return when (action) {
            KeyEvent.ACTION_DOWN -> when {
                repeatCount == 0 -> Outcome.NONE
                holdFired -> Outcome.HOLD_REPEAT
                heldMs >= holdMs -> {
                    holdFired = true
                    Outcome.HOLD
                }
                else -> Outcome.WAITING
            }
            KeyEvent.ACTION_UP -> {
                val fired = holdFired
                reset()
                when {
                    fired -> Outcome.RELEASED
                    heldMs >= holdMs -> Outcome.HOLD
                    else -> Outcome.PRESS
                }
            }
            else -> Outcome.NONE
        }
    }
}
