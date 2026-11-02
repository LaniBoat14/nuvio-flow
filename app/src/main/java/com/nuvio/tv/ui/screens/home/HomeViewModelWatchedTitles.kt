package com.nuvio.tv.ui.screens.home

import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.WatchedTitlesMode
import com.nuvio.tv.domain.model.legacyKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

private const val WATCHED_LISTS_WAIT_MS = 1_500L

internal class HomeWatchedTitlesState {
    val filter = MutableStateFlow<HomeWatchedFilter?>(null)
    val refillPages = ConcurrentHashMap<String, Int>()

    @Volatile
    var homeShown = false

    /** Row that still shows watched titles because it had focus when they became watched. */
    @Volatile
    var heldRowKey: String? = null

    @Volatile
    var waitDeadlineMs = 0L

    var idsJob: Job? = null
}

internal fun HomeViewModel.observeWatchedTitlesPipeline() {
    viewModelScope.launch {
        layoutPreferenceDataStore.watchedTitlesMode
            .distinctUntilChanged()
            .collect { mode ->
                val previous = watchedTitles.filter.value
                if (mode == WatchedTitlesMode.HIDE) {
                    val keptIds = previous?.ids?.takeIf { previous.mode == WatchedTitlesMode.HIDE }
                    watchedTitles.filter.value = HomeWatchedFilter(mode, keptIds)
                    if (watchedTitles.idsJob?.isActive != true) startWatchedIdsObserver()
                } else {
                    watchedTitles.idsJob?.cancel()
                    watchedTitles.idsJob = null
                    watchedTitles.filter.value = HomeWatchedFilter(mode)
                }
                _uiState.update { state ->
                    if (state.watchedTitlesMode == mode) state else state.copy(watchedTitlesMode = mode)
                }
                val wasHiding = previous?.mode == WatchedTitlesMode.HIDE
                if (previous != null && wasHiding != (mode == WatchedTitlesMode.HIDE)) {
                    watchedTitles.refillPages.clear()
                    scheduleWatchedRowsUpdate()
                }
            }
    }
}

private fun HomeViewModel.startWatchedIdsObserver() {
    watchedTitles.idsJob = viewModelScope.launch {
        watchedSeriesStateHolder.loadFromDisk()
        combine(
            watchProgressRepository.observeWatchedMovieIds(),
            watchedSeriesStateHolder.fullyWatchedSeriesIds
        ) { movieIds, seriesIds ->
            HomeWatchedIds(movieIds = movieIds, seriesIds = seriesIds)
        }
            .distinctUntilChanged()
            .collect { ids ->
                watchedTitles.filter.update { current ->
                    if (current?.mode == WatchedTitlesMode.HIDE) current.copy(ids = ids) else current
                }
                scheduleWatchedRowsUpdate()
            }
    }
}

private fun HomeViewModel.scheduleWatchedRowsUpdate() {
    if (hasAnyCatalogRows()) scheduleUpdateCatalogRows()
}

/**
 * The first rows wait briefly for the saved watched lists, so a title is not shown and then pulled
 * away a moment later. If the lists take longer, the rows show in full and are filtered when they land.
 */
internal suspend fun HomeViewModel.awaitWatchedFilterPipeline(): HomeWatchedFilter? {
    val state = watchedTitles.filter
    val ready = { filter: HomeWatchedFilter? ->
        filter != null && (filter.mode != WatchedTitlesMode.HIDE || filter.ids != null)
    }
    if (ready(state.value)) return state.value
    val now = SystemClock.elapsedRealtime()
    if (watchedTitles.waitDeadlineMs == 0L) {
        watchedTitles.waitDeadlineMs = now + WATCHED_LISTS_WAIT_MS
    }
    val remaining = watchedTitles.waitDeadlineMs - now
    if (remaining > 0) {
        withTimeoutOrNull(remaining) { state.first { ready(it) } }
    }
    return state.value
}

internal fun HomeViewModel.requestWatchedRefillPages(sourceRows: List<CatalogRow>, shownRows: List<CatalogRow>) {
    val shownByKey = shownRows.associateBy { it.legacyKey() }
    val loading = _loadingCatalogs.value
    sourceRows.forEach { source ->
        val key = source.legacyKey()
        val shown = shownByKey[key] ?: return@forEach
        val fetched = watchedTitles.refillPages[key] ?: 0
        if (key in loading || !shouldRefillAfterHidingWatched(source, shown, fetched)) return@forEach
        watchedTitles.refillPages[key] = fetched + 1
        loadMoreCatalogItemsPipeline(source.catalogId, source.addonId, source.apiType)
    }
}

internal fun HomeViewModel.onWatchedFocusRowChanged(rowKey: String?) {
    val held = watchedTitles.heldRowKey ?: return
    if (held != rowKey) scheduleWatchedRowsUpdate()
}

internal fun HomeViewModel.onHomeLeftForWatchedTitles() {
    watchedTitles.homeShown = false
    if (watchedTitles.heldRowKey != null) scheduleWatchedRowsUpdate()
}
