package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.local.isProfileScopedDataStoreFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktSavedWatchedMoviesTest {
    private val storage = FakeTraktWatchedMoviesStorage()
    private val saved = TraktSavedWatchedMovies(storage)

    @Test
    fun `start-up gets the ids saved for the signed-in account`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1", "tmdb:2"))

        assertEquals(setOf("tt1", "tmdb:2"), saved.restore(profileId = 1, signedIn = true, account = "sam"))
    }

    @Test
    fun `a newer live result replaces the saved ids`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt2"))

        assertEquals(setOf("tt2"), saved.restore(profileId = 1, signedIn = true, account = "sam"))
    }

    @Test
    fun `nothing is used while Trakt is signed out`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))

        assertNull(saved.restore(profileId = 1, signedIn = false, account = null))
    }

    @Test
    fun `signing out of Trakt deletes the saved ids`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))

        assertTrue(saved.onAccountState(profileId = 1, signedIn = false, previousAccount = "sam", account = null))
        assertNull(storage.load(1))
    }

    @Test
    fun `another Trakt account never sees the saved ids`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))

        assertNull(saved.restore(profileId = 1, signedIn = true, account = "someone"))
        assertNull(storage.load(1))
    }

    @Test
    fun `switching Trakt account deletes the saved ids and drops the ones in memory`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))

        assertTrue(saved.onAccountState(profileId = 1, signedIn = true, previousAccount = "sam", account = "someone"))
        assertNull(storage.load(1))
    }

    @Test
    fun `the same account signed in keeps the saved ids`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))

        assertFalse(saved.onAccountState(profileId = 1, signedIn = true, previousAccount = null, account = "sam"))
        assertEquals(setOf("tt1"), storage.load(1)?.movieIds)
    }

    @Test
    fun `each profile keeps its own ids`() = runBlocking {
        saved.save(profileId = 1, account = "sam", movieIds = setOf("tt1"))
        saved.save(profileId = 2, account = "kid", movieIds = setOf("tt9"))

        saved.onAccountState(profileId = 1, signedIn = false, previousAccount = "sam", account = null)

        assertNull(saved.restore(profileId = 1, signedIn = true, account = "sam"))
        assertEquals(setOf("tt9"), saved.restore(profileId = 2, signedIn = true, account = "kid"))
    }

    @Test
    fun `a save that finds Trakt signed out writes nothing`() = runBlocking {
        val written = saved.saveIfSignedIn(profileId = 1, movieIds = setOf("tt1")) { TraktAuthState() }

        assertFalse(written)
        assertNull(storage.load(1))
    }

    @Test
    fun `the same list is not written twice`() = runBlocking {
        val auth = TraktAuthState(accessToken = "a", refreshToken = "r", userSlug = "sam")
        saved.saveIfSignedIn(profileId = 1, movieIds = setOf("tt1")) { auth }
        saved.saveIfSignedIn(profileId = 1, movieIds = setOf("tt1")) { auth }

        assertEquals(1, storage.saves.get())
        assertEquals("sam", storage.load(1)?.account)
    }

    @Test
    fun `the saved ids go with the profile data when the Nuvio account signs out`() {
        assertTrue(isProfileScopedDataStoreFile("trakt_watched_movies.preferences_pb"))
        assertTrue(isProfileScopedDataStoreFile("trakt_watched_movies_p3.preferences_pb"))
    }
}

internal class FakeTraktWatchedMoviesStorage : TraktWatchedMoviesStorage {
    val byProfile = java.util.concurrent.ConcurrentHashMap<Int, SavedTraktWatchedMovies>()
    val saves = java.util.concurrent.atomic.AtomicInteger()
    @Volatile var loadDelayMs = 0L
    @Volatile var failLoads = false

    override suspend fun load(profileId: Int): SavedTraktWatchedMovies? {
        if (loadDelayMs > 0) kotlinx.coroutines.delay(loadDelayMs)
        if (failLoads) throw java.io.IOException("load failed")
        return byProfile[profileId]
    }

    override suspend fun save(profileId: Int, saved: SavedTraktWatchedMovies) {
        saves.incrementAndGet()
        byProfile[profileId] = saved
    }

    override suspend fun remove(profileId: Int) {
        byProfile.remove(profileId)
    }
}
