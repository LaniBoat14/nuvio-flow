package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEpisodeSynopsisDefaultTest {
    @Test fun `the synopsis under the row is on until a profile turns it off`() = runTest {
        val active = MutableStateFlow(1)
        val manager = mockk<ProfileManager> { every { activeProfileId } returns active }
        val factory = mockk<ProfileDataStoreFactory>()
        val fresh = MutableStateFlow<Preferences>(emptyPreferences())
        val switchedOff = MutableStateFlow<Preferences>(preferencesOf(booleanPreferencesKey("episode_synopsis_under_row") to false))
        every { factory.get(1, "layout_settings") } returns mockk<DataStore<Preferences>> { every { data } returns fresh }
        every { factory.get(2, "layout_settings") } returns mockk<DataStore<Preferences>> { every { data } returns switchedOff }
        val layout = LayoutPreferenceDataStore(factory, manager)
        assertTrue(layout.episodeSynopsisUnderRow.first())
        active.value = 2
        assertFalse(layout.episodeSynopsisUnderRow.first())
        active.value = 1
        assertTrue(layout.episodeSynopsisUnderRow.first())
    }
}
