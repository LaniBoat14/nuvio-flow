package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerLibrary
import com.nuvio.tv.data.mediaserver.ServerMediaKind
import com.nuvio.tv.data.mediaserver.ServerSession
import com.nuvio.tv.data.mediaserver.ServerThumbnailFormat
import com.nuvio.tv.data.mediaserver.ServerTrickplay
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaBrowserTrickplayTest {
    private fun session(providerId: String, address: String) = ServerSession(
        connection = ServerConnection(
            id = "cabc",
            providerId = providerId,
            name = "Home",
            address = address,
            remoteServerId = "s1",
            remoteUserId = "u1",
            userName = "viewer",
            credentialRef = "k1",
            libraries = listOf(ServerLibrary("lib1", "Movies", ServerMediaKind.MOVIE))
        ),
        token = "secret"
    )

    private val json = MediaBrowserClient("Authorization", TestHttp().client, testIdentity).json

    private val item = """
        {"Id": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", "Name": "Film",
         "Trickplay": {
           "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6": {
             "320": {"Width": 320, "Height": 134, "TileWidth": 10, "TileHeight": 10, "ThumbnailCount": 712,
                     "Interval": 10000, "Bandwidth": 21000},
             "640": {"Width": 640, "Height": 268, "TileWidth": 10, "TileHeight": 10, "ThumbnailCount": 712,
                     "Interval": 10000, "Bandwidth": 82000}
           },
           "ffff0000ffff0000ffff0000ffff0000": {
             "320": {"Width": 320, "Height": 180, "TileWidth": 10, "TileHeight": 10, "ThumbnailCount": 5,
                     "Interval": 10000, "Bandwidth": 1}
           }
         }}
    """.trimIndent()

    @Test
    fun readsTheWidthsOfThePlayingVersion() {
        val parsed = json.decodeFromString(BaseItem.serializer(), item)
        val set = trickplayLevels(parsed.trickplay, parsed.id, "a1b2c3d4-e5f6-a7b8-c9d0-e1f2a3b4c5d6")!!
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6", set.mediaSourceId)
        assertEquals(
            listOf(
                ServerTrickplay(320, 134, 10, 10, 712, 10_000L),
                ServerTrickplay(640, 268, 10, 10, 712, 10_000L)
            ),
            set.levels
        )
        val other = trickplayLevels(parsed.trickplay, parsed.id, "FFFF0000FFFF0000FFFF0000FFFF0000")!!
        assertEquals(listOf(5), other.levels.map { it.thumbnailCount })
        assertEquals("ffff0000ffff0000ffff0000ffff0000", other.mediaSourceId)
    }

    @Test
    fun fallsBackToTheItemKeyOnly() {
        val parsed = json.decodeFromString(BaseItem.serializer(), item)
        val stand = trickplayLevels(parsed.trickplay, parsed.id, "unknown-version")!!
        assertEquals(2, stand.levels.size)
        assertEquals(parsed.id, stand.mediaSourceId)
        assertNull(trickplayLevels(parsed.trickplay, "other-item", "unknown-version"))
    }

    @Test
    fun noTrickplayMeansNone() {
        assertNull(trickplayLevels(json.decodeFromString(BaseItem.serializer(), """{"Id": "x"}""").trickplay, "x", "x"))
        assertNull(trickplayLevels(json.decodeFromString(BaseItem.serializer(), """{"Id": "x", "Trickplay": null}""").trickplay, "x", "x"))
        assertNull(trickplayLevels(json.decodeFromString(BaseItem.serializer(), """{"Id": "x", "Trickplay": {}}""").trickplay, "x", "x"))
        assertNull(trickplayLevels(json.decodeFromString(BaseItem.serializer(), """{"Id": "x", "Trickplay": {"x": {}}}""").trickplay, "x", "x"))
    }

    @Test
    fun dropsUnusableWidths() {
        val parsed = json.decodeFromString(
            BaseItem.serializer(),
            """{"Id": "x", "Trickplay": {"x": {
                "320": {"Width": 320, "Height": 180, "TileWidth": 10, "TileHeight": 10, "ThumbnailCount": 0, "Interval": 10000},
                "480": {"Height": 270, "TileWidth": 10, "TileHeight": 10, "ThumbnailCount": 9, "Interval": 10000},
                "640": {"Width": 640, "Height": 360, "TileWidth": 0, "TileHeight": 10, "ThumbnailCount": 9, "Interval": 10000}
            }}}"""
        )
        assertEquals(listOf(480), trickplayLevels(parsed.trickplay, "x", "x")!!.levels.map { it.width })
    }

    @Test
    fun jellyfinAsksForTheTrickplayFieldAndSignsSheetRequests() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/jf/Items/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6" -> item
                "/jf/Videos/a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6/Trickplay/640/3.jpg" -> "jpeg"
                else -> error("Unexpected ${request.url}")
            }
        }
        val provider = JellyfinProvider(http.client, testIdentity)
        val s = session("jellyfin", "http://den.local:8096/jf")
        assertEquals(ServerThumbnailFormat.TRICKPLAY, provider.thumbnailFormat)
        val id = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
        assertEquals(2, provider.trickplay(s, id, id)!!.levels.size)
        assertEquals("jpeg", String(provider.trickplaySheet(s, id, id, 640, 3, 1024L)))
        val (itemRequest, sheetRequest) = http.requests
        assertEquals("Trickplay", itemRequest.url.queryParameter("fields"))
        assertEquals("u1", itemRequest.url.queryParameter("userId"))
        assertEquals(id, sheetRequest.url.queryParameter("mediaSourceId"))
        assertTrue(sheetRequest.header("Authorization")!!.contains("Token=\"secret\""))
    }

    @Test
    fun anOversizedSheetFails() = runTest {
        val http = TestHttp { "x".repeat(100) }
        val provider = JellyfinProvider(http.client, testIdentity)
        try {
            provider.trickplaySheet(session("jellyfin", "http://den.local:8096"), "i", "m", 320, 0, 10L)
            fail("expected a failure")
        } catch (e: ServerException) {
            assertEquals(ServerFailure.FAILED, e.failure)
        }
    }

    @Test
    fun siloUsesTheJellyfinPathWithItsVersionId() = runTest {
        val http = TestHttp { request ->
            when (request.url.encodedPath) {
                "/Items/movie-1" -> """{"Id": "movie-1", "Trickplay": {"version-2": {"320": {"Width": 320, "Height": 180,
                    "TileWidth": 10, "TileHeight": 10, "ThumbnailCount": 20, "Interval": 10000}}}}"""
                "/Videos/movie-1/Trickplay/320/0.jpg" -> "jpeg"
                else -> error("Unexpected ${request.url}")
            }
        }
        val provider = SiloProvider(http.client, testIdentity)
        val s = session("silo", "http://192.168.1.20:8096")
        assertEquals(listOf(20), provider.trickplay(s, "movie-1", "version-2")!!.levels.map { it.thumbnailCount })
        provider.trickplaySheet(s, "movie-1", "version-2", 320, 0, 1024L)
        assertEquals("version-2", http.requests.last().url.queryParameter("mediaSourceId"))
    }

    @Test
    fun embyAsksForTheBifUnderItsApiRootWithARange() = runTest {
        val http = TestHttp(status = { 206 }) { request ->
            assertEquals("/emby/Videos/77/index.bif", request.url.encodedPath)
            "BIF"
        }
        val provider = EmbyProvider(http.client, testIdentity)
        val s = session("emby", "http://den.local:8096")
        assertEquals(ServerThumbnailFormat.BIF, provider.thumbnailFormat)
        assertNull(provider.trickplay(s, "77", "77"))
        val reply = provider.bif(s, "77", "mediasource_77", 320, 0L until 65_536L, 1_000L)
        assertTrue(reply.partial)
        assertFalse(reply.truncated)
        assertEquals("BIF", String(reply.bytes))
        val request = http.requests.single()
        assertEquals("bytes=0-65535", request.header("Range"))
        assertEquals("320", request.url.queryParameter("Width"))
        assertEquals("mediasource_77", request.url.queryParameter("MediaSourceId"))
        assertTrue(request.header("X-Emby-Authorization")!!.contains("Token=\"secret\""))
    }

    @Test
    fun aMissingBifIsNotFound() = runTest {
        val http = TestHttp(status = { 404 }) { "" }
        val provider = EmbyProvider(http.client, testIdentity)
        try {
            provider.bif(session("emby", "http://den.local:8096"), "77", "77", 320, null, 1_000L)
            fail("expected a failure")
        } catch (e: ServerException) {
            assertEquals(ServerFailure.NOT_FOUND, e.failure)
        }
    }

    @Test
    fun aWholeBifCutAtTheCapIsMarkedTruncated() = runTest {
        val http = TestHttp { "y".repeat(50) }
        val provider = EmbyProvider(http.client, testIdentity)
        val reply = provider.bif(session("emby", "http://den.local:8096"), "77", "77", 320, 0L until 10L, 20L)
        assertFalse(reply.partial)
        assertTrue(reply.truncated)
        assertEquals(20, reply.bytes.size)
    }
}
