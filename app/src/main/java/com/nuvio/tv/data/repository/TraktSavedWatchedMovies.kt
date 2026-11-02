package com.nuvio.tv.data.repository

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TraktAuthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SavedTraktWatchedMovies(
    val account: String?,
    val movieIds: Set<String>
)

interface TraktWatchedMoviesStorage {
    suspend fun load(profileId: Int): SavedTraktWatchedMovies?
    suspend fun save(profileId: Int, saved: SavedTraktWatchedMovies)
    suspend fun remove(profileId: Int)
}

@Singleton
class AndroidTraktWatchedMoviesStorage @Inject constructor(
    private val factory: ProfileDataStoreFactory
) : TraktWatchedMoviesStorage {
    override suspend fun load(profileId: Int): SavedTraktWatchedMovies? {
        val preferences = factory.get(profileId, FEATURE_NAME).data.first()
        val ids = preferences[MOVIE_IDS_KEY] ?: return null
        return SavedTraktWatchedMovies(account = preferences[ACCOUNT_KEY], movieIds = ids)
    }

    override suspend fun save(profileId: Int, saved: SavedTraktWatchedMovies) {
        factory.get(profileId, FEATURE_NAME).edit { preferences ->
            preferences[MOVIE_IDS_KEY] = saved.movieIds
            if (saved.account == null) preferences.remove(ACCOUNT_KEY) else preferences[ACCOUNT_KEY] = saved.account
        }
    }

    override suspend fun remove(profileId: Int) {
        factory.get(profileId, FEATURE_NAME).edit { preferences ->
            preferences.remove(MOVIE_IDS_KEY)
            preferences.remove(ACCOUNT_KEY)
        }
    }

    private companion object {
        const val FEATURE_NAME = "trakt_watched_movies"
        val MOVIE_IDS_KEY = stringSetPreferencesKey("movie_ids")
        val ACCOUNT_KEY = stringPreferencesKey("account")
    }
}

/**
 * The last watched-movie ids Trakt returned, kept per profile so ticks and Home can use them at start-up
 * before Trakt answers. They belong to one Trakt account and go when it signs out or changes.
 */
@Singleton
class TraktSavedWatchedMovies @Inject constructor(
    private val storage: TraktWatchedMoviesStorage
) {
    private val mutex = Mutex()

    suspend fun restore(profileId: Int, signedIn: Boolean, account: String?): Set<String>? = mutex.withLock {
        if (!signedIn) return@withLock null
        val saved = storage.load(profileId) ?: return@withLock null
        if (isOtherAccount(saved.account, account)) {
            storage.remove(profileId)
            return@withLock null
        }
        saved.movieIds
    }

    suspend fun save(profileId: Int, account: String?, movieIds: Set<String>) = mutex.withLock {
        writeIfChanged(profileId, SavedTraktWatchedMovies(account, movieIds))
    }

    /** Checks the sign-in under the same lock as [onAccountState], so a sign-out cannot slip in before the write. */
    suspend fun saveIfSignedIn(
        profileId: Int,
        movieIds: Set<String>,
        currentAuth: suspend () -> TraktAuthState
    ): Boolean = mutex.withLock {
        val auth = currentAuth()
        if (!auth.isAuthenticated) return@withLock false
        writeIfChanged(profileId, SavedTraktWatchedMovies(auth.account, movieIds))
        true
    }

    /** Returns true when the ids held in memory no longer belong to the signed-in account. */
    suspend fun onAccountState(
        profileId: Int,
        signedIn: Boolean,
        previousAccount: String?,
        account: String?
    ): Boolean = mutex.withLock {
        val saved = storage.load(profileId)
        val stale = leftAccount(signedIn, previousAccount, account) ||
            (saved != null && isOtherAccount(saved.account, account))
        if (stale && saved != null) storage.remove(profileId)
        stale
    }

    fun leftAccount(signedIn: Boolean, previousAccount: String?, account: String?): Boolean =
        !signedIn || isOtherAccount(previousAccount, account)

    private suspend fun writeIfChanged(profileId: Int, saved: SavedTraktWatchedMovies) {
        if (storage.load(profileId) != saved) storage.save(profileId, saved)
    }

    private fun isOtherAccount(saved: String?, current: String?): Boolean =
        saved != null && current != null && !saved.equals(current, ignoreCase = true)
}

internal val TraktAuthState.account: String?
    get() = userSlug?.takeIf { it.isNotBlank() } ?: username?.takeIf { it.isNotBlank() }
