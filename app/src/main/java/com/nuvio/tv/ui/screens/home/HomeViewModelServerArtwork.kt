package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.data.mediaserver.ArtworkOnDemand
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

private const val SERVER_ARTWORK_BATCH_MS = 300L

/** Home server rows only ask the addons for the titles on screen; the rest follow as the user scrolls. */
internal fun HomeViewModel.requestServerArtwork(rows: List<Pair<String, List<MetaPreview>>>) {
    serverCatalog.requestArtwork(rows)
}

// Classic and Grid have no visibility feed yet, so they keep looking up whole rows.
internal suspend fun HomeViewModel.serverArtworkContext(): CoroutineContext =
    if ((layoutPreferenceDataStore.selectedLayout.firstOrNull() ?: HomeLayout.MODERN) == HomeLayout.MODERN) ArtworkOnDemand else EmptyCoroutineContext

internal fun HomeViewModel.observeServerArtwork() {
    viewModelScope.launch {
        _uiState.map { state -> state.heroItems.map { it.id } }.distinctUntilChanged().collect { ids ->
            if (ids.isEmpty()) return@collect
            val shown = ids.toHashSet()
            val rows = synchronized(catalogStateLock) {
                currentHeroCatalogKeys.mapNotNull { catalogsMap[it] }.filter { ServerCatalog.isServerAddon(it.addonBaseUrl) }
            }
            val wanted = rows.map { row -> row.addonBaseUrl to row.items.filter { it.id in shown } }.filter { it.second.isNotEmpty() }
            if (wanted.isNotEmpty()) requestServerArtwork(wanted)
        }
    }
    viewModelScope.launch {
        serverCatalog.artworkUpdates.drop(1).conflate().collect {
            delay(SERVER_ARTWORK_BATCH_MS)
            applyServerArtwork()
        }
    }
}

private suspend fun HomeViewModel.applyServerArtwork() {
    val pattern = patternForScreen(
        layoutPreferenceDataStore.customPosterUrlPattern.first(),
        CustomPosterScreen.HOME,
        layoutPreferenceDataStore.customPosterEnabledScreens.first()
    )
    val rows = synchronized(catalogStateLock) {
        catalogsMap.filter { (_, row) -> ServerCatalog.isServerAddon(row.addonBaseUrl) }
    }
    if (rows.isEmpty()) return
    val updated = withContext(Dispatchers.Default) {
        rows.mapNotNull { (key, row) -> serverCatalog.withArtwork(row, pattern).takeIf { it !== row }?.let { key to (row to it) } }
    }
    var changed = false
    updated.forEach { (key, pair) ->
        val (before, after) = pair
        updateCatalogRow(key) { current ->
            val next = if (current === before) after else serverCatalog.withArtwork(current, pattern)
            if (next !== current) changed = true
            next
        }
    }
    if (changed) scheduleUpdateCatalogRows()
}
