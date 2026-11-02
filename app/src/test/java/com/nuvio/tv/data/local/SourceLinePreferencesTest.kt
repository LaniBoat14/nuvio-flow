package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class SourceLinePreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    @Test fun `source line switches default on and are saved separately per profile`() = runBlocking {
        val active = MutableStateFlow(0)
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns active
        val stores = listOf(MemoryStore(), MemoryStore())
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } answers { stores[firstArg()] }
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            assertTrue(settings.playerSettings.first().showSourceLineBadges)
            assertTrue(settings.playerSettings.first().showSourceLineReleaseGroup)
            settings.setShowSourceLineReleaseGroup(false)
            assertTrue(settings.playerSettings.first().showSourceLineBadges)
            assertFalse(settings.playerSettings.first().showSourceLineReleaseGroup)
            settings.setShowSourceLineBadges(false)
            assertFalse(settings.playerSettings.first().showSourceLineBadges)
            assertFalse(settings.playerSettings.first().showSourceLineReleaseGroup)
            assertEquals(false, stores[0].data.value[booleanPreferencesKey("show_source_line_badges")])
            assertEquals(false, stores[0].data.value[booleanPreferencesKey("show_source_line_release_group")])
            active.value = 1
            assertTrue(settings.playerSettings.first().showSourceLineBadges)
            assertTrue(settings.playerSettings.first().showSourceLineReleaseGroup)
            settings.setShowSourceLineBadges(false)
            assertFalse(settings.playerSettings.first().showSourceLineBadges)
            assertTrue(settings.playerSettings.first().showSourceLineReleaseGroup)
            active.value = 0
            assertFalse(settings.playerSettings.first().showSourceLineBadges)
            assertFalse(settings.playerSettings.first().showSourceLineReleaseGroup)
            settings.setShowSourceLineBadges(true)
            settings.setShowSourceLineReleaseGroup(true)
            assertTrue(settings.playerSettings.first().showSourceLineBadges)
            assertTrue(settings.playerSettings.first().showSourceLineReleaseGroup)
        } finally {
            val scope = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scope.get(settings) as CoroutineScope).cancel()
        }
    }
}
