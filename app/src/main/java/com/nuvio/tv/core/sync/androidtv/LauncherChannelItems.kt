package com.nuvio.tv.core.sync.androidtv

import com.nuvio.tv.core.recommendations.watchNextDescription
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.WatchProgress

data class ChannelSettingsSnapshot(
    val daysCap: Int,
    val dismissedNextUp: Set<String>,
    val useEpisodeThumbnails: Boolean
)

/** Fire OS carries the leanback flag but its home screen has no channel rows, so it gets nothing. */
internal fun launcherChannelsSupported(hasLeanback: Boolean, isFireTv: Boolean): Boolean =
    hasLeanback && !isFireTv

/** The row title on the home screen: the app name first, so two Nuvio apps side by side stay apart. */
internal fun launcherChannelName(appName: String, continueWatching: String): String =
    "${appName.trim()}: ${continueWatching.trim()}"

/**
 * Merges the cached in-progress and next-up items into the list the launcher channel shows,
 * applying the same settings as the in-app Continue Watching row: the days cap, dismissed
 * next-up entries, aired episodes only, and in-progress winning over next-up for one title.
 */
internal fun buildChannelItems(
    inProgress: List<CachedInProgressItem>,
    nextUp: List<CachedNextUpItem>,
    settings: ChannelSettingsSnapshot,
    nowMs: Long = System.currentTimeMillis()
): List<WatchProgress> {
    val cutoffMs = if (settings.daysCap == TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL) {
        null
    } else {
        val windowMs = settings.daysCap.toLong() * 24L * 60L * 60L * 1000L
        nowMs - windowMs
    }

    val filteredInProgress = inProgress
        .filter { cutoffMs == null || it.lastWatched >= cutoffMs }

    val filteredNextUp = nextUp
        .filter { it.hasAired }
        .filter { cutoffMs == null || it.lastWatched >= cutoffMs }
        .filter { nextUpDismissKey(it) !in settings.dismissedNextUp }

    val inProgressContentIds = filteredInProgress.mapTo(mutableSetOf()) { it.contentId }
    val deduplicatedNextUp = filteredNextUp.filter { it.contentId !in inProgressContentIds }

    data class SortableItem(val watchProgress: WatchProgress, val sortKey: Long)

    val inProgressSorted = filteredInProgress.map { item ->
        SortableItem(item.toWatchProgress(settings.useEpisodeThumbnails), item.lastWatched)
    }

    val nextUpSorted = deduplicatedNextUp.map { item ->
        SortableItem(item.toWatchProgress(settings.useEpisodeThumbnails), item.sortTimestamp)
    }

    return (inProgressSorted + nextUpSorted)
        .sortedByDescending { it.sortKey }
        .map { it.watchProgress }
        .distinctBy { it.contentId }
}

internal fun nextUpDismissKey(item: CachedNextUpItem): String {
    return buildString {
        append(item.contentId)
        if (item.seedSeason != null) {
            append("_s${item.seedSeason}")
            if (item.seedEpisode != null) append("e${item.seedEpisode}")
        }
    }
}

internal fun CachedInProgressItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        episodeThumbnail ?: backdrop
    } else {
        backdrop ?: episodeThumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}

/** Next-up items carry no playback position, so they show without a progress bar. */
internal fun CachedNextUpItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        thumbnail ?: backdrop
    } else {
        backdrop ?: thumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season,
        episode = episode,
        episodeTitle = episodeTitle,
        position = 0,
        duration = 0,
        lastWatched = sortTimestamp
    )
}

/** Synopsis per video id: the cached episode text first, then whatever meta is already in memory. */
internal fun channelDescriptions(
    items: List<WatchProgress>,
    inProgress: List<CachedInProgressItem>,
    nextUp: List<CachedNextUpItem>,
    cachedMeta: (type: String, id: String) -> Meta?
): Map<String, String> {
    val episodeText = HashMap<String, String>()
    nextUp.forEach { item -> item.episodeDescription?.takeIf { it.isNotBlank() }?.let { episodeText[item.videoId] = it } }
    inProgress.forEach { item -> item.episodeDescription?.takeIf { it.isNotBlank() }?.let { episodeText[item.videoId] = it } }
    return buildMap {
        items.forEach { item ->
            val known = episodeText[item.videoId]
            val meta = if (known == null) cachedMeta(item.contentType, item.contentId) else null
            watchNextDescription(item, meta, known)?.let { put(item.videoId, it) }
        }
    }
}
