package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.supportNuvioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "support_nuvio",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

@Singleton
class SupportNuvioDataStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore = context.supportNuvioDataStore
    private val suggestionsHiddenKey = booleanPreferencesKey("suggestions_hidden")
    private val thankedUsersKey = stringSetPreferencesKey("thanked_users")

    val suggestionsHidden: Flow<Boolean> = dataStore.data
        .map { it[suggestionsHiddenKey] == true }
        .distinctUntilChanged()

    val thankedUsers: Flow<Set<String>> = dataStore.data
        .map { it[thankedUsersKey].orEmpty() }
        .distinctUntilChanged()

    suspend fun setSuggestionsHidden(hidden: Boolean) {
        dataStore.edit { it[suggestionsHiddenKey] = hidden }
    }

    suspend fun toggleSuggestionsHidden() {
        dataStore.edit { it[suggestionsHiddenKey] = it[suggestionsHiddenKey] != true }
    }

    suspend fun markThanked(userId: String) {
        dataStore.edit { it[thankedUsersKey] = it[thankedUsersKey].orEmpty() + userId }
    }
}
