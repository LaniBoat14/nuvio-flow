package com.nuvio.tv.ui.screens.home

/** Resolve before composing the hero and rows, including the first frame after Back. */
internal fun resolveModernHomeInitialFocus(
    rows: List<HeroCarouselRow>,
    saved: HomeScreenFocusState
): Pair<HeroCarouselRow, Int>? {
    if (rows.isEmpty()) return null
    if (!saved.hasSavedFocus) return rows.first() to 0
    val row = rows.firstOrNull {
        if (saved.focusedRowKey != null) it.key == saved.focusedRowKey
        else if (saved.focusedRowIndex == -1) it.key == MODERN_CONTINUE_WATCHING_ROW_KEY
        else it.globalRowIndex == saved.focusedRowIndex
    } ?: rows.first()
    val itemKey = saved.focusedItemKeyByRow[row.key]
    val keyedIndex = itemKey?.let { key -> row.items.list.indexOfFirst { it.key == key } }
    val savedIndex = saved.focusedItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
    val index = when {
        keyedIndex == null -> savedIndex
        keyedIndex >= 0 -> keyedIndex
        // The focused title left its row while Home was away: the one that moved into its place takes focus.
        row.key == saved.focusedRowKey -> savedIndex
        else -> 0
    }
    return row to index
}
