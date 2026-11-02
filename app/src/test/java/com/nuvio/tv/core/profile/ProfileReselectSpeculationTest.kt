package com.nuvio.tv.core.profile

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.stream.StreamPrefetchCache
import com.nuvio.tv.data.local.ContinueWatchingSnapshotStorage
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProfileReselectSpeculationTest {
    private val dir = Files.createTempDirectory("profile-reselect-").toFile()
    private val active = MutableStateFlow(2)
    private lateinit var created: ProfileManager

    private fun newManager(): ProfileManager {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns dir
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val data = mockk<ProfileDataStore>(relaxed = true)
        val factory = mockk<ProfileDataStoreFactory>(relaxed = true)
        every { data.profilesList } returns MutableStateFlow(listOf(profile(1), profile(2)))
        every { data.activeProfileId } returns active
        every { data.hasEverSelectedProfile } returns MutableStateFlow(true)
        every { data.rememberLastProfileEnabled } returns MutableStateFlow(false)
        every { data.confirmExitEnabled } returns MutableStateFlow(false)
        every { data.startupSplashEnabled } returns MutableStateFlow(true)
        every { factory.historyGenerationChanges } returns MutableStateFlow(0L)
        coEvery { data.setActiveProfile(any()) } coAnswers { active.value = firstArg() }
        val credentials = mockk<ProfileScopedCredentialStore>(relaxed = true)
        return ProfileManager(data, factory, setOf(credentials), context, ContinueWatchingSnapshotStorage(context))
            .also { created = it }
    }

    private fun profile(id: Int) = UserProfile(id, "profile$id", "#1E88E5")

    @After
    fun stop() {
        StreamPrefetchCache.updatePolicy(-1, false)
        StreamPrefetchCache.bindProfile { null }
        if (::created.isInitialized) {
            val scope = ProfileManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(created) as CoroutineScope
            scope.coroutineContext[Job]!!.cancel()
        }
        dir.deleteRecursively()
    }

    @Test
    fun `picking the profile already active keeps the early source search on`() = runBlocking {
        val m = newManager()
        withTimeout(5_000) { m.activeProfileId.first { it == 2 } }
        StreamPrefetchCache.bindProfile { m.activeProfileId.value }
        StreamPrefetchCache.updatePolicy(2, true)

        m.setActiveProfile(2)

        assertTrue(StreamPrefetchCache.speculationAllowed.value)
    }

    @Test
    fun `switching to another profile still stops it until that profile's setting is read`() = runBlocking {
        val m = newManager()
        withTimeout(5_000) { m.activeProfileId.first { it == 2 } }
        StreamPrefetchCache.bindProfile { m.activeProfileId.value }
        StreamPrefetchCache.updatePolicy(2, true)

        m.setActiveProfile(1)

        assertFalse(StreamPrefetchCache.speculationAllowed.value)
    }
}
