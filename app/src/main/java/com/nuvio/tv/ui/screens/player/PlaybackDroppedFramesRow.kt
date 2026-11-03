package com.nuvio.tv.ui.screens.player

/** The Stats overlay's Dropped row. The dot shows the worse of dropped and skipped frames. */
internal fun droppedFramesRow(
    presentation: PlaybackVideoPresentation,
    dropped: PlaybackDroppedFrameSample,
    skipped: PlaybackSkippedFrameSample?,
    nowMs: Long
): StatsRow {
    val tunnelled = presentation.isTunnelled
    val dropCount = dropped.count
    val text = when {
        presentation == PlaybackVideoPresentation.NATIVE -> "Not counted on the native route"
        dropCount == null -> "Unavailable"
        tunnelled -> "$dropCount · renderer source only (tunnelled)"
        skipped != null -> "$dropCount · skipped ${skipped.count}"
        else -> "$dropCount"
    }
    fun recent(atMs: Long?) = atMs != null && nowMs - atMs <= PlaybackStatsThresholds.RECENT_EVENT_WINDOW_MS
    val dot = when {
        dropCount == null || (tunnelled && dropCount == 0) -> StatsDot.NONE
        dropCount == 0 && (skipped?.count ?: 0) == 0 -> StatsDot.GOOD
        recent(dropped.increasedAtMs) || recent(skipped?.increasedAtMs) -> StatsDot.BAD
        else -> StatsDot.WARN
    }
    return StatsRow("Dropped", text, dot)
}
