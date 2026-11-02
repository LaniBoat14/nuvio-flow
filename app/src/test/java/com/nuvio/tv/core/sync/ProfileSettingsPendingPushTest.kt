package com.nuvio.tv.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSettingsPendingPushTest {
    private val dispatcher = StandardTestDispatcher()
    private val scopes = mutableListOf<CoroutineScope>()
    private val cardStyle = stringPreferencesKey("continue_watching_card_style")

    @Before
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        unmockkStatic(Dispatchers::class)
    }

    @Test
    fun `pending edit wins only for the account it was made under`() {
        val mine = PendingProfileSettingsPush("user-a", sequence = 3, lastLocalEditMs = 10)
        assertTrue(shouldPushPendingInsteadOfApplyingRemote(mine, "user-a"))
        assertFalse(shouldPushPendingInsteadOfApplyingRemote(mine, "user-b"))
        assertFalse(shouldPushPendingInsteadOfApplyingRemote(mine, null))
        assertFalse(shouldPushPendingInsteadOfApplyingRemote(null, "user-a"))
        assertFalse(shouldPushPendingInsteadOfApplyingRemote(mine.copy(ownerUserId = null), "user-a"))
    }

    @Test
    fun `marker clears only when the push covered the newest edit`() {
        val pending = PendingProfileSettingsPush("user-a", sequence = 4, lastLocalEditMs = 10)
        assertTrue(canClearPendingAfterPush(pending, 4))
        assertTrue(canClearPendingAfterPush(pending, 5))
        assertFalse(canClearPendingAfterPush(pending, 3))
        assertTrue(canClearPendingAfterPush(null, 0))
    }

    @Test
    fun `retries back off and then stop`() {
        assertEquals(5_000L, profileSettingsPushRetryDelayMs(0))
        assertEquals(30_000L, profileSettingsPushRetryDelayMs(1))
        assertEquals(120_000L, profileSettingsPushRetryDelayMs(2))
        assertNull(profileSettingsPushRetryDelayMs(3))
    }

    @Test
    fun `state store keeps markers per profile and ignores stale pushes`() {
        val store = ProfileSettingsSyncStateStore(contextWith(FakeSharedPreferences()))
        store.markPending(1, "user-a", nowMs = 100)
        val pushedSequence = store.currentSequence(1)
        store.markPending(1, "user-a", nowMs = 200)

        assertNull(store.pending(2))
        assertEquals(200L, store.pending(1)?.lastLocalEditMs)
        assertFalse(store.clearPending(1, pushedSequence))
        assertNotNull(store.pending(1))
        assertTrue(store.clearPending(1, store.currentSequence(1)))
        assertNull(store.pending(1))

        store.markPending(2, null)
        assertNull(store.pending(2)?.ownerUserId)
    }

    @Test
    fun `pull pushes a pending local edit instead of applying the cloud copy`() = runTest(dispatcher) {
        val harness = harness()
        runCurrent()
        harness.layout.edit { it[cardStyle] = "POSTER" }
        runCurrent()
        assertNotNull(harness.state.pending(1))
        harness.remote[1] = settings(cardStyle = "WIDE")

        val applied = harness.service.pullCurrentProfileFromRemote().getOrThrow()

        assertFalse(applied)
        assertEquals("POSTER", harness.layout.value[cardStyle])
        assertEquals("POSTER", remoteCardStyle(harness))
        assertNull(harness.state.pending(1))
    }

    @Test
    fun `marker from another account lets the cloud copy load`() = runTest(dispatcher) {
        val harness = harness()
        harness.layout.edit { it[cardStyle] = "POSTER" }
        harness.state.markPending(1, "user-b")
        harness.remote[1] = settings(cardStyle = "WIDE")

        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())

        assertEquals("WIDE", harness.layout.value[cardStyle])
        assertNull(harness.state.pending(1))
        assertTrue(harness.pushes.isEmpty())
    }

    @Test
    fun `applying the cloud copy is not mistaken for a local edit`() = runTest(dispatcher) {
        val harness = harness()
        runCurrent()
        harness.remote[1] = settings(cardStyle = "WIDE")

        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals("WIDE", harness.layout.value[cardStyle])
        assertNull(harness.state.pending(1))
        assertTrue(harness.pushes.isEmpty())
    }

    @Test
    fun `card style survives a cloud copy that does not have it`() = runTest(dispatcher) {
        val harness = harness()
        harness.layout.edit { it[cardStyle] = "POSTER" }
        harness.remote[1] = settings(cardStyle = null, heroSection = false)

        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())

        assertEquals("POSTER", harness.layout.value[cardStyle])
        assertEquals(false, harness.layout.value[booleanPreferencesKey("hero_section_enabled")])
    }

    @Test
    fun `failed push is retried with backoff until it lands`() = runTest(dispatcher) {
        val harness = harness(failingPushes = 2)
        runCurrent()
        harness.layout.edit { it[cardStyle] = "POSTER" }
        runCurrent()

        advanceTimeBy(1_600)
        runCurrent()
        assertEquals(1, harness.pushAttempts())
        assertNotNull(harness.state.pending(1))

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, harness.pushAttempts())

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(3, harness.pushAttempts())
        assertEquals("POSTER", remoteCardStyle(harness))
        assertNull(harness.state.pending(1))

        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(3, harness.pushAttempts())
    }

    @Test
    fun `retries stop after the last backoff step and keep the marker`() = runTest(dispatcher) {
        val harness = harness(failingPushes = Int.MAX_VALUE)
        runCurrent()
        harness.layout.edit { it[cardStyle] = "POSTER" }
        runCurrent()

        advanceTimeBy(3_600_000)
        runCurrent()

        assertEquals(4, harness.pushAttempts())
        assertNotNull(harness.state.pending(1))
    }

    private fun remoteCardStyle(harness: Harness): String? =
        harness.remote[1]?.getValue("features")?.jsonObject
            ?.get("layout_settings")?.jsonObject
            ?.get(cardStyle.name)?.jsonObject
            ?.getValue("value")?.jsonPrimitive?.content

    private fun harness(failingPushes: Int = 0): Harness {
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
        every { factory.corruptedFileNames } returns mutableSetOf()
        val activeProfileId = MutableStateFlow(1)
        val profiles = MutableStateFlow(listOf(UserProfile(1, "Primary", "#000000")))
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfile } answers { profiles.value.first { it.id == activeProfileId.value } }
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns true
        every { authManager.currentUserId } returns "user-a"
        every { authManager.authState } returns MutableStateFlow(AuthState.FullAccount("user-a", "a@example.com"))
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-pending-push-test"
        val remote = mutableMapOf<Int, JsonObject>()
        val pushes = mutableListOf<Int>()
        var pushAttempts = 0
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            val params = secondArg<JsonObject>()
            val requestedProfile = params.getValue("p_profile_id").jsonPrimitive.int
            val response = when (firstArg<String>()) {
                "sync_push_profile_settings_blob" -> {
                    pushAttempts++
                    if (pushAttempts <= failingPushes) error("Network unavailable")
                    pushes += requestedProfile
                    remote[requestedProfile] = params.getValue("p_settings_json").jsonObject
                    ""
                }
                "sync_pull_profile_settings_blob" -> {
                    val blob = remote[requestedProfile]
                    JsonArray(if (blob == null) emptyList() else listOf(buildJsonObject {
                        put("profile_id", requestedProfile)
                        put("settings_json", blob)
                    })).toString()
                }
                else -> error("Unexpected RPC")
            }
            PostgrestResult(response, Headers.Empty, postgrest)
        }
        val state = ProfileSettingsSyncStateStore(contextWith(FakeSharedPreferences()))
        val service = ProfileSettingsSyncService(
            authManager, postgrest, profileManager, factory, identity,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), state
        )
        scopes += ProfileSettingsSyncService::class.java.getDeclaredField("scope")
            .apply { isAccessible = true }.get(service) as CoroutineScope
        return Harness(
            service = service,
            state = state,
            layout = factory.get(1, "layout_settings") as TestPreferencesStore,
            remote = remote,
            pushes = pushes,
            pushAttempts = { pushAttempts }
        )
    }

    private fun contextWith(preferences: SharedPreferences): Context {
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns preferences
        return context
    }

    private fun settings(cardStyle: String?, heroSection: Boolean? = null) = buildJsonObject {
        put("version", 1)
        put("features", buildJsonObject {
            put("layout_settings", buildJsonObject {
                if (cardStyle != null) {
                    put(this@ProfileSettingsPendingPushTest.cardStyle.name, buildJsonObject {
                        put("type", "string")
                        put("value", cardStyle)
                    })
                }
                if (heroSection != null) {
                    put("hero_section_enabled", buildJsonObject {
                        put("type", "boolean")
                        put("value", heroSection)
                    })
                }
            })
        })
    }

    private class Harness(
        val service: ProfileSettingsSyncService,
        val state: ProfileSettingsSyncStateStore,
        val layout: TestPreferencesStore,
        val remote: MutableMap<Int, JsonObject>,
        val pushes: MutableList<Int>,
        val pushAttempts: () -> Int
    )

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = key in values
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                if (clear) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(changes)
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }
}
