package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.WatchProgress

internal fun resolveNextToWatchLatestProgress(
    latestProgress: WatchProgress?,
    progressEntries: Collection<WatchProgress>,
    isResumable: (WatchProgress) -> Boolean
): WatchProgress? {
    val resumeOverride = progressEntries
        .filter(isResumable)
        .maxWithOrNull(
            compareByDescending<WatchProgress> { it.lastWatched }
                .thenByDescending { it.season ?: 0 }
                .thenByDescending { it.episode ?: 0 }
        )
    return when {
        resumeOverride != null && (
            latestProgress == null ||
                !isResumable(latestProgress) ||
                resumeOverride.lastWatched >= latestProgress.lastWatched
            ) -> resumeOverride
        else -> latestProgress
    }
}

internal fun hasNextToWatchData(
    progressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    serverStateCount: Int = 0
): Boolean = progressMap.isNotEmpty() || watchedEpisodes.isNotEmpty() || serverStateCount > 0

/**
 * Prefers what the observers already hold over a one-shot repository read, which with a
 * remote provider can still be the empty seed value.
 */
internal inline fun pickNextToWatchInputs(
    observedProgress: Map<Pair<Int, Int>, WatchProgress>,
    observedWatched: Set<Pair<Int, Int>>,
    readProgress: () -> Map<Pair<Int, Int>, WatchProgress>,
    readWatched: () -> Set<Pair<Int, Int>>
): Pair<Map<Pair<Int, Int>, WatchProgress>, Set<Pair<Int, Int>>> =
    observedProgress.ifEmpty(readProgress) to observedWatched.ifEmpty(readWatched)

/**
 * A target worked out from real progress or watched data is never replaced by one worked
 * out from nothing.
 */
internal fun shouldReplaceNextToWatch(
    current: NextToWatch?,
    currentFromData: Boolean,
    candidate: NextToWatch,
    candidateFromData: Boolean
): Boolean = when {
    current == null -> true
    current == candidate -> false
    candidateFromData -> true
    else -> !currentFromData
}
