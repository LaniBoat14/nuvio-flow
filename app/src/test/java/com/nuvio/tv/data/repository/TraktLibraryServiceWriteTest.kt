package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListMutationCountDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktLibraryServiceWriteTest {
    private val movie = LibraryEntryInput("tt1", "movie", "Movie", traktId = 1, imdbId = "tt1", tmdbId = 1)
    private val api = mockk<TraktApi>()
    private val auth = mockk<TraktAuthService>()
    private val profiles = mockk<ProfileManager>()
    private val watchlistCalls = AtomicInteger()
    @Volatile private var watchlistHasMovie = false
    @Volatile private var blockedWatchlist: CompletableDeferred<Unit>? = null
    private val blockedEntered = CompletableDeferred<Unit>()

    init {
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        coEvery { auth.executeAuthorizedRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("Bearer token")
        }
        coEvery { api.getUserWatchlist(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (thirdArg<String>() == "movies") {
                watchlistCalls.incrementAndGet()
                val serverHasMovie = watchlistHasMovie
                blockedWatchlist?.let { gate ->
                    blockedWatchlist = null
                    blockedEntered.complete(Unit)
                    gate.await()
                }
                Response.success(if (serverHasMovie) listOf(movieItem()) else emptyList())
            } else {
                Response.success(emptyList())
            }
        }
        coEvery { api.getUserLists(any(), any()) } returns Response.success(emptyList<TraktListSummaryDto>())
        coEvery { api.addToWatchlist(any(), any()) } coAnswers {
            watchlistHasMovie = true
            Response.success(TraktListItemsMutationResponseDto(added = TraktListMutationCountDto(movies = 1)))
        }
    }

    @Test
    fun `adding to the watchlist does not wait for a running refresh and survives its stale result`() = runBlocking {
        val service = TraktLibraryService(mockk<Context>(relaxed = true), api, auth, profiles)
        eventually { service.currentItemsOrNull() != null }
        assertEquals(mapOf(TraktLibraryService.WATCHLIST_KEY to false), service.getDefaultMembershipSnapshot(movie).listMembership)

        val release = CompletableDeferred<Unit>()
        blockedWatchlist = release
        val refresh = async(Dispatchers.Default) { service.refreshNow() }
        withTimeout(5_000) { blockedEntered.await() }

        withTimeout(5_000) {
            service.applyMembershipChanges(movie, ListMembershipChanges(mapOf(TraktLibraryService.WATCHLIST_KEY to true)))
        }
        coVerify(exactly = 1) { api.addToWatchlist(any(), any()) }
        assertTrue(refresh.isActive)
        assertEquals(mapOf(TraktLibraryService.WATCHLIST_KEY to true), service.getDefaultMembershipSnapshot(movie).listMembership)

        release.complete(Unit)
        withTimeout(5_000) { refresh.await() }
        assertTrue(service.currentItems().any { it.imdbId == "tt1" })
        eventually { watchlistCalls.get() == 3 }
        assertTrue(service.currentItems().any { it.imdbId == "tt1" && TraktLibraryService.WATCHLIST_KEY in it.listKeys })
    }

    @Test
    fun `before the first load only the watchlist is read and the change is sent`() = runBlocking {
        coEvery { api.getUserLists(any(), any()) } returns Response.error(500, "".toResponseBody(null))
        coEvery { api.removeFromWatchlist(any(), any()) } returns Response.success(TraktListItemsMutationResponseDto())
        watchlistHasMovie = true
        val service = TraktLibraryService(mockk<Context>(relaxed = true), api, auth, profiles)
        eventually { watchlistCalls.get() >= 1 }
        delay(200)
        assertEquals(mapOf(TraktLibraryService.WATCHLIST_KEY to true), service.getDefaultMembershipSnapshot(movie).listMembership)
        coVerify(exactly = 1) { api.getUserLists(any(), any()) }

        withTimeout(5_000) {
            service.applyMembershipChanges(movie, ListMembershipChanges(mapOf(TraktLibraryService.WATCHLIST_KEY to false)))
        }
        coVerify(exactly = 1) { api.removeFromWatchlist(any(), any()) }
        coVerify(exactly = 0) { api.addToWatchlist(any(), any()) }
    }

    private fun movieItem() = TraktListItemDto(
        rank = 1,
        listedAt = "2026-10-01T00:00:00.000Z",
        type = "movie",
        movie = TraktMovieDto(title = "Movie", year = 2020, ids = TraktIdsDto(trakt = 1, imdb = "tt1", tmdb = 1))
    )

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
