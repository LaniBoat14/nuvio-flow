package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.repository.MetaCaller
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.MetaRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FakeMetaRepository(
    private val metas: Map<String, Meta> = emptyMap(),
    private val delayMs: Long = 0,
    private val cachedMetas: Map<String, Meta> = emptyMap(),
    private val failing: Set<String> = emptySet()
) : MetaRepository {
    val requested = mutableListOf<String>()
    val callers = mutableListOf<String?>()
    private var active = 0
    var maxActive = 0
        private set

    override fun getMeta(addonBaseUrl: String, type: String, id: String): Flow<NetworkResult<Meta>> =
        getMetaFromAllAddons(type, id)

    override fun getMetaFromAllAddons(type: String, id: String, sourceAddonBaseUrl: String?): Flow<NetworkResult<Meta>> = flow {
        requested += id
        callers += currentCoroutineContext()[MetaCaller]?.name
        active++
        maxActive = maxOf(maxActive, active)
        try {
            emit(NetworkResult.Loading)
            if (delayMs > 0) delay(delayMs)
            val meta = metas[id]
            emit(
                when {
                    id in failing -> NetworkResult.Error("HTTP 429")
                    meta != null -> NetworkResult.Success(meta)
                    else -> NetworkResult.Error("missing", NetworkResult.META_NOT_FOUND_CODE)
                }
            )
        } finally {
            active--
        }
    }

    override fun getMetaFromPrimaryAddon(type: String, id: String): Flow<NetworkResult<Meta>> =
        flowOf(NetworkResult.Error("unused"))

    override fun getCachedMeta(type: String, id: String): Meta? = cachedMetas[id]

    override fun clearCache() = Unit
}

internal fun serverArtwork(
    metaRepository: MetaRepository = FakeMetaRepository(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    nowMs: () -> Long = System::currentTimeMillis
) = ServerArtwork(Lazy { metaRepository }, scope, nowMs)

internal fun addonMeta(id: String, name: String = "Addon $id") =
    meta(id, ServerMediaKind.MOVIE, name).copy(
        poster = "https://addon.example/$id/poster.jpg",
        background = "https://addon.example/$id/background.jpg",
        logo = "https://addon.example/$id/logo.png",
        description = "Addon description",
        imdbRating = 8.5f,
        genres = listOf("Drama")
    )

class ServerArtworkTest {
    private fun item(id: String, type: ContentType = ContentType.MOVIE) = MetaPreview(
        id = id,
        type = type,
        name = "Server $id",
        poster = "https://server.example/$id.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://server.example/$id-bg.jpg",
        logo = null,
        description = "Server description",
        releaseInfo = "1994",
        imdbRating = null,
        genres = emptyList()
    )

    @Test
    fun addonArtworkReplacesServerArtworkAndKeepsTheId() = runTest {
        val artwork = serverArtwork(FakeMetaRepository(mapOf("tt1" to addonMeta("tt1", "Addon Title"))))

        val resolved = artwork.resolve(listOf(item("tt1")), waitMs = 1_000).single()

        assertEquals("tt1", resolved.id)
        assertEquals("https://addon.example/tt1/poster.jpg", resolved.poster)
        assertEquals("https://addon.example/tt1/background.jpg", resolved.background)
        assertEquals("https://addon.example/tt1/logo.png", resolved.logo)
        assertEquals("Addon Title", resolved.name)
        assertEquals("Addon description", resolved.description)
        assertEquals(8.5f, resolved.imdbRating)
        assertEquals(listOf("Drama"), resolved.genres)
        assertEquals("1994", resolved.releaseInfo)
    }

    @Test
    fun missingMetaOrServerIdsKeepServerArtwork() = runTest {
        val repository = FakeMetaRepository(mapOf("tt1" to addonMeta("tt1")))
        val artwork = serverArtwork(repository)
        val server = item(ServerItemRef("c1", "9").encode())
        val missing = item("tt404")
        val collection = item("tt1", ContentType.UNKNOWN).copy(rawType = "collection")

        val resolved = artwork.resolve(listOf(server, missing, collection), waitMs = 1_000)

        assertEquals(listOf(server, missing, collection), resolved)
        assertEquals(listOf("tt404"), repository.requested)
    }

    @Test
    fun metaWithoutArtworkKeepsServerArtwork() = runTest {
        val artwork = serverArtwork(FakeMetaRepository(mapOf("tt1" to meta("tt1", ServerMediaKind.MOVIE, "Bare"))))

        val original = item("tt1")
        assertEquals(original, artwork.resolve(listOf(original), waitMs = 1_000).single())
    }

    @Test
    fun repositoryCacheIsUsedWithoutFetching() = runTest {
        val repository = FakeMetaRepository(cachedMetas = mapOf("tt1" to addonMeta("tt1")))
        val artwork = serverArtwork(repository)

        val cached = artwork.cached(listOf(item("tt1"))).single()

        assertEquals("https://addon.example/tt1/poster.jpg", cached.poster)
        assertTrue(repository.requested.isEmpty())
    }

    @Test
    fun resolvedArtworkIsRememberedAndUnchangedListsAreReturnedAsIs() = runTest {
        val repository = FakeMetaRepository(mapOf("tt1" to addonMeta("tt1")))
        val artwork = serverArtwork(repository)
        artwork.resolve(listOf(item("tt1")), waitMs = 1_000)

        artwork.resolve(listOf(item("tt1"), item("tt404")), waitMs = 1_000)
        artwork.resolve(listOf(item("tt1"), item("tt404")), waitMs = 1_000)

        assertEquals(listOf("tt1", "tt404"), repository.requested)
        val plain = listOf(item(ServerItemRef("c1", "1").encode()))
        assertSame(plain, artwork.cached(plain))
    }

    @Test
    fun fetchesAtMostFourAtATimeAndOnlyTheGivenItems() = runTest {
        val ids = (1..10).map { "tt$it" }
        val repository = FakeMetaRepository(ids.associateWith(::addonMeta), delayMs = 1_000)
        val artwork = serverArtwork(repository, backgroundScope)
        val items = ids.map(::item)

        val early = artwork.resolve(items, waitMs = 500)
        assertEquals(items, early)

        artwork.resolve(items.take(2), waitMs = 500)
        artwork.settle(items, timeoutMs = 10_000)

        assertEquals(4, repository.maxActive)
        assertEquals(ids, repository.requested)
        assertTrue(artwork.cached(items).all { it.poster == "https://addon.example/${it.id}/poster.jpg" })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun slowAddonsDoNotHoldTheRowPastTheWait() = runTest {
        val repository = FakeMetaRepository(mapOf("tt1" to addonMeta("tt1")), delayMs = 5_000)
        val artwork = serverArtwork(repository, backgroundScope)
        val original = item("tt1")

        val started = testScheduler.currentTime
        val first = artwork.resolve(listOf(original), waitMs = 700).single()

        assertEquals(700, testScheduler.currentTime - started)
        assertEquals(original, first)
        artwork.settle(listOf(original), timeoutMs = 10_000)
        assertEquals("https://addon.example/tt1/poster.jpg", artwork.cached(listOf(original)).single().poster)
    }

    @Test
    fun aFailedLookupIsAskedAgainSoonButAGenuineMissWaits() = runTest {
        var now = 1_000_000L
        val repository = FakeMetaRepository(mapOf("tt1" to addonMeta("tt1")), failing = setOf("tt1"))
        val artwork = serverArtwork(repository, nowMs = { now })
        val items = listOf(item("tt1"), item("tt404"))

        artwork.resolve(items, waitMs = 1_000)
        artwork.resolve(items, waitMs = 1_000)
        assertEquals(listOf("tt1", "tt404"), repository.requested)

        now += 61_000L
        artwork.resolve(items, waitMs = 1_000)
        assertEquals(listOf("tt1", "tt404", "tt1"), repository.requested)

        now += 10 * 60_000L
        artwork.resolve(items, waitMs = 1_000)
        assertEquals(listOf("tt1", "tt404", "tt1", "tt1", "tt404"), repository.requested)
    }

    @Test
    fun requestsAreTaggedAsServerArtwork() = runTest {
        val repository = FakeMetaRepository(mapOf("tt1" to addonMeta("tt1")))

        serverArtwork(repository).resolve(listOf(item("tt1")), waitMs = 1_000)

        assertEquals(listOf<String?>("server-art"), repository.callers)
    }

    @Test
    fun aHomeFullOfServerRowsStaysCached() = runTest {
        val ids = (1..1_500).map { "tt$it" }
        val repository = FakeMetaRepository(ids.associateWith(::addonMeta))
        val artwork = serverArtwork(repository)
        val items = ids.map(::item)

        artwork.resolve(items, waitMs = 1_000)
        artwork.resolve(items, waitMs = 1_000)

        assertEquals(ids.size, repository.requested.size)
        assertTrue(artwork.cached(items).all { it.poster == "https://addon.example/${it.id}/poster.jpg" })
    }
    @Test
    fun requestLooksUpOnlyTheGivenTitlesAndSkipsCachedOnes() = runTest {
        val ids = (1..6).map { "tt$it" }
        val repository = FakeMetaRepository(
            ids.associateWith(::addonMeta),
            cachedMetas = mapOf("tt2" to addonMeta("tt2"))
        )
        val artwork = serverArtwork(repository)
        val items = ids.map(::item)

        artwork.request(items.take(3))
        assertEquals(listOf("tt1", "tt3"), repository.requested)

        artwork.request(items.take(3))
        assertEquals(listOf("tt1", "tt3"), repository.requested)

        artwork.request(items.drop(3))
        assertEquals(listOf("tt1", "tt3", "tt4", "tt5", "tt6"), repository.requested)
        assertTrue(artwork.cached(items).all { it.poster == "https://addon.example/${it.id}/poster.jpg" })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun requestRunsFourAtATimeAndTheNewestRequestGoesFirst() = runTest {
        val ids = (1..12).map { "tt$it" }
        val repository = FakeMetaRepository(ids.associateWith(::addonMeta), delayMs = 1_000)
        val artwork = serverArtwork(repository, backgroundScope)
        val items = ids.map(::item)

        artwork.request(items.take(8))
        testScheduler.runCurrent()
        assertEquals(listOf("tt1", "tt2", "tt3", "tt4"), repository.requested)

        artwork.request(items.drop(8))
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()

        assertEquals(4, repository.maxActive)
        assertEquals(ids.take(4) + ids.drop(8) + ids.subList(4, 8), repository.requested)
        assertTrue(artwork.cached(items).all { it.poster == "https://addon.example/${it.id}/poster.jpg" })
    }

    @Test
    fun newArtworkMovesTheUpdateCounter() = runTest {
        val repository = FakeMetaRepository(mapOf("tt1" to addonMeta("tt1")))
        val artwork = serverArtwork(repository)

        artwork.request(listOf(item("tt404")))
        assertEquals(0L, artwork.updates.value)
        artwork.request(listOf(item("tt1")))
        assertEquals(1L, artwork.updates.value)
    }

    @Test
    fun aCardKeepsAddonArtworkWhenARefreshFails() = runTest {
        var now = 1_000_000L
        val metas = mapOf("tt1" to addonMeta("tt1"))
        val first = FakeMetaRepository(metas)
        var repository: MetaRepository = first
        val artwork = ServerArtwork(Lazy { repository }, CoroutineScope(Dispatchers.Unconfined)) { now }
        val items = listOf(item("tt1"))

        artwork.request(items)
        now += 46 * 60_000L
        assertEquals("https://addon.example/tt1/poster.jpg", artwork.cached(items).single().poster)

        repository = FakeMetaRepository(metas, failing = setOf("tt1"))
        artwork.request(items)
        assertEquals("https://addon.example/tt1/poster.jpg", artwork.cached(items).single().poster)
    }
}
