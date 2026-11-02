package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHdrSubtitleStyleMigrationTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    private val dimKey = booleanPreferencesKey("dim_hdr_overlays")
    private val hdrStyleKey = booleanPreferencesKey("subtitle_hdr_style_enabled")

    private fun withSettings(initial: Preferences, block: suspend (PlayerSettingsDataStore, MemoryStore) -> Unit) = runBlocking {
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(0)
        val store = MemoryStore(initial)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), "player_settings") } returns store
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            block(settings, store)
        } finally {
            val field = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (field.get(settings) as CoroutineScope).cancel()
        }
    }

    private suspend fun awaitKey(store: MemoryStore) = withTimeout(5_000) {
        while (store.data.value[hdrStyleKey] == null) delay(10)
        store.data.value[hdrStyleKey]
    }

    @Test
    fun `an upgrade keeps dimmed HDR subtitles once, then the controls switch leaves them alone`() =
        withSettings(preferencesOf(dimKey to true)) { settings, store ->
            assertEquals(true, awaitKey(store))
            settings.setDimHdrOverlays(false)
            val snapshot = settings.playerSettings.first()
            assertFalse(snapshot.dimHdrOverlays)
            assertTrue(snapshot.subtitleStyle.hdrStyleEnabled)
        }

    @Test
    fun `turning on Dim controls in HDR does not turn on the HDR subtitle style`() =
        withSettings(emptyPreferences()) { settings, store ->
            awaitKey(store)
            settings.setDimHdrOverlays(true)
            val snapshot = settings.playerSettings.first()
            assertTrue(snapshot.dimHdrOverlays)
            assertFalse(snapshot.subtitleStyle.hdrStyleEnabled)
        }

    @Test
    fun `restored settings without the subtitle key are pinned before the controls switch changes`() =
        withSettings(emptyPreferences()) { settings, store ->
            awaitKey(store)
            store.data.value = preferencesOf(dimKey to true)
            settings.setDimHdrOverlays(false)
            assertEquals(true, store.data.value[hdrStyleKey])
            assertTrue(settings.playerSettings.first().subtitleStyle.hdrStyleEnabled)
        }
}
