package com.nuvio.tv.ui.screens.search

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import com.nuvio.tv.ui.components.posteroptions.PosterOptionsController
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

private const val BASE_A = "https://a.example.test"
private const val BASE_B = "https://b.example.test"
private const val SERVER = "nuvio-server://box"

/** How one catalog answers a query: its titles, after a pause, or an error. */
private data class Answer(val titles: List<String>, val afterMs: Long = 0, val error: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelSpeedTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val speed = SearchCatalogSpeed(mainDispatcher.scheduler.timeSource)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `typing a word asks each catalog once for it`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Wolf $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "movies", "series")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceUntilIdle()

        assertEquals(listOf("movies" to "wolf", "series" to "wolf"), repo.requests.sortedBy { it.first })
    }

    @Test
    fun `suggestions come from any addon, not only the first`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            if (catalogId == "empty") Answer(emptyList()) else Answer(listOf("Dune: Part Two", "Dune"))
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "empty"), addon(BASE_B, "full")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("dune"))
        advanceUntilIdle()

        assertEquals(listOf("Dune", "Dune: Part Two"), viewModel.uiState.value.suggestions)
    }

    @Test
    fun `a catalog not timed yet is asked straight away`() = runTest {
        val repo = ScriptedCatalogRepository { _, _ -> Answer(listOf("Survivor")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "ai")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("su"))
        advanceTimeBy(400)
        runCurrent()

        assertEquals(listOf("ai" to "su"), repo.requests)
    }

    @Test
    fun `a slow catalog is skipped for a word typed past it`() = runTest {
        speed.recordAnswer(SearchCatalogSpeed.key(BASE_A, "movie", "ai"), 3_000)
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Survivor $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "ai", "exact")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("su"))
        // The live search has run; the next letter comes before the slow catalog's longer pause.
        advanceTimeBy(700)
        runCurrent()
        viewModel.onEvent(SearchEvent.QueryChanged("sur"))
        advanceUntilIdle()

        assertEquals(listOf("exact" to "su", "exact" to "sur", "ai" to "sur"), repo.requests)
    }

    @Test
    fun `submitting asks a slow catalog at once`() = runTest {
        speed.recordAnswer(SearchCatalogSpeed.key(BASE_A, "movie", "ai"), 3_000)
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Survivor $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "ai", "exact")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("survivor"))
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("exact" to "survivor"), repo.requests)

        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()

        assertEquals(listOf("exact" to "survivor", "ai" to "survivor"), repo.requests)
    }

    @Test
    fun `results are ready to move into as soon as the top row is in`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            if (catalogId == "fast") Answer(listOf("Dune"), afterMs = 200) else Answer(listOf("Dune AI"), afterMs = 9_000)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "fast", "slow")), repo)

        submit(viewModel, "dune")
        advanceTimeBy(400)
        runCurrent()

        assertEquals("dune", viewModel.uiState.value.focusReadyQuery)
        assertTrue(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `a row above that is still out holds the move in for the grace period`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            if (catalogId == "fast") Answer(listOf("Dune"), afterMs = 200) else Answer(listOf("Dune AI"), afterMs = 9_000)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "slow", "fast")), repo)

        submit(viewModel, "dune")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("", viewModel.uiState.value.focusReadyQuery)

        advanceTimeBy(700)
        runCurrent()
        assertEquals("dune", viewModel.uiState.value.focusReadyQuery)
    }

    @Test
    fun `a row above that answers empty does not hold the move in`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            when (catalogId) {
                "dummy" -> Answer(emptyList(), afterMs = 200)
                "fast" -> Answer(listOf("Dune"), afterMs = 400)
                else -> Answer(listOf("Dune AI"), afterMs = 9_000)
            }
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "dummy", "fast"), addon(BASE_B, "slow")), repo)

        submit(viewModel, "dune")
        advanceTimeBy(600)
        runCurrent()

        assertEquals("dune", viewModel.uiState.value.focusReadyQuery)
    }

    @Test
    fun `nothing is ready to move into when every answer is empty`() = runTest {
        val repo = ScriptedCatalogRepository { _, _ -> Answer(emptyList(), afterMs = 100) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "one", "two")), repo)

        submit(viewModel, "zzzz")
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.focusReadyQuery)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `typing again takes back the ready mark`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Dune $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "one")), repo)

        submit(viewModel, "dune")
        advanceUntilIdle()
        assertEquals("dune", viewModel.uiState.value.focusReadyQuery)

        viewModel.onEvent(SearchEvent.QueryChanged("dunes"))
        runCurrent()
        assertEquals("", viewModel.uiState.value.focusReadyQuery)
    }

    @Test
    fun `submitting a word live search already finished is ready at once`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Dune $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "one")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("dune"))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals("dune", viewModel.uiState.value.focusReadyQuery)
        assertEquals(1, repo.requests.size)
    }

    @Test
    fun `a catalog that never answers stops holding the loading state`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            if (catalogId == "fast") Answer(listOf("Dune"), afterMs = 200) else Answer(listOf("Dune late"), afterMs = 30_000)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "fast", "dead")), repo)

        submit(viewModel, "dune")
        advanceTimeBy(12_100)
        runCurrent()
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(listOf("fast"), viewModel.uiState.value.catalogRows.map { it.catalogId })

        // An answer after the cap still lands.
        advanceUntilIdle()
        assertEquals(listOf("fast", "dead"), viewModel.uiState.value.catalogRows.map { it.catalogId })
    }

    @Test
    fun `a catalog that fails the new word drops the old word's row`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, query ->
            if (catalogId == "flaky" && query == "loan survivor") Answer(emptyList(), error = true)
            else Answer(listOf("$catalogId $query"))
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "steady", "flaky")), repo)

        submit(viewModel, "survivor")
        advanceUntilIdle()
        assertEquals(listOf("steady", "flaky"), viewModel.uiState.value.catalogRows.map { it.catalogId })

        submit(viewModel, "loan survivor")
        advanceUntilIdle()

        val rows = viewModel.uiState.value.catalogRows
        assertEquals(listOf("steady"), rows.map { it.catalogId })
        assertEquals("steady loan survivor", rows.single().items.single().name)
    }

    @Test
    fun `a row still showing the previous word is marked as updating until it answers`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, query ->
            if (catalogId == "slow" && query == "loan survivor") Answer(listOf("$catalogId $query"), afterMs = 3_000)
            else Answer(listOf("$catalogId $query"), afterMs = 100)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "fast", "slow")), repo)
        submit(viewModel, "survivor")
        advanceUntilIdle()
        val slowKey = viewModel.uiState.value.catalogRows.single { it.catalogId == "slow" }.let {
            com.nuvio.tv.domain.model.catalogRowStableKey(it.addonId, it.addonBaseUrl, it.apiType, it.catalogId)
        }

        submit(viewModel, "loan survivor")
        // Quick answers are not marked: nothing is out of date for long enough yet.
        advanceTimeBy(300)
        runCurrent()
        assertEquals(emptySet<String>(), viewModel.uiState.value.updatingRowKeys)

        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf(slowKey), viewModel.uiState.value.updatingRowKeys)

        advanceUntilIdle()
        assertEquals(emptySet<String>(), viewModel.uiState.value.updatingRowKeys)
        assertEquals("slow loan survivor", viewModel.uiState.value.catalogRows.single { it.catalogId == "slow" }.items.single().name)
    }

    @Test
    fun `focus targets this word's own row, not one left from the previous word`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, query ->
            if (catalogId == "slow" && query != "survivor") Answer(listOf("$catalogId $query"), afterMs = 9_000)
            else Answer(listOf("$catalogId $query"), afterMs = 200)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "slow", "fast")), repo)
        submit(viewModel, "survivor")
        advanceUntilIdle()
        val fastKey = rowKey(viewModel, "fast")

        submit(viewModel, "loan survivor")
        advanceTimeBy(1_700)
        runCurrent()

        assertEquals("loan survivor", viewModel.uiState.value.focusReadyQuery)
        assertEquals(fastKey, viewModel.uiState.value.focusTargetRowKey)
    }

    @Test
    fun `the updating mark carries into the next typing run`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, query ->
            if (catalogId == "slow" && query != "survivor") Answer(listOf("$catalogId $query"), afterMs = 3_000)
            else Answer(listOf("$catalogId $query"), afterMs = 100)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "fast", "slow")), repo)
        submit(viewModel, "survivor")
        advanceUntilIdle()
        val slowKey = rowKey(viewModel, "slow")
        submit(viewModel, "loan survivor")
        advanceTimeBy(800)
        runCurrent()
        assertEquals(setOf(slowKey), viewModel.uiState.value.updatingRowKeys)

        viewModel.onEvent(SearchEvent.QueryChanged("loan survivors"))
        // The live run has started and the fast catalog has answered; the slow row is still old.
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf(slowKey), viewModel.uiState.value.updatingRowKeys)
    }

    @Test
    fun `done pressed while the live search is still running is remembered`() = runTest {
        val history = mockk<SearchHistoryDataStore>(relaxed = true)
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Dune $query"), afterMs = 2_000) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "one")), repo, history)

        viewModel.onEvent(SearchEvent.QueryChanged("dune"))
        advanceTimeBy(400)
        runCurrent()
        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        coVerify(exactly = 1) { history.saveRecentSearch("dune", 8) }
        assertEquals(1, repo.requests.size)
    }

    @Test
    fun `with nothing to show at the cap the search keeps loading`() = runTest {
        val repo = ScriptedCatalogRepository { _, _ -> Answer(listOf("Dune"), afterMs = 30_000) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "late")), repo)

        submit(viewModel, "dune")
        advanceTimeBy(12_100)
        runCurrent()
        assertTrue(viewModel.uiState.value.isSearching)

        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isSearching)
        assertEquals(listOf("late"), viewModel.uiState.value.catalogRows.map { it.catalogId })
    }

    @Test
    fun `catalogs crowded onto one server are not timed`() = runTest {
        val ids = listOf("c1", "c2", "c3", "c4", "c5", "c6")
        val repo = ScriptedCatalogRepository { _, _ -> Answer(listOf("Dune"), afterMs = 3_000) }
        val viewModel = newViewModel(listOf(addon(BASE_A, *ids.toTypedArray()), addon(BASE_B, "alone")), repo)

        submit(viewModel, "dune")
        advanceUntilIdle()

        ids.forEach { id -> assertFalse(id, speed.isSlow(SearchCatalogSpeed.key(BASE_A, "movie", id))) }
        assertTrue(speed.isSlow(SearchCatalogSpeed.key(BASE_B, "movie", "alone")))
    }

    @Test
    fun `suggestions that no longer match are cleared at the cap even if a catalog never answers`() = runTest {
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            if (catalogId == "dead") Answer(listOf("Wolf"), afterMs = 60_000) else Answer(listOf("Wolf"), afterMs = 100)
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, "fast", "dead")), repo)
        viewModel.onEvent(SearchEvent.QueryChanged("wolf"))
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf("Wolf"), viewModel.uiState.value.suggestions)

        viewModel.onEvent(SearchEvent.QueryChanged("wolfz"))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("Wolf"), viewModel.uiState.value.suggestions)

        advanceTimeBy(8_000)
        runCurrent()
        assertEquals(emptyList<String>(), viewModel.uiState.value.suggestions)
    }

    private fun rowKey(viewModel: SearchViewModel, catalogId: String): String =
        viewModel.uiState.value.catalogRows.single { it.catalogId == catalogId }.let {
            com.nuvio.tv.domain.model.catalogRowStableKey(it.addonId, it.addonBaseUrl, it.apiType, it.catalogId)
        }

    @Test
    fun `a catalog listed twice is asked once`() = runTest {
        val repo = ScriptedCatalogRepository { _, _ -> Answer(listOf("Dune")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "top", "top")), repo)

        submit(viewModel, "dune")
        advanceUntilIdle()

        assertEquals(1, repo.requests.size)
        assertEquals(1, viewModel.uiState.value.catalogRows.size)
    }

    /** Answers 100 ms apart used to restart the refresh wait each time and hold every row back. */
    @Test
    fun `rows keep appearing while later answers are still coming in`() = runTest {
        val ids = listOf("c1", "c2", "c3", "c4", "c5", "c6")
        val repo = ScriptedCatalogRepository { catalogId, _ ->
            Answer(listOf(catalogId), afterMs = 100L * (ids.indexOf(catalogId) + 1))
        }
        val viewModel = newViewModel(listOf(addon(BASE_A, *ids.toTypedArray())), repo)

        submit(viewModel, "dune")
        advanceTimeBy(380)
        runCurrent()

        val shown = viewModel.uiState.value.catalogRows
            .filter { it.items.firstOrNull()?.id?.startsWith("__placeholder_") != true }
            .map { it.catalogId }
        assertTrue("rows shown at 380 ms: $shown", shown.containsAll(listOf("c1", "c2")))
    }

    @Test
    fun `a media server is not asked about a two letter word while typing`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Up $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "movies"), addon(SERVER, "library")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("up"))
        advanceUntilIdle()

        assertEquals(listOf("movies" to "up"), repo.requests)
        assertTrue(viewModel.uiState.value.catalogRows.none { it.addonBaseUrl == SERVER })
    }

    @Test
    fun `submitting a two letter word asks the media server too`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Up $query"), afterMs = 1_000) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "movies"), addon(SERVER, "library")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("up"))
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("movies" to "up"), repo.requests)

        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertTrue("library" to "up" in repo.requests)
        assertTrue(viewModel.uiState.value.catalogRows.any { it.addonBaseUrl == SERVER && it.items.isNotEmpty() })
    }

    @Test
    fun `moving into the results asks the media server about a two letter word`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Up $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "movies"), addon(SERVER, "library")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("up"))
        advanceUntilIdle()
        viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
        advanceUntilIdle()

        assertEquals(listOf("movies" to "up", "library" to "up"), repo.requests)
        assertEquals(
            listOf(BASE_A, SERVER),
            viewModel.uiState.value.catalogRows.map { it.addonBaseUrl }
        )

        viewModel.onEvent(SearchEvent.SubmitSearch)
        advanceUntilIdle()

        assertEquals(2, repo.requests.size)
    }

    @Test
    fun `with only a media server to search a two letter word still asks it`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Up $query")) }
        val viewModel = newViewModel(listOf(addon(SERVER, "library")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("up"))
        advanceUntilIdle()

        assertEquals(listOf("library" to "up"), repo.requests)
    }

    @Test
    fun `a media server waits for a longer pause while typing`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Toy $query")) }
        val viewModel = newViewModel(listOf(addon(BASE_A, "movies"), addon(SERVER, "library")), repo)

        viewModel.onEvent(SearchEvent.QueryChanged("toy"))
        advanceTimeBy(500)
        runCurrent()
        viewModel.onEvent(SearchEvent.QueryChanged("toy s"))
        advanceUntilIdle()

        assertEquals(listOf("movies" to "toy", "movies" to "toy s", "library" to "toy s"), repo.requests)
    }

    @Test
    fun `submitting asks the media server without the typing pause`() = runTest {
        val repo = ScriptedCatalogRepository { _, query -> Answer(listOf("Toy $query")) }
        val viewModel = newViewModel(listOf(addon(SERVER, "library")), repo)

        submit(viewModel, "toy story")

        assertEquals(listOf("library" to "toy story"), repo.requests)
    }

    private fun TestScope.submit(viewModel: SearchViewModel, query: String) {
        viewModel.onEvent(SearchEvent.QueryChanged(query))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        runCurrent()
    }

    private fun newViewModel(
        addons: List<Addon>,
        catalogRepository: CatalogRepository,
        history: SearchHistoryDataStore = mockk(relaxed = true)
    ): SearchViewModel {
        val layoutPreferences = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferences.discoverLocation } returns flowOf(com.nuvio.tv.domain.model.DiscoverLocation.OFF)
        every { layoutPreferences.posterCardWidthDp } returns flowOf(126)
        every { layoutPreferences.posterLabelsEnabled } returns flowOf(true)
        every { layoutPreferences.catalogAddonNameEnabled } returns flowOf(true)
        every { layoutPreferences.posterCardHeightDp } returns flowOf(189)
        every { layoutPreferences.posterCardCornerRadiusDp } returns flowOf(12)
        every { layoutPreferences.catalogTypeSuffixEnabled } returns flowOf(true)
        every { layoutPreferences.hideUnreleasedContent } returns flowOf(false)

        every { history.recentSearches } returns flowOf(emptyList())

        val watchProgress = mockk<WatchProgressRepository>()
        every { watchProgress.observeWatchedMovieIds() } returns flowOf(emptySet())

        val watchedSeries = mockk<WatchedSeriesStateHolder>()
        every { watchedSeries.fullyWatchedSeriesIds } returns MutableStateFlow(emptySet())

        return SearchViewModel(
            addonRepository = FixedAddonRepository(addons),
            catalogRepository = catalogRepository,
            metaRepository = mockk(relaxed = true),
            discoverSelectionDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = layoutPreferences,
            searchHistoryDataStore = history,
            watchProgressRepository = watchProgress,
            watchedSeriesStateHolder = watchedSeries,
            posterOptions = mockk<PosterOptionsController>(relaxed = true),
            serverCatalog = mockk { every { searchAddons } returns flowOf(emptyList()) },
            catalogSpeed = speed,
            context = mockk<Context>(relaxed = true)
        )
    }

    private class FixedAddonRepository(private val addons: List<Addon>) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(addons)
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> = error("unused")
        override suspend fun addAddon(url: String) = error("unused")
        override suspend fun removeAddon(url: String) = error("unused")
        override suspend fun setAddonOrder(urls: List<String>) = error("unused")
        override suspend fun setAddonEnabled(url: String, enabled: Boolean) = error("unused")
    }

    private class ScriptedCatalogRepository(
        private val script: (catalogId: String, query: String) -> Answer
    ) : CatalogRepository {
        /** Catalog id and query of every request, in the order they went out. */
        val requests = mutableListOf<Pair<String, String>>()

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen
        ): Flow<NetworkResult<CatalogRow>> = flow {
            val query = extraArgs["search"].orEmpty()
            requests += catalogId to query
            emit(NetworkResult.Loading)
            val answer = script(catalogId, query)
            delay(answer.afterMs)
            if (answer.error) {
                emit(NetworkResult.Error("down"))
            } else {
                emit(
                    NetworkResult.Success(
                        CatalogRow(
                            addonId = addonId,
                            addonName = addonName,
                            addonBaseUrl = addonBaseUrl,
                            catalogId = catalogId,
                            catalogName = catalogName,
                            type = ContentType.MOVIE,
                            items = answer.titles.map { title ->
                                MetaPreview(
                                    id = "id_${title.hashCode()}",
                                    type = ContentType.MOVIE,
                                    name = title,
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
                        )
                    )
                )
            }
        }

        override suspend fun clearCaches() = Unit
    }

    private fun addon(baseUrl: String, vararg catalogIds: String): Addon = Addon(
        id = "addon_$baseUrl",
        name = baseUrl,
        version = "1",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogIds.map { id ->
            CatalogDescriptor(
                type = ContentType.MOVIE,
                id = id,
                name = id,
                extra = listOf(CatalogExtra(name = "search"))
            )
        },
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )
}

class SearchCatalogSpeedTest {

    private var nowMs = 0L
    private val speed = SearchCatalogSpeed(object : kotlin.time.TimeSource {
        override fun markNow(): kotlin.time.TimeMark = object : kotlin.time.TimeMark {
            val at = nowMs
            override fun elapsedNow() = (nowMs - at).milliseconds
        }
    })

    @Test
    fun `a catalog it has not timed counts as fast`() {
        assertFalse(speed.isSlow("a"))
    }

    @Test
    fun `cache-quick answers say nothing about a catalog`() {
        speed.recordAnswer("a", 3_000)
        repeat(5) { speed.recordAnswer("a", 2) }
        assertTrue(speed.isSlow("a"))
    }

    @Test
    fun `a dropped request that waited long marks the catalog slow`() {
        speed.recordUnanswered("a", 1_000)
        assertFalse(speed.isSlow("a"))
        speed.recordUnanswered("a", 2_000)
        assertTrue(speed.isSlow("a"))
    }

    @Test
    fun `a slow catalog that speeds up counts as fast again`() {
        speed.recordAnswer("a", 3_000)
        repeat(4) { speed.recordAnswer("a", 300) }
        assertFalse(speed.isSlow("a"))
    }

    @Test
    fun `the time since a mark is measured on the given clock`() {
        val mark = speed.markNow()
        nowMs += 1_234
        assertEquals(1_234L, mark.elapsedNow().inWholeMilliseconds)
    }
}
