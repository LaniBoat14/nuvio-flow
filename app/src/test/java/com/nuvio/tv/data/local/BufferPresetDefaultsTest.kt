package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BufferPresetDefaultsTest {
    private val gb = 1024L * 1024L * 1024L

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(data.value).also { data.value = it } }
    }

    // The unit-test android.jar reports API 0, so native memory could never be switched on.
    private fun setSdkInt(value: Int) {
        val field = android.os.Build.VERSION::class.java.getField("SDK_INT")
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
        unsafeClass.getMethod("putInt", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(unsafe, base, offset, value)
    }

    private fun setBoxRam(bytes: Long) {
        NuvioExoPlayerPerformanceHelper::class.java.getDeclaredField("cachedDevicePhysicalRamBytes")
            .apply { isAccessible = true }.setLong(NuvioExoPlayerPerformanceHelper, bytes)
    }

    @After
    fun restore() {
        setSdkInt(0)
        NuvioExoPlayerPerformanceHelper.clearCache()
    }

    private fun withStore(ramBytes: Long, block: suspend (PlayerSettingsDataStore) -> Unit) = runBlocking {
        setSdkInt(30)
        setBoxRam(ramBytes)
        val memory = MemoryStore()
        val manager = mockk<ProfileManager>()
        every { manager.activeProfileId } returns MutableStateFlow(0)
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(0, "player_settings") } returns memory
        val settings = PlayerSettingsDataStore(factory, manager, mockk(relaxed = true))
        try {
            withTimeout(5_000) {
                memory.data.first { it[booleanPreferencesKey("migration_target_buffer_size_reduced_done")] == true }
            }
            block(settings)
        } finally {
            val scopeField = PlayerSettingsDataStore::class.java.getDeclaredField("ioScope").apply { isAccessible = true }
            (scopeField.get(settings) as CoroutineScope).cancel()
        }
    }

    @Test
    fun `managed memory budget is on by default`() {
        assertTrue(PlayerSettings().bufferBudgetManaged)
        withStore((1.9 * gb).toLong()) { store ->
            assertTrue(store.playerSettings.first().bufferBudgetManaged)
        }
    }

    @Test
    fun `turning native memory on keeps the app sizing the buffer on a 2 GB box`() = withStore((1.9 * gb).toLong()) { store ->
        store.setBufferBudgetManaged(false)
        store.setBufferTargetSizeMb(200)
        store.setNuvioPerformanceModeEnabled(true)
        val s = store.playerSettings.first()
        assertTrue(s.nuvioPerformanceModeEnabled)
        assertTrue(s.bufferBudgetManaged)
        assertFalse(s.allowLargeTargetBuffer)
        assertEquals(200, s.bufferSettings.targetBufferSizeMb)
        assertEquals(2, s.parallelConnectionCount)
        assertEquals(12_000, s.bufferSettings.backBufferDurationMs)
    }

    @Test
    fun `the native memory preset brings a large size back into the range without Allow larger`() =
        withStore((1.9 * gb).toLong()) { store ->
            store.setAllowLargeTargetBuffer(true)
            store.setBufferTargetSizeMb(2000)
            store.setNuvioPerformanceModeEnabled(true)
            val s = store.playerSettings.first()
            assertFalse(s.allowLargeTargetBuffer)
            assertEquals(250, s.bufferSettings.targetBufferSizeMb)
        }

    @Test
    fun `turning native memory on keeps 4 connections and the set size on a 4 GB box`() = withStore((3.8 * gb).toLong()) { store ->
        store.setBufferBudgetManaged(false)
        store.setBufferTargetSizeMb(900)
        store.setNuvioPerformanceModeEnabled(true)
        val s = store.playerSettings.first()
        assertTrue(s.bufferBudgetManaged)
        assertFalse(s.allowLargeTargetBuffer)
        assertEquals(900, s.bufferSettings.targetBufferSizeMb)
        assertEquals(4, s.parallelConnectionCount)
    }

    @Test
    fun `buffer reset turns managed budget on and keeps the size with native memory`() = withStore((3.8 * gb).toLong()) { store ->
        store.setNuvioPerformanceModeEnabled(true)
        store.setBufferBudgetManaged(false)
        store.setBufferTargetSizeMb(700)
        store.setBufferBackBufferDurationMs(30_000)
        store.resetBufferSettingsToDefaults()
        val s = store.playerSettings.first()
        assertTrue(s.bufferBudgetManaged)
        assertFalse(s.allowLargeTargetBuffer)
        assertEquals(700, s.bufferSettings.targetBufferSizeMb)
        assertEquals(NuvioExoPlayerPerformanceHelper.DEFAULT_NUVIO_BACK_BUFFER_MS, s.bufferSettings.backBufferDurationMs)
    }

    @Test
    fun `buffer reset turns managed budget on and keeps the size without native memory`() = withStore((1.9 * gb).toLong()) { store ->
        store.setBufferBudgetManaged(false)
        store.setBufferTargetSizeMb(100)
        store.setBufferBackBufferDurationMs(30_000)
        store.resetBufferSettingsToDefaults()
        val s = store.playerSettings.first()
        assertFalse(s.nuvioPerformanceModeEnabled)
        assertTrue(s.bufferBudgetManaged)
        assertEquals(100, s.bufferSettings.targetBufferSizeMb)
        assertEquals(BufferSettings.DEFAULT_BACK_BUFFER_DURATION_MS, s.bufferSettings.backBufferDurationMs)
    }

    @Test
    fun `native memory allows up to 16 connections`() = withStore((3.8 * gb).toLong()) { store ->
        store.setNuvioPerformanceModeEnabled(true)
        store.setParallelConnectionCount(16)
        assertEquals(16, store.playerSettings.first().parallelConnectionCount)
        store.setParallelConnectionCount(20)
        assertEquals(16, store.playerSettings.first().parallelConnectionCount)
        store.updateMemorySettings(parallelConnectionCount = 12)
        assertEquals(12, store.playerSettings.first().parallelConnectionCount)
    }

    @Test
    fun `without native memory connections go up to 8 and old counts stay`() = withStore((3.8 * gb).toLong()) { store ->
        store.setParallelConnectionCount(4)
        assertEquals(4, store.playerSettings.first().parallelConnectionCount)
        store.setParallelConnectionCount(16)
        assertEquals(8, store.playerSettings.first().parallelConnectionCount)
        store.updateMemorySettings(parallelConnectionCount = 12)
        assertEquals(8, store.playerSettings.first().parallelConnectionCount)
    }

    @Test
    fun `chunk sizes go up to 128 MB and old sizes stay`() = withStore((3.8 * gb).toLong()) { store ->
        store.setParallelChunkSizeKb(32 * 1024)
        assertEquals(32 * 1024, store.playerSettings.first().parallelChunkSizeKb)
        store.setParallelChunkSizeKb(128 * 1024)
        assertEquals(128 * 1024, store.playerSettings.first().parallelChunkSizeKb)
        store.setParallelChunkSizeKb(256 * 1024)
        assertEquals(128 * 1024, store.playerSettings.first().parallelChunkSizeKb)
    }

    @Test
    fun `buffer ahead takes the stored max of a hand-set min and max`() = withStore((1.9 * gb).toLong()) { store ->
        store.setBufferMinBufferMs(20_000)
        store.setBufferMaxBufferMs(60_000)
        assertEquals(60_000, store.playerSettings.first().bufferSettings.bufferAheadMs)
        assertEquals(45_000, BufferSettings().bufferAheadMs)
        assertEquals(30_000, BufferSettings(minBufferMs = 30_000, maxBufferMs = 20_000).bufferAheadMs)
    }

    @Test
    fun `buffer ahead writes min and max and keeps to its range`() = withStore((1.9 * gb).toLong()) { store ->
        store.setBufferAheadMs(90_000)
        store.playerSettings.first().bufferSettings.let {
            assertEquals(90_000, it.minBufferMs)
            assertEquals(90_000, it.maxBufferMs)
            assertEquals(90_000, it.bufferAheadMs)
        }
        store.setBufferAheadMs(300_000)
        assertEquals(120_000, store.playerSettings.first().bufferSettings.bufferAheadMs)
        store.setBufferAheadMs(5_000)
        assertEquals(10_000, store.playerSettings.first().bufferSettings.bufferAheadMs)
    }

    @Test
    fun `with native memory buffer ahead goes up to 600 s and the preset keeps its 280 s`() =
        withStore((3.8 * gb).toLong()) { store ->
            store.setNuvioPerformanceModeEnabled(true)
            assertEquals(280_000, store.playerSettings.first().bufferSettings.bufferAheadMs)
            store.setBufferAheadMs(300_000)
            assertEquals(300_000, store.playerSettings.first().bufferSettings.bufferAheadMs)
            store.setBufferAheadMs(1_200_000)
            assertEquals(600_000, store.playerSettings.first().bufferSettings.bufferAheadMs)
        }
}
