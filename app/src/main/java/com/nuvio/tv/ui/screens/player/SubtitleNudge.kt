package com.nuvio.tv.ui.screens.player

/**
 * The Subtitles up / down remote jobs: one step of the saved vertical offset per press, and while the button is held
 * one step every [REPEAT_MS]. Counts from its own value during a hold, as the saved one arrives later.
 */
internal class SubtitleNudge {
    private var offset = 0
    private var lastStepMs = 0L

    fun press(savedOffset: Int, up: Boolean, nowMs: Long): Int {
        offset = step(savedOffset, up)
        lastStepMs = nowMs
        return offset
    }

    /** The next offset for a repeat of the held button, or null while the last step is too recent. */
    fun repeat(up: Boolean, nowMs: Long): Int? {
        if (nowMs - lastStepMs < REPEAT_MS) return null
        lastStepMs = nowMs
        val next = step(offset, up)
        if (next == offset) return null
        offset = next
        return offset
    }

    companion object {
        const val MIN_OFFSET = -20
        const val MAX_OFFSET = 50
        const val REPEAT_MS = 100L

        fun step(offset: Int, up: Boolean): Int = (offset + if (up) 1 else -1).coerceIn(MIN_OFFSET, MAX_OFFSET)
    }
}
