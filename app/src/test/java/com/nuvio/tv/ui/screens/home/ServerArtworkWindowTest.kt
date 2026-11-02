package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.mediaserver.FakeMetaRepository
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.data.mediaserver.addonMeta
import com.nuvio.tv.data.mediaserver.serverArtwork
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerArtworkWindowTest {
    private val hero = HeroPreview(title = "Title", logo = null, description = null,
        contentTypeText = null, yearText = null, imdbText = null, genres = StableList(),
        poster = null, backdrop = null, imageUrl = null)

    private fun id(row: Int, card: Int) = "tt${row * 1000 + card}"

    private fun row(index: Int, server: Boolean = true, cards: Int = 50): HeroCarouselRow {
        val baseUrl = if (server) ServerCatalog.baseUrl("box$index") else "https://addon.example/manifest.json"
        val items = (0 until cards).map { card ->
            val meta = MetaPreview(id = id(index, card), type = ContentType.MOVIE, name = "Title", poster = "server",
                posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
                releaseInfo = null, imdbRating = null, genres = emptyList())
            ModernCarouselItem("k$index-$card", "Title", null, "server", hero,
                ModernPayload.Catalog("k$index-$card", meta.id, "movie", baseUrl, "Title", null, "movie"), metaPreview = meta)
        }
        return HeroCarouselRow(key = "row$index", title = "Row $index", globalRowIndex = index, items = StableList(items))
    }

    private fun ids(window: List<Pair<String, List<MetaPreview>>>) = window.flatMap { (_, items) -> items.map { it.id } }

    @Test
    fun cardsCoverTheFirstTenAndAFewAhead() {
        assertEquals(0..13, serverArtworkCards(50, null))
        assertEquals(0..13, serverArtworkCards(50, 0..6))
        assertEquals(0..19, serverArtworkCards(50, 0..15))
        assertEquals(20..33, serverArtworkCards(50, 20..26))
        assertEquals(0..5, serverArtworkCards(6, null))
        assertEquals(44..49, serverArtworkCards(50, 44..49))
        assertTrue(serverArtworkCards(0, null).isEmpty())
    }

    @Test
    fun onlyVisibleServerRowsAndTheOneBelowAreAskedFor() {
        val rows = listOf(row(0, server = false), row(1), row(2), row(3), row(4))
        val window = serverArtworkWindow(rows, 0..1) { null }

        assertEquals(listOf(ServerCatalog.baseUrl("box1"), ServerCatalog.baseUrl("box2")), window.map { it.first })
        assertEquals((0..13).map { id(1, it) } + (0..13).map { id(2, it) }, ids(window))
        assertTrue(serverArtworkWindow(rows, IntRange.EMPTY) { null }.isEmpty())
    }

    @Test
    fun aRowScrolledRightAsksForTheCardsOnScreen() {
        val rows = listOf(row(0))
        val window = serverArtworkWindow(rows, 0..0) { key -> if (key == "row0") 20..26 else null }

        assertEquals((20..33).map { id(0, it) }, ids(window))
    }

    @Test
    fun startUpLooksUpOnlyVisibleTitlesAndScrollingBringsTheNextOnes() {
        val rows = (0 until 56).map { row(it) }
        val repository = FakeMetaRepository(rows.flatMap { r -> r.items.list.map { it.payload } }
            .map { (it as ModernPayload.Catalog).itemId }.associateWith(::addonMeta))
        val artwork = serverArtwork(repository)

        artwork.request(serverArtworkWindow(rows, 0..2) { null }.flatMap { it.second })
        assertEquals(4 * 14, repository.requested.size)

        artwork.request(serverArtworkWindow(rows, 0..2) { null }.flatMap { it.second })
        assertEquals(4 * 14, repository.requested.size)

        artwork.request(serverArtworkWindow(rows, 2..4) { null }.flatMap { it.second })
        assertEquals((4 + 2) * 14, repository.requested.size)

        artwork.request(serverArtworkWindow(rows, 0..2) { key -> if (key == "row0") 10..16 else null }.flatMap { it.second })
        assertEquals((4 + 2) * 14 + 10, repository.requested.size)
        assertEquals((14..23).map { id(0, it) }, repository.requested.takeLast(10))
    }
}
