package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
import org.junit.Assert.assertEquals
import org.junit.Test

class NightModePreferenceTest {

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private fun withStore(block: suspend (PlayerSettingsDataStore, MemoryStore) -> Unit) = runBlocking {
        val memory = MemoryStore()
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(0)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(0, "player_settings") } returns memory
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            block(settings, memory)
        } finally {
            val scopeField = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scopeField.get(settings) as CoroutineScope).cancel()
        }
    }

    @Test
    fun `night mode is off by default`() {
        assertEquals(NightModeLevel.OFF, PlayerSettings().nightMode)
        withStore { store, _ -> assertEquals(NightModeLevel.OFF, store.playerSettings.first().nightMode) }
    }

    @Test
    fun `the chosen level is remembered`() = withStore { store, memory ->
        for (level in NightModeLevel.entries.reversed()) {
            store.setNightMode(level)
            assertEquals(level, store.playerSettings.first().nightMode)
            assertEquals(level.key, memory.data.value[stringPreferencesKey("night_mode")])
        }
    }

    @Test
    fun `an unknown stored value falls back to off`() = withStore { store, memory ->
        memory.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("night_mode")] = "loud" } }
        assertEquals(NightModeLevel.OFF, store.playerSettings.first().nightMode)
    }

    @Test
    fun `keys round trip`() {
        for (level in NightModeLevel.entries) assertEquals(level, NightModeLevel.fromKey(level.key))
        assertEquals(NightModeLevel.OFF, NightModeLevel.fromKey(null))
    }

    @Test
    fun `turning night mode off remembers the level it had`() = withStore { store, _ ->
        assertEquals(NightModeLevel.MEDIUM, store.playerSettings.first().nightModeLastOn)
        store.setNightMode(NightModeLevel.STRONG)
        store.setNightMode(NightModeLevel.OFF)
        assertEquals(NightModeLevel.STRONG, store.playerSettings.first().nightModeLastOn)
        store.setNightMode(NightModeLevel.OFF)
        assertEquals(NightModeLevel.STRONG, store.playerSettings.first().nightModeLastOn)
        store.setNightMode(NightModeLevel.LIGHT)
        assertEquals(NightModeLevel.LIGHT, store.playerSettings.first().nightModeLastOn)
    }

    @Test
    fun `a level set before the remembered level existed is kept when turned off`() = withStore { store, memory ->
        memory.updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("night_mode")] = "strong" } }
        store.setNightMode(NightModeLevel.OFF)
        assertEquals(NightModeLevel.STRONG, store.playerSettings.first().nightModeLastOn)
    }
}
