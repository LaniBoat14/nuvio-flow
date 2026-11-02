package com.nuvio.tv.data.mediaserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSourceLogTest {
    private fun connection(
        enabled: Boolean = true,
        libraries: List<ServerLibrary> = listOf(ServerLibrary("m", "Movies", ServerMediaKind.MOVIE))
    ) = ServerConnection(
        id = "c1",
        providerId = "emby",
        name = "Home",
        address = "https://emby.example",
        remoteServerId = "s1",
        remoteUserId = "u1",
        userName = "viewer",
        credentialRef = "k1",
        libraries = libraries,
        enabled = enabled
    )

    @Test
    fun askedWhenALibraryForTheKindIsTicked() {
        assertNull(serverSourceSkipReason(connection(), ServerMediaKind.MOVIE, canLookUpIds = true))
    }

    @Test
    fun mixedLibraryCountsForMoviesAndSeries() {
        val mixed = connection(libraries = listOf(ServerLibrary("x", "All", ServerMediaKind.MIXED)))
        assertNull(serverSourceSkipReason(mixed, ServerMediaKind.SERIES, canLookUpIds = true))
    }

    @Test
    fun skipReasons() {
        assertEquals("turned_off", serverSourceSkipReason(connection(enabled = false), ServerMediaKind.MOVIE, true))
        assertEquals("no_title_ids", serverSourceSkipReason(connection(), null, true))
        assertEquals("no_library", serverSourceSkipReason(connection(), ServerMediaKind.SERIES, true))
        assertEquals("no_id_lookup", serverSourceSkipReason(connection(), ServerMediaKind.MOVIE, false))
    }

    @Test
    fun untickedLibrariesAreStillAsked() {
        val unticked = connection(libraries = listOf(ServerLibrary("m", "Movies", ServerMediaKind.MOVIE, selected = false)))
        assertNull(serverSourceSkipReason(unticked, ServerMediaKind.MOVIE, canLookUpIds = true))
        val mixed = connection(libraries = listOf(ServerLibrary("x", "All", ServerMediaKind.MIXED, selected = false)))
        assertNull(serverSourceSkipReason(mixed, ServerMediaKind.SERIES, canLookUpIds = true))
        assertEquals("turned_off", serverSourceSkipReason(unticked.copy(enabled = false), ServerMediaKind.MOVIE, true))
    }

    @Test
    fun librariesHoldingIgnoresTheTicks() {
        val unticked = connection(
            libraries = listOf(
                ServerLibrary("m", "Movies", ServerMediaKind.MOVIE, selected = false),
                ServerLibrary("x", "All", ServerMediaKind.MIXED, selected = false),
                ServerLibrary("s", "Shows", ServerMediaKind.SERIES)
            )
        )
        assertEquals(
            listOf("m" to ServerMediaKind.MOVIE, "x" to ServerMediaKind.MOVIE),
            unticked.librariesHolding(ServerMediaKind.MOVIE).map { it.id to it.kind }
        )
        assertEquals(listOf("x", "s"), unticked.librariesHolding(ServerMediaKind.SERIES).map { it.id })
        assertEquals(listOf("s"), unticked.selectedLibraries.map { it.id })
    }

    @Test
    fun outcomesWithoutError() {
        assertEquals("found", serverSourceOutcome(3, matchedItems = 1, error = null, timedOut = false))
        assertEquals("no_match", serverSourceOutcome(0, matchedItems = 0, error = null, timedOut = false))
        assertEquals("no_streams", serverSourceOutcome(0, matchedItems = 1, error = null, timedOut = false))
        assertEquals("timeout", serverSourceOutcome(0, matchedItems = null, error = null, timedOut = true))
    }

    @Test
    fun outcomesWithError() {
        assertEquals("signed_out", serverSourceOutcome(0, null, ServerException(ServerFailure.AUTH_REQUIRED), false))
        assertEquals("unreachable", serverSourceOutcome(0, null, ServerException(ServerFailure.UNREACHABLE), false))
        assertEquals("unreachable", serverSourceOutcome(0, null, ServerException(ServerFailure.CERTIFICATE), false))
        assertEquals("unreachable", serverSourceOutcome(0, null, ServerException(ServerFailure.FAILED, network = true), false))
        assertEquals("failed", serverSourceOutcome(0, null, ServerException(ServerFailure.INCOMPLETE), false))
        assertEquals("failed", serverSourceOutcome(0, null, IllegalStateException("x"), false))
    }

    @Test
    fun logLineNamesTheServerAndTheErrorClass() {
        assertEquals(
            "SERVER_SOURCE name=Emby · Home ms=120 streams=0 outcome=failed error=SocketTimeoutException",
            serverSourceLogLine("Emby · Home", 120, 0, "failed", java.net.SocketTimeoutException("read"))
        )
        assertEquals(
            "SERVER_SOURCE name=Silo · Vault ms=900 streams=0 outcome=unreachable error=UNREACHABLE http 502 network",
            serverSourceLogLine(
                "Silo · Vault", 900, 0, "unreachable",
                ServerException(ServerFailure.UNREACHABLE, network = true, httpStatus = 502)
            )
        )
        assertEquals(
            "SERVER_SOURCE name=Silo · Vault ms=40 streams=3 outcome=found",
            serverSourceLogLine("Silo · Vault", 40, 3, "found", null)
        )
    }
}
