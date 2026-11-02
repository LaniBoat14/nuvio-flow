package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * A Shield with six media servers asked two addons for about 1,800 titles in its first minute and
 * got 2,000 429s back, because every lookup kept asking a host that had just said "slow down".
 */
class MetaRepositoryRateLimitTest {

    private val requested = Collections.synchronizedList(mutableListOf<String>())

    @Test
    fun `a 429 pauses that host for the following lookups`() = runTest {
        val repository = newRepository(
            apiAnswering { url -> if (url.startsWith("https://busy.example")) tooMany("30") else found(url) },
            addon("first", "https://busy.example/one"),
            addon("second", "https://calm.example")
        )

        assertTrue(repository.getMetaFromAllAddons("movie", "tt0000001").last() is NetworkResult.Success)
        assertTrue(repository.getMetaFromAllAddons("movie", "tt0000002").last() is NetworkResult.Success)

        assertEquals(1, requested.count { it.startsWith("https://busy.example") })
        assertEquals(2, requested.count { it.startsWith("https://calm.example") })
    }

    @Test
    fun `a second addon on the rate limited host is skipped in the same lookup`() = runTest {
        val repository = newRepository(
            apiAnswering { tooMany(null) },
            addon("one", "https://aio.example/config-one"),
            addon("two", "https://aio.example/config-two")
        )

        val result = repository.getMetaFromAllAddons("movie", "tt0000001").last()

        assertEquals(1, requested.size)
        assertNull("a rate limited lookup stays retryable", (result as NetworkResult.Error).code)
    }

    @Test
    fun `a paused host is asked again once the pause is over`() = runTest {
        var limited = true
        val repository = newRepository(
            apiAnswering { url -> if (limited) tooMany("1") else found(url) },
            addon("one", "https://busy.example")
        )
        repository.getMetaFromAllAddons("movie", "tt0000001").last()
        repository.getMetaFromAllAddons("movie", "tt0000002").last()
        assertEquals(1, requested.size)

        limited = false
        repository.rateLimits.clear()

        assertTrue(repository.getMetaFromAllAddons("movie", "tt0000002").last() is NetworkResult.Success)
        assertEquals(2, requested.size)
    }

    @Test
    fun `a caller joining a failed lookup does not run it again`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = mockk<AddonApi>()
        coEvery { api.getMeta(any()) } coAnswers {
            requested += firstArg<String>()
            entered.complete(Unit)
            release.await()
            Response.error(500, "".toResponseBody("application/json".toMediaType()))
        }
        val repository = newRepository(api, addon("one", "https://flaky.example"))

        val results = withContext(Dispatchers.Default) {
            val first = async { repository.getMetaFromAllAddons("movie", "tt0000001").last() }
            entered.await()
            val second = async {
                repository.getMetaFromAllAddons("movie", "tt0000001").first { it !is NetworkResult.Loading }
            }
            Thread.sleep(100)
            release.complete(Unit)
            listOf(first.await(), second.await())
        }

        assertEquals(1, requested.size)
        assertTrue(results.all { it is NetworkResult.Error && it.code == null })
    }

    @Test
    fun `the requesting feature is counted without changing the result`() = runTest {
        val repository = newRepository(apiAnswering { url -> found(url) }, addon("one", "https://calm.example"))

        val result = withContext(MetaCaller("server-art")) {
            repository.getMetaFromAllAddons("movie", "tt0000001").last()
        }

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, requested.size)
    }

    private fun found(url: String): Response<MetaResponseDto> {
        val id = url.substringAfterLast('/').substringBefore(".json")
        return Response.success(MetaResponseDto(meta = MetaDto(id = id, type = "movie", name = "Found $id")))
    }

    private fun tooMany(retryAfter: String?): Response<MetaResponseDto> {
        val raw = okhttp3.Response.Builder()
            .code(429)
            .message("Too Many Requests")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://busy.example/").build())
            .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
            .build()
        return Response.error("".toResponseBody("application/json".toMediaType()), raw)
    }

    private fun apiAnswering(answer: (String) -> Response<MetaResponseDto>): AddonApi =
        mockk<AddonApi>().also { api ->
            coEvery { api.getMeta(any()) } coAnswers {
                val url = firstArg<String>()
                requested += url
                answer(url)
            }
        }

    private fun addon(key: String, baseUrl: String) = Addon(
        id = "test.addon.$key",
        name = "Test Addon $key",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        rawTypes = listOf("movie", "series"),
        resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = null)),
        idPrefixes = listOf("tt")
    )

    private fun newRepository(api: AddonApi, vararg addons: Addon): MetaRepositoryImpl {
        val context = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Episode"
            every { getString(any(), *anyVararg()) } returns "No supported addon"
        }
        val addonRepository = mockk<AddonRepository>(relaxed = true) {
            every { getInstalledAddons() } returns flowOf(addons.toList())
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
