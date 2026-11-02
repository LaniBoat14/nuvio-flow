package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class HeroPickTest {

    @Test
    fun `hero picks match the full sort on random catalogs`() = runBlocking {
        val random = Random(8776)
        repeat(400) { round ->
            val rowCount = random.nextInt(0, 12)
            val idPool = random.nextInt(5, 120)
            val rows = (0 until rowCount).map { r ->
                val items = (0 until random.nextInt(0, 60)).map { n ->
                    preview(
                        id = "tt${random.nextInt(idPool)}",
                        name = "r$r-n$n",
                        background = if (random.nextInt(3) == 0) null else "bg"
                    )
                }
                row(addonId = "addon${r % 3}", catalogId = "cat$r", items = items)
            }
            val currentOrder = (0 until random.nextInt(0, 9)).map { "tt${random.nextInt(idPool)}" }
            val filters = listOf<(MetaPreview) -> Boolean>({ it.hasHeroArtwork() }, { true })
            filters.forEach { filter ->
                assertEquals(
                    "round $round",
                    fullSortPick(rows, filter, currentOrder),
                    pickHeroItems(rows, filter, currentOrder)
                )
            }
        }
    }

    @Test
    fun `hero picks match the full sort on a large live TV home`() = runBlocking {
        val random = Random(21859)
        val rows = (0 until 29).map { r ->
            row(
                addonId = "cncverse",
                catalogId = "live$r",
                items = (0 until 750).map { n ->
                    preview(id = "live:${random.nextInt(15000)}", name = "r$r-n$n", background = "bg")
                }
            )
        }
        val currentOrder = listOf(rows[0].items[3].id, rows[2].items[10].id, "gone")

        assertEquals(
            fullSortPick(rows, { it.hasHeroArtwork() }, currentOrder),
            pickHeroItems(rows, { it.hasHeroArtwork() }, currentOrder)
        )
    }

    @Test
    fun `sort key equals the hash of the joined text`() {
        listOf("", "tt0111161", "live:fr.tf1", "kitsu:12|x", "éè中").forEach { id ->
            val prefix = "addon|movie|top|"
            assertEquals("$prefix$id".hashCode(), stableHeroSortKey(prefix.hashCode(), id))
        }
    }

    private fun fullSortPick(
        rows: List<CatalogRow>,
        filter: (MetaPreview) -> Boolean,
        currentOrder: List<String>
    ): List<MetaPreview> {
        val totalCatalogs = rows.size.coerceAtLeast(1)
        val baseSlot = 7 / totalCatalogs
        val remainder = 7 % totalCatalogs
        val seen = mutableSetOf<String>()
        val result = mutableListOf<MetaPreview>()
        rows.forEachIndexed { index, row ->
            val slot = baseSlot + if (index < remainder) 1 else 0
            val existing = currentOrder.filter { id -> row.items.any { it.id == id } }
            val byId = row.items.filter(filter).associateBy { it.id }
            val ordered = existing.mapNotNull { byId[it] }
            val new = byId.values.filter { it.id !in existing }.sortedWith(
                compareBy<MetaPreview> { "${row.addonId}|${row.apiType}|${row.catalogId}|${it.id}".hashCode() }
                    .thenBy { it.id }
            )
            val unique = (ordered + new).filter { seen.add(it.id) }
            result += unique.take(slot)
        }
        return result
    }

    private fun row(addonId: String, catalogId: String, items: List<MetaPreview>) = CatalogRow(
        addonId = addonId,
        addonName = addonId,
        addonBaseUrl = "https://example.test",
        catalogId = catalogId,
        catalogName = catalogId,
        type = ContentType.MOVIE,
        items = items
    )

    private fun preview(id: String, name: String, background: String?) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
