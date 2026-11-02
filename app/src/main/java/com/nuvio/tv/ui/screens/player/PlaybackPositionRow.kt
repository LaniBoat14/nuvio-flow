package com.nuvio.tv.ui.screens.player

import java.util.concurrent.TimeUnit

/** Clock text shared by the seek bar and the Stats overlay: h:mm:ss past an hour, m:ss below it. */
internal fun formatSeekTime(millis: Long): String {
    if (millis <= 0) return "0:00"

    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

/** The Stats overlay's Position row. A live window or an unknown length shows the position alone. */
internal fun positionStatsRow(positionMs: Long, durationMs: Long, isLive: Boolean): StatsRow {
    val position = formatSeekTime(positionMs)
    val text = if (isLive || durationMs <= 0L) position else "$position / ${formatSeekTime(durationMs)}"
    return StatsRow("Position", text)
}
