package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.WatchedTitlesMode
import com.nuvio.tv.domain.model.stableKey

internal const val WATCHED_REFILL_MIN_TITLES = 10
internal const val WATCHED_REFILL_MAX_PAGES = 2

/** Movies watched or marked watched, and shows with every aired episode watched: the titles that carry the tick. */
internal data class HomeWatchedIds(
    val movieIds: Set<String> = emptySet(),
    val seriesIds: Set<String> = emptySet()
)

/** [ids] stays null until the watched lists have been read, so nothing is hidden on a guess. */
internal data class HomeWatchedFilter(
    val mode: WatchedTitlesMode,
    val ids: HomeWatchedIds? = null
) {
    val hides: Boolean get() = mode == WatchedTitlesMode.HIDE && ids != null
}

internal fun HomeWatchedIds.isWatched(item: MetaPreview): Boolean {
    val type = item.apiType
    return when {
        type.equals("movie", ignoreCase = true) -> item.id in movieIds
        type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true) -> item.id in seriesIds
        else -> false
    }
}

internal fun CatalogRow.withoutWatched(ids: HomeWatchedIds, keepIds: Set<String> = emptySet()): CatalogRow {
    if (items.isEmpty()) return this
    val kept = items.filter { it.id in keepIds || !ids.isWatched(it) }
    return if (kept.size == items.size) this else copy(items = kept)
}

/**
 * The titles of the focused row that are on screen right now. They stay until focus leaves the row,
 * so a title marked watched never disappears from under the focus ring.
 */
internal fun watchedFocusKeepIds(
    row: CatalogRow,
    focusedRowKey: String?,
    shownRows: List<CatalogRow>
): Set<String> {
    if (focusedRowKey == null) return emptySet()
    val rowKey = row.stableKey()
    if (rowKey != focusedRowKey) return emptySet()
    val shown = shownRows.firstOrNull { it.stableKey() == rowKey } ?: return emptySet()
    return shown.items.mapTo(HashSet(shown.items.size)) { it.id }
}

internal fun List<CatalogRow>.withoutWatched(
    ids: HomeWatchedIds,
    focusedRowKey: String?,
    shownRows: List<CatalogRow>
): List<CatalogRow> = map { row ->
    row.withoutWatched(ids, watchedFocusKeepIds(row, focusedRowKey, shownRows))
}

/** A row that hiding left short asks for another page, at most [WATCHED_REFILL_MAX_PAGES] until it starts again from page one. */
internal fun shouldRefillAfterHidingWatched(source: CatalogRow, shown: CatalogRow, pagesFetched: Int): Boolean {
    return shown.items.size < source.items.size &&
        shown.items.size < WATCHED_REFILL_MIN_TITLES &&
        source.hasMore &&
        !source.isLoading &&
        pagesFetched < WATCHED_REFILL_MAX_PAGES
}
