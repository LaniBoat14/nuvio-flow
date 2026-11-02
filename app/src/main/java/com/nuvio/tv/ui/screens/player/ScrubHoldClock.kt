package com.nuvio.tv.ui.screens.player

/**
 * Timing of a held seek key. A TV remote reaches the box over HDMI-CEC: every repeat arrives as a fresh press
 * (its down time is its own time), some TVs send a tap as two presses about 100 ms apart, and the repeat interval
 * differs per TV (about 100 ms up to 450 ms). A held key first repeats after about 400 ms; presses that follow
 * each other faster from the start are a doubled tap or a backlog the TV sends after the release, and never glide.
 * A backlog follows a hold: presses that start right after a hold's release do not even count as a tap, while a
 * doubled tap right after a tap keeps its step.
 * The glide stands still once no repeat has come for twice the longest interval seen in this hold, so it stops
 * close to the release even when the key-up comes late.
 */
class ScrubHoldClock {
    private var pressAt: Long? = null
    private var tapOnly = false
    private var releasedAt: Long? = null
    private var held = false
    private var releasedHold = false
    private var afterRelease = false
    private var undoTap = false
    private var lastRepeatAt = 0L
    private var longestGapMs = 0L

    /** The hold comes from a TV remote over HDMI-CEC. */
    var fromTv = false
        private set

    /** A tap (the first press) at [at]; a later repeat of the same press is measured from here. */
    fun press(at: Long) {
        pressAt = at
        tapOnly = false
        afterRelease = releasedHold && releasedAt?.let { at - it in 0..BACKLOG_AFTER_RELEASE_MS } ?: false
        held = false
        undoTap = false
        lastRepeatAt = 0L
        longestGapMs = 0L
    }

    fun release(at: Long) {
        pressAt = null
        releasedAt = at
        releasedHold = held
    }

    /** The key that came up last was held, not tapped. */
    val releasedAfterHold: Boolean get() = releasedHold

    /** True once when the last tap turned out to start a TV's backlog after a hold: its step is to be taken back. */
    fun takeUndoTap(): Boolean = undoTap.also { undoTap = false }

    /** When the hold began for this key repeat, or null while the repeat still belongs to the tap. */
    fun repeat(eventTime: Long, downTime: Long): Long? {
        if (downTime >= eventTime && tapOnly) return null
        val start = if (downTime < eventTime) downTime else pressAt
        if (start != null && eventTime - start < TAP_REPEAT_MS) {
            if (downTime >= eventTime) {
                if (!tapOnly && afterRelease) undoTap = true
                tapOnly = true
            }
            return null
        }
        if (lastRepeatAt > 0L && eventTime > lastRepeatAt) {
            longestGapMs = maxOf(longestGapMs, eventTime - lastRepeatAt)
        }
        lastRepeatAt = maxOf(lastRepeatAt, eventTime)
        fromTv = downTime >= eventTime
        held = true
        return start ?: eventTime
    }

    /** False once the repeats have stopped: the key is up even if its key-up has not arrived yet. */
    fun moving(now: Long): Boolean = lastRepeatAt > 0L && now - lastRepeatAt <= standStillMs()

    fun standStillMs(): Long =
        if (longestGapMs == 0L) MAX_STAND_STILL_MS
        else (longestGapMs * 2).coerceIn(MIN_STAND_STILL_MS, MAX_STAND_STILL_MS)

    companion object {
        /** A first repeat this soon after the press is the TV doubling a tap or sending a backlog, not a hold. */
        const val TAP_REPEAT_MS = 150L
        const val BACKLOG_AFTER_RELEASE_MS = 300L
        const val MIN_STAND_STILL_MS = 200L
        const val MAX_STAND_STILL_MS = 650L
    }
}
