package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.PlayerSessionTracker
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.MetaDto
import com.nuvio.tv.data.remote.dto.MetaResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MetaRepositoryPlayerHoldTest {

    private val requested = Collections.synchronizedList(mutableListOf<String>())

    private suspend fun <T> withPlayerOpen(block: suspend (PlayerSessionTracker.Session) -> T): T {
        val session = PlayerSessionTracker.open()
        try {
            return withContext(Dispatchers.Default) { block(session) }
        } finally {
            session.close()
        }
    }

    @Test
    fun `a Continue Watching lookup waits until the player closes`() = runTest {
        val repository = newRepository()

        val result = withPlayerOpen { session ->
            val cw = async {
                withContext(MetaCaller("cw")) { repository.getMetaFromAllAddons("movie", "tt0000001").last() }
            }
            Thread.sleep(200)
            assertEquals(0, requested.size)
            session.close()
            withTimeout(5_000) { cw.await() }
        }

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, requested.size)
    }

    @Test
    fun `the player joining a held lookup gets it at once`() = runTest {
        val repository = newRepository()

        withPlayerOpen {
            val cw = async {
                withContext(MetaCaller("cw")) { repository.getMetaFromAllAddons("movie", "tt0000001").last() }
            }
            Thread.sleep(200)
            assertEquals(0, requested.size)

            val own = withTimeout(5_000) {
                repository.getMetaFromAllAddons("movie", "tt0000001").first { it !is NetworkResult.Loading }
            }
            assertTrue(own is NetworkResult.Success)
            assertTrue(withTimeout(5_000) { cw.await() } is NetworkResult.Success)
        }

        assertEquals(1, requested.size)
    }

    @Test
    fun `a held primary lookup is released when another caller joins`() = runTest {
        val repository = newRepository()

        withPlayerOpen {
            val badges = async {
                withContext(MetaCaller("cw-badges")) {
                    repository.getMetaFromPrimaryAddon("series", "tt0000002").first { it !is NetworkResult.Loading }
                }
            }
            Thread.sleep(200)
            assertEquals(0, requested.size)

            val own = withTimeout(5_000) {
                repository.getMetaFromPrimaryAddon("series", "tt0000002").first { it !is NetworkResult.Loading }
            }
            assertTrue(own is NetworkResult.Success)
            assertTrue(withTimeout(5_000) { badges.await() } is NetworkResult.Success)
        }

        assertEquals(1, requested.size)
    }

    @Test
    fun `other callers are never held`() = runTest {
        val repository = newRepository()

        val results = withPlayerOpen {
            withTimeout(5_000) {
                listOf(
                    repository.getMetaFromAllAddons("movie", "tt0000003").last(),
                    withContext(MetaCaller("home-details")) {
                        repository.getMetaFromPrimaryAddon("movie", "tt0000004").first { it !is NetworkResult.Loading }
                    }
                )
            }
        }

        assertTrue(results.all { it is NetworkResult.Success })
        assertEquals(2, requested.size)
    }

    private fun newRepository(): MetaRepositoryImpl {
        val api = mockk<AddonApi>().also { api ->
            coEvery { api.getMeta(any()) } coAnswers {
                val url = firstArg<String>()
                requested += url
                val id = url.substringAfterLast('/').substringBefore(".json")
                Response.success(MetaResponseDto(meta = MetaDto(id = id, type = "movie", name = "Found $id")))
            }
        }
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addon = Addon(
            id = "test.addon.one",
            name = "Test Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://calm.example",
            catalogs = emptyList(),
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            rawTypes = listOf("movie", "series"),
            resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = null)),
            idPrefixes = listOf("tt")
        )
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(listOf(addon))
        }
        return MetaRepositoryImpl(
            context = context,
            api = api,
            addonRepository = addonRepository,
            healthStore = mockk(relaxed = true),
            serverCatalog = mockk(relaxed = true)
        )
    }
}
