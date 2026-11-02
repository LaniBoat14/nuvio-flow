package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.*
import com.nuvio.tv.domain.model.ContentType
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class HomeHeroMetadataTest {
    @Test fun `home requests share core data without triggering full enrichment`() = runTest {
        val api = mockk<TmdbApi>()
        val gate = CompletableDeferred<Unit>()
        coEvery { api.getMovieDetails(10, any(), any()) } coAnswers {
            gate.await()
            Response.success(TmdbDetailsResponse(id=10, title="Title", backdropPath="/art.jpg", overview="Synopsis"))
        }
        coEvery { api.getMovieImages(10, any(), any()) } returns Response.success(TmdbImagesResponse())
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler))
        val first = async { service.fetchHomeHero("10", ContentType.MOVIE) }
        val second = async { service.fetchHomeHero("10", ContentType.MOVIE) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("Synopsis", first.await()?.description)
        assertEquals(first.await(), second.await())
        assertEquals(first.await(), service.fetchHomeHero("10", ContentType.MOVIE))
        coVerify(exactly=1) { api.getMovieDetails(10, any(), any()) }
        coVerify(exactly=1) { api.getMovieImages(10, any(), any()) }
        coVerify(exactly=0) { api.getMovieCredits(any(), any(), any()) }
        coVerify(exactly=0) { api.getMovieVideos(any(), any(), any()) }
        coVerify(exactly=0) { api.getMovieAlternativeTitles(any(), any()) }
    }

    @Test fun `slow logo data cannot block the readable hero indefinitely`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(id=10, title="Readable", backdropPath="/art.jpg"))
        coEvery { api.getMovieImages(any(), any(), any()) } coAnswers {
            delay(10_000)
            Response.success(TmdbImagesResponse())
        }
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler))
        val job = async { service.fetchHomeHero("10", ContentType.MOVIE) }
        advanceTimeBy(749); runCurrent(); assertFalse(job.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("Readable", job.await()?.localizedTitle)
        assertNull(job.await()?.logo)
    }

    @Test fun `cancelled shared work can be fetched on the next visit`() = runTest {
        val api = mockk<TmdbApi>()
        var slow = true
        coEvery { api.getMovieDetails(any(), any(), any()) } coAnswers {
            if (slow) delay(10_000)
            Response.success(TmdbDetailsResponse(id=10, title="Retry"))
        }
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler))
        val first = async { service.fetchHomeHero("10", ContentType.MOVIE) }
        runCurrent(); first.cancelAndJoin()
        slow = false
        assertEquals("Retry", service.fetchHomeHero("10", ContentType.MOVIE)?.localizedTitle)
        coVerify(exactly=2) { api.getMovieDetails(any(), any(), any()) }
    }
}
