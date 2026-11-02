package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridHomeFocusRestoreTest {

    @Test
    fun `each card knows its section and place`() {
        val items = gridItemsWithStableKeys(section("a", "a1", "a2") + section("b", "b1"))
        val slots = gridFocusSlots(items)

        val a2 = slots.getValue(keyOf(items, "a2"))
        val b1 = slots.getValue(keyOf(items, "b1"))
        assertEquals(1, a2.index)
        assertEquals(0, b1.index)
        assertTrue(a2.sectionKey != b1.sectionKey)
    }

    @Test
    fun `the card now in the place of one that left takes focus`() {
        val before = gridItemsWithStableKeys(section("a", "a1", "a2", "a3") + section("b", "b1"))
        val slot = gridFocusSlots(before).getValue(keyOf(before, "a2"))
        val after = gridItemsWithStableKeys(section("a", "a1", "a3") + section("b", "b1"))

        assertEquals(listOf(keyOf(after, "a3"), keyOf(after, "a1")), gridReplacementFocusKeys(after, slot))
    }

    @Test
    fun `when the last card of a section left, the new last card takes focus`() {
        val before = gridItemsWithStableKeys(section("a", "a1", "a2"))
        val slot = gridFocusSlots(before).getValue(keyOf(before, "a2"))
        val after = gridItemsWithStableKeys(section("a", "a1"))

        assertEquals(listOf(keyOf(after, "a1")), gridReplacementFocusKeys(after, slot))
    }

    @Test
    fun `nothing is offered when the whole section is gone`() {
        val before = gridItemsWithStableKeys(section("a", "a1") + section("b", "b1"))
        val slot = gridFocusSlots(before).getValue(keyOf(before, "a1"))
        val after = gridItemsWithStableKeys(section("b", "b1"))

        assertEquals(emptyList<String>(), gridReplacementFocusKeys(after, slot))
    }

    private fun keyOf(items: List<Pair<GridItem, String>>, id: String): String =
        items.first { (item, _) -> item is GridItem.Content && item.item.id == id }.second

    private fun section(catalogId: String, vararg ids: String): List<GridItem> =
        listOf<GridItem>(
            GridItem.SectionDivider(
                catalogName = catalogId,
                catalogId = catalogId,
                addonBaseUrl = "https://example.test",
                addonId = "addon",
                type = "movie"
            )
        ) + ids.map { id ->
            GridItem.Content(
                item = movie(id),
                addonBaseUrl = "https://example.test",
                catalogId = catalogId,
                catalogName = catalogId,
                addonId = "addon"
            )
        }

    private fun movie(id: String) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
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
