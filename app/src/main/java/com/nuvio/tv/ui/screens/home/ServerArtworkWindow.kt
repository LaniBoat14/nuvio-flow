package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.MetaPreview

private const val ROWS_BELOW = 1
private const val FIRST_CARDS = 10
private const val CARDS_AHEAD = 4

/** Cards of one row worth looking up: the ones on screen, or the first few, and a few beyond. */
internal fun serverArtworkCards(size: Int, visible: IntRange?): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val first = (visible?.first ?: 0).coerceIn(0, size - 1)
    val last = maxOf(visible?.last ?: -1, first + FIRST_CARDS - 1) + CARDS_AHEAD
    return first..minOf(size - 1, last)
}

/**
 * Server row titles on screen or about to be, as (row addon base URL, titles), top row first.
 * [visibleCards] gives a row's card range once it has been laid out.
 */
internal fun serverArtworkWindow(
    rows: List<HeroCarouselRow>,
    visibleRows: IntRange,
    visibleCards: (String) -> IntRange?
): List<Pair<String, List<MetaPreview>>> {
    if (visibleRows.isEmpty() || rows.isEmpty()) return emptyList()
    val last = minOf(rows.lastIndex, visibleRows.last + ROWS_BELOW)
    return (visibleRows.first.coerceAtLeast(0)..last).mapNotNull { index ->
        val row = rows[index]
        val baseUrl = (row.items.list.firstOrNull()?.payload as? ModernPayload.Catalog)?.addonBaseUrl
            ?.takeIf(ServerCatalog::isServerAddon) ?: return@mapNotNull null
        val items = row.items.list
        val titles = serverArtworkCards(items.size, visibleCards(row.key)).mapNotNull { items[it].metaPreview }
        titles.takeIf { it.isNotEmpty() }?.let { baseUrl to it }
    }
}
