package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchedTitlesMode
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWatchedTitlesTest {

    @Test
    fun `a watched movie leaves the row and the others keep their order`() {
        val row = row("movies", movie("m1"), movie("m2"), movie("m3"))
        val ids = HomeWatchedIds(movieIds = setOf("m2"))

        assertEquals(listOf("m1", "m3"), row.withoutWatched(ids).items.map { it.id })
    }

    @Test
    fun `a fully watched show leaves and a partly watched show stays`() {
        val row = row("shows", show("full"), show("partly"), show("tvtype", rawType = "tv"))
        val ids = HomeWatchedIds(seriesIds = setOf("full", "tvtype"))

        assertEquals(listOf("partly"), row.withoutWatched(ids).items.map { it.id })
    }

    @Test
    fun `a show comes back when a new season makes it no longer fully watched`() {
        val row = row("shows", show("s1"), show("s2"))

        val before = row.withoutWatched(HomeWatchedIds(seriesIds = setOf("s1")))
        val afterNewSeason = row.withoutWatched(HomeWatchedIds(seriesIds = emptySet()))

        assertEquals(listOf("s2"), before.items.map { it.id })
        assertEquals(listOf("s1", "s2"), afterNewSeason.items.map { it.id })
    }

    @Test
    fun `watched ids only count for their own type`() {
        val row = row("mixed", movie("x"), show("y"), other("x"))
        val ids = HomeWatchedIds(movieIds = setOf("y"), seriesIds = setOf("x"))

        assertEquals(listOf("x", "y", "x"), row.withoutWatched(ids).items.map { it.id })
    }

    @Test
    fun `a row with nothing watched is handed back unchanged`() {
        val row = row("movies", movie("m1"), movie("m2"))

        assertSame(row, row.withoutWatched(HomeWatchedIds(movieIds = setOf("other"))))
    }

    @Test
    fun `the focused row keeps the titles it shows until focus leaves it`() {
        val focused = row("focused", movie("m1"), movie("m2"), movie("m3"))
        val other = row("other", movie("m1"), movie("m4"))
        val ids = HomeWatchedIds(movieIds = setOf("m1", "m3"))
        val shown = listOf(focused, other)

        val whileFocused = listOf(focused, other).withoutWatched(ids, focused.stableKey(), shown)
        assertEquals(listOf("m1", "m2", "m3"), whileFocused[0].items.map { it.id })
        assertEquals(listOf("m4"), whileFocused[1].items.map { it.id })

        val afterFocusMoved = listOf(focused, other).withoutWatched(ids, "somewhere_else", shown)
        assertEquals(listOf("m2"), afterFocusMoved[0].items.map { it.id })
        assertEquals(listOf("m4"), afterFocusMoved[1].items.map { it.id })
    }

    @Test
    fun `the focus guard only keeps titles that were already on screen`() {
        val shownBefore = row("focused", movie("m1"), movie("m2"))
        val withNextPage = row("focused", movie("m1"), movie("m2"), movie("m9"))
        val ids = HomeWatchedIds(movieIds = setOf("m1", "m9"))

        val result = withNextPage.withoutWatched(
            ids,
            watchedFocusKeepIds(withNextPage, withNextPage.stableKey(), listOf(shownBefore))
        )

        assertEquals(listOf("m1", "m2"), result.items.map { it.id })
        assertTrue(watchedFocusKeepIds(withNextPage, null, listOf(shownBefore)).isEmpty())
    }

    @Test
    fun `a row left short by hiding asks for more pages, two at most`() {
        val source = row("movies", *(1..12).map { movie("m$it") }.toTypedArray())
        val shown = source.withoutWatched(HomeWatchedIds(movieIds = (1..4).map { "m$it" }.toSet()))

        assertTrue(shouldRefillAfterHidingWatched(source, shown, pagesFetched = 0))
        assertTrue(shouldRefillAfterHidingWatched(source, shown, pagesFetched = 1))
        assertFalse(shouldRefillAfterHidingWatched(source, shown, pagesFetched = 2))
        assertFalse(shouldRefillAfterHidingWatched(source.copy(hasMore = false), shown, pagesFetched = 0))
        assertFalse(shouldRefillAfterHidingWatched(source.copy(isLoading = true), shown, pagesFetched = 0))
    }

    @Test
    fun `a row that was short before hiding or still has ten titles is left alone`() {
        val shortRow = row("short", movie("m1"), movie("m2"))
        assertFalse(shouldRefillAfterHidingWatched(shortRow, shortRow, pagesFetched = 0))

        val source = row("movies", *(1..11).map { movie("m$it") }.toTypedArray())
        val shown = source.withoutWatched(HomeWatchedIds(movieIds = setOf("m1")))
        assertEquals(WATCHED_REFILL_MIN_TITLES, shown.items.size)
        assertFalse(shouldRefillAfterHidingWatched(source, shown, pagesFetched = 0))
    }

    @Test
    fun `only hide with loaded lists filters anything`() {
        assertFalse(HomeWatchedFilter(WatchedTitlesMode.HIDE, ids = null).hides)
        assertFalse(HomeWatchedFilter(WatchedTitlesMode.DIM, HomeWatchedIds()).hides)
        assertFalse(HomeWatchedFilter(WatchedTitlesMode.SHOW, HomeWatchedIds()).hides)
        assertTrue(HomeWatchedFilter(WatchedTitlesMode.HIDE, HomeWatchedIds()).hides)
    }

    private fun row(catalogId: String, vararg items: MetaPreview) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.test",
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.MOVIE,
        items = items.toList()
    )

    private fun movie(id: String) = preview(id, ContentType.MOVIE)

    private fun show(id: String, rawType: String = "series") = preview(id, ContentType.SERIES, rawType)

    private fun other(id: String) = preview(id, ContentType.MOVIE, rawType = "channel")

    private fun preview(id: String, type: ContentType, rawType: String = type.toApiString()) = MetaPreview(
        id = id,
        type = type,
        rawType = rawType,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
