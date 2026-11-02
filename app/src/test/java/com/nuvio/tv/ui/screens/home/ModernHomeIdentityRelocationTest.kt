package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeIdentityRelocationTest {

    @Test
    fun `focused item follows its identity when items are inserted`() {
        val relocatedIndex = findRelocatedItemIndex(
            previousIdentities = listOf("movie:a", "movie:b"),
            currentIdentities = listOf("movie:x", "movie:a", "movie:b"),
            storedIndex = 1
        )

        assertEquals(2, relocatedIndex)
    }

    @Test
    fun `missing previous or current identity leaves the stored index unchanged`() {
        assertNull(
            findRelocatedItemIndex(
                previousIdentities = null,
                currentIdentities = listOf("movie:a"),
                storedIndex = 0
            )
        )
        assertNull(
            findRelocatedItemIndex(
                previousIdentities = listOf("movie:a"),
                currentIdentities = listOf("movie:b"),
                storedIndex = 0
            )
        )
    }

    @Test
    fun `presentation lookups retain ordered payload identities`() {
        val row = HeroCarouselRow(
            key = "catalog",
            title = "Catalog",
            globalRowIndex = 0,
            items = StableList(
                listOf(
                    catalogItem(key = "first", id = "a", type = "movie"),
                    catalogItem(key = "second", id = "b", type = "series")
                )
            )
        )

        val identities = buildCarouselRowLookups(listOf(row))
            .itemIdentitiesByRow["catalog"]
            ?.list

        assertEquals(listOf("movie:a", "series:b"), identities)
    }

    @Test
    fun `continue watching card is found after its progress changed and it moved to the front`() {
        val avatar = cwItem("avatar", position = 1_000L)
        val before = cwRow(avatar, cwItem("suzume", position = 60_000L))
        val after = cwRow(cwItem("suzume", position = 240_000L), avatar)

        val relocated = findRelocatedItemIndex(
            previousIdentities = buildCarouselRowLookups(listOf(before)).itemIdentitiesByRow["cw"]?.list,
            currentIdentities = buildCarouselRowLookups(listOf(after)).itemIdentitiesByRow["cw"]!!.list,
            storedIndex = 1
        )

        assertEquals(0, relocated)
    }

    @Test
    fun `focused card moved to the front puts the row back at its start`() {
        assertEquals(0 to 0, scrollKeepingFocusedSlot(0, 0, oldFocusedIndex = 1, newFocusedIndex = 0))
        assertEquals(0 to 0, scrollKeepingFocusedSlot(2, 40, oldFocusedIndex = 3, newFocusedIndex = 0))
    }

    @Test
    fun `focused card keeps its slot when it moves further along`() {
        assertEquals(3 to 40, scrollKeepingFocusedSlot(2, 40, oldFocusedIndex = 3, newFocusedIndex = 4))
        assertEquals(5 to 12, scrollKeepingFocusedSlot(4, 12, oldFocusedIndex = 4, newFocusedIndex = 5))
    }

    private fun cwRow(vararg items: ModernCarouselItem) = HeroCarouselRow(
        key = "cw",
        title = "Continue watching",
        globalRowIndex = -1,
        items = StableList(items.toList())
    )

    private fun cwItem(id: String, position: Long): ModernCarouselItem {
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = id,
                contentType = "movie",
                name = id,
                poster = null,
                backdrop = null,
                logo = null,
                videoId = id,
                season = null,
                episode = null,
                episodeTitle = null,
                position = position,
                duration = 600_000L,
                lastWatched = position
            )
        )
        return catalogItem(key = continueWatchingItemKey(item), id = id, type = "movie")
            .copy(payload = ModernPayload.ContinueWatching(item))
    }

    private fun catalogItem(key: String, id: String, type: String): ModernCarouselItem {
        return ModernCarouselItem(
            key = key,
            title = id,
            subtitle = null,
            imageUrl = null,
            heroPreview = HeroPreview(
                title = id,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(),
                poster = null,
                backdrop = null,
                imageUrl = null
            ),
            payload = ModernPayload.Catalog(
                focusKey = key,
                itemId = id,
                itemType = type,
                addonBaseUrl = "https://example.test/manifest.json",
                trailerTitle = id,
                trailerReleaseInfo = null,
                trailerApiType = type
            )
        )
    }
}
