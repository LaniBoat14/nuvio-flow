package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDisplayNameTest {
    private val product: (ServerConnection) -> String = { it.providerId.replaceFirstChar(Char::uppercase) }

    private fun connection(id: String, providerId: String, name: String, displayName: String? = null, user: String = "viewer") =
        ServerConnection(
            id = id,
            providerId = providerId,
            name = name,
            address = "https://$id.example",
            remoteServerId = "s-$id",
            remoteUserId = "u-$id",
            userName = user,
            credentialRef = "k-$id",
            displayName = displayName
        )

    @Test
    fun typedNamesAreCleanedAndEmptyMeansTheServersOwnName() {
        assertEquals("Home NAS", serverDisplayName("  Home   NAS ", "pluto"))
        assertEquals("Home NAS", serverDisplayName("Home\nNAS", "pluto"))
        assertNull(serverDisplayName("", "pluto"))
        assertNull(serverDisplayName("   ", "pluto"))
        assertNull(serverDisplayName(null, "pluto"))
        assertNull(serverDisplayName(" pluto ", "pluto"))
        assertEquals("a".repeat(SERVER_DISPLAY_NAME_MAX_LENGTH), serverDisplayName("a".repeat(45), "pluto"))
        assertEquals("a".repeat(29), serverDisplayName("a".repeat(29) + " xyz", "pluto"))
        assertEquals("a".repeat(29), serverDisplayName("a".repeat(29) + "🎬", "pluto"))
    }

    @Test
    fun theShownNameIsTheDisplayNameWhenSet() {
        val plain = connection("a", "jellyfin", "pluto")
        assertEquals("pluto", plain.shownName)
        assertFalse(plain.isRenamed)

        val renamed = plain.copy(displayName = "Home NAS")
        assertEquals("Home NAS", renamed.shownName)
        assertTrue(renamed.isRenamed)

        assertEquals("pluto", plain.copy(displayName = " ").shownName)
    }

    @Test
    fun homeRowsDropTheProductOnceTheNamesDiffer() {
        assertEquals(
            mapOf("a" to "Jellyfin · pluto", "b" to "Emby · pluto"),
            serverRowLabels(listOf(connection("a", "jellyfin", "pluto"), connection("b", "emby", "pluto")), product)
        )
        assertEquals(
            mapOf("a" to "Home NAS", "b" to "pluto"),
            serverRowLabels(
                listOf(connection("a", "jellyfin", "pluto", displayName = "Home NAS"), connection("b", "emby", "pluto")),
                product
            )
        )
    }

    @Test
    fun displayNamesThatClashGetTheProductBack() {
        assertEquals(
            mapOf("a" to "Jellyfin · Home", "b" to "Emby · home"),
            serverRowLabels(
                listOf(connection("a", "jellyfin", "pluto", displayName = "Home"), connection("b", "emby", "home")),
                product
            )
        )
        assertEquals(
            mapOf("a" to "Jellyfin · Media (sam)", "b" to "Jellyfin · Media (kids)"),
            serverRowLabels(
                listOf(
                    connection("a", "jellyfin", "pluto", displayName = "Media", user = "sam"),
                    connection("b", "jellyfin", "mars", displayName = "Media", user = "kids")
                ),
                product
            )
        )
    }

    @Test
    fun renameAndResetAreSavedWithTheServer() {
        val persistence = MemoryServerPersistence()
        val repository = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))
        repository.store(connection("c1", "jellyfin", "pluto"), "token")

        repository.setDisplayName("c1", "  Home NAS ")
        assertEquals("Home NAS", repository.connection("c1")!!.displayName)
        assertEquals("jellyfin · Home NAS", repository.sourceLabel(repository.connection("c1")!!))
        val reloaded = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))
        assertEquals("Home NAS", reloaded.connection("c1")!!.shownName)

        repository.setDisplayName("c1", "")
        assertEquals("", repository.connection("c1")!!.displayName)
        assertFalse(repository.connection("c1")!!.isRenamed)
        repository.setDisplayName("c1", "Home NAS")
        repository.setDisplayName("c1", null)
        assertEquals("pluto", repository.connection("c1")!!.shownName)
    }

    @Test
    fun aServerThatWasNeverRenamedSendsNoName() {
        val repository = ServerRepository(MemoryServerPersistence(), emptyList(), CoroutineScope(Dispatchers.Unconfined))
        repository.store(connection("c1", "jellyfin", "pluto"), "token")

        repository.setDisplayName("c1", "")
        repository.setDisplayName("c1", "pluto")

        assertNull(repository.connection("c1")!!.displayName)
        assertNull(repository.connection("c1")!!.toSynced("token").displayName)
    }

    @Test
    fun storedServersFromBeforeRenamingStillLoad() {
        val persistence = MemoryServerPersistence()
        persistence.write(
            1,
            """{"connections":[{"id":"c1","providerId":"jellyfin","name":"pluto","address":"https://a.example",""" +
                """"remoteServerId":"s1","remoteUserId":"u1","userName":"viewer","credentialRef":"k1"}],""" +
                """"tokens":{"k1":"token"},"fullSourceText":true}"""
        )
        val repository = ServerRepository(persistence, emptyList(), CoroutineScope(Dispatchers.Unconfined))

        assertEquals("pluto", repository.connection("c1")!!.shownName)
        assertTrue(repository.uiState.value.showServerLogos)
        assertTrue(repository.uiState.value.fullSourceText)
        assertEquals("token", repository.session("c1")?.token)
    }

    @Test
    fun signingInAgainKeepsTheName() = runBlocking {
        val provider = FakeServerProvider()
        val (repository, connection) = fakeServerRepository(provider)
        repository.setDisplayName(connection.id, "Home NAS")

        repository.connect(provider, "https://fake.example", "viewer", "pw")

        assertEquals("Home NAS", repository.connection(connection.id)!!.shownName)
    }

    @Test
    fun homeRowsLibrariesAndCollectionsUseTheName() = runBlocking {
        val (repository, connection) = fakeServerRepository()
        repository.setDisplayName(connection.id, "Home NAS")
        val addonRepository = mockk<AddonRepository> { every { getInstalledAddons() } returns MutableStateFlow(emptyList()) }
        val catalog = ServerCatalog(repository, addonRepository, serverArtwork(FakeMetaRepository()))

        val addon = catalog.addons.first().single()
        assertEquals("Fake · Home NAS", addon.displayName)
        assertEquals("Home NAS · Movies", addon.catalogs.first().name)
        assertEquals("Home NAS · Movies", catalog.libraries().first().title)
        assertEquals("Fake · Home NAS", catalog.sourceLabel(connection.id))
    }

    private fun streams(repository: ServerRepository): ServerStreams {
        val metaRepository = mockk<MetaRepository> { every { getCachedMeta(any(), any()) } returns null }
        return ServerStreams(repository, ServerMatcher(repository, mockk<TmdbService>(relaxed = true), metaRepository))
    }

    @Test
    fun sourcesCarryTheNameUnderTheLogoAndInTheDetails() = runBlocking {
        val (repository, connection) = fakeServerRepository(FakeServerProvider(id = "jellyfin"))
        repository.setDisplayName(connection.id, "Home NAS")
        val streams = streams(repository)

        val stream = streams.candidates(ServerItemRef(connection.id, "m1")).single()
        assertEquals("Fake · Home NAS", stream.addonName)
        assertTrue(stream.description!!.endsWith("Home NAS"))
        assertEquals(listOf("Fake · Home NAS"), streams.sources("movie", ServerItemRef(connection.id, "m1").encode(), null, null).map { it.name })
    }

    @Test
    fun theLogoSwitchHidesOnlyTheLogo() = runBlocking {
        val persistence = MemoryServerPersistence()
        val provider = FakeServerProvider(id = "jellyfin")
        val repository = ServerRepository(persistence, listOf(provider), CoroutineScope(Dispatchers.Unconfined))
        val connection = connection("c1", "jellyfin", "pluto")
        repository.store(connection, "token")
        val streams = streams(repository)
        assertTrue(repository.uiState.value.showServerLogos)
        assertEquals(serverLogoUri("jellyfin"), streams.candidates(ServerItemRef(connection.id, "m1")).single().addonLogo)
        val revision = streams.revision

        repository.setShowServerLogos(false)
        val stream = streams.candidates(ServerItemRef(connection.id, "m1")).single()
        assertNull(stream.addonLogo)
        assertEquals("Fake · pluto", stream.addonName)
        assertTrue(revision != streams.revision)

        val reloaded = ServerRepository(persistence, listOf(provider), CoroutineScope(Dispatchers.Unconfined))
        reloaded.ensureLoaded()
        assertFalse(reloaded.uiState.value.showServerLogos)
    }
}
