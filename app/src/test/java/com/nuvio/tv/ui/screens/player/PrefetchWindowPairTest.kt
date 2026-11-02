package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrefetchWindowPairTest {
    private val film = 19_717_426_246L
    private val errorCard = 417_231L
    private val tailStart = film - PrefetchWindowStore.TAIL_WINDOW_BYTES
    private val uri = mockk<Uri>()
    private val identity = PlaybackPrewarmIdentity.from("https://comet.example/playback/film", emptyMap())

    @Before fun setUp() {
        mockkObject(PlayerPlaybackNetworking)
        every { PlayerPlaybackNetworking.canReusePrewarmFor(any(), any()) } returns true
        PrefetchWindowStore.clear()
    }

    @After fun tearDown() {
        PrefetchWindowStore.clear()
        unmockkAll()
    }

    private fun entry(start: Long, total: Long, size: Int) = ParallelRangeDataSource.BootstrapCacheEntry(
        requestUri = uri,
        startPosition = start,
        resolvedUri = null,
        openLength = total - start,
        totalFileLength = total,
        bootstrapData = ByteArray(size),
        bootstrapSize = size,
        createdAtUptimeMs = 0L,
        prewarmIdentity = identity
    )

    private fun head(total: Long) = entry(0L, total, 1024)
    private fun tail(start: Long, total: Long) = entry(start, total, 1024)
    private fun headSpec() = DataSpec.Builder().setUri(uri).build()

    @Test fun `matching head and tail are both used`() {
        assertTrue(PrefetchWindowStore.putTail(tail(tailStart, film)))
        assertTrue(PrefetchWindowStore.putHead(head(film)))
        assertNotNull(PrefetchWindowStore.peekTail(uri, tailStart + 10, identity, film))
        assertEquals(film, PrefetchWindowStore.consumeHead(headSpec(), identity)?.totalFileLength)
    }

    @Test fun `a suffix answered with a whole small file is never stored`() {
        assertFalse(PrefetchWindowStore.putTail(tail(0L, errorCard)))
        assertNull(PrefetchWindowStore.peekTail(uri, 0L, identity, film))
        assertNull(PrefetchWindowStore.peekTail(uri, 6_840L, identity, errorCard))
    }

    @Test fun `a tail from a file of another length is never served for the film`() {
        val other = 3_000_000_000L
        assertTrue(PrefetchWindowStore.putTail(tail(other - PrefetchWindowStore.TAIL_WINDOW_BYTES, other)))
        assertNull(PrefetchWindowStore.peekTail(uri, other - 100, identity, film))
        // Dropped on the mismatch, so the right length cannot pick it up later either.
        assertNull(PrefetchWindowStore.peekTail(uri, other - 100, identity, other))
    }

    @Test fun `a head arriving after a tail of another length drops the pair`() {
        val other = 3_000_000_000L
        assertTrue(PrefetchWindowStore.putTail(tail(other - PrefetchWindowStore.TAIL_WINDOW_BYTES, other)))
        assertFalse(PrefetchWindowStore.putHead(head(film)))
        assertNull(PrefetchWindowStore.consumeHead(headSpec(), identity))
        assertNull(PrefetchWindowStore.peekTail(uri, other - 100, identity, other))
    }

    @Test fun `a tail arriving after its head was taken is checked against that head`() {
        assertTrue(PrefetchWindowStore.putHead(head(film)))
        assertNotNull(PrefetchWindowStore.consumeHead(headSpec(), identity))
        val other = 3_000_000_000L
        assertFalse(PrefetchWindowStore.putTail(tail(other - PrefetchWindowStore.TAIL_WINDOW_BYTES, other)))
        assertTrue(PrefetchWindowStore.putTail(tail(tailStart, film)))
        assertNotNull(PrefetchWindowStore.peekTail(uri, tailStart, identity, film))
    }

    @Test fun `a new selection forgets the earlier head length`() {
        assertTrue(PrefetchWindowStore.putHead(head(film)))
        PrefetchWindowStore.clear()
        val other = 3_000_000_000L
        assertTrue(PrefetchWindowStore.putTail(tail(other - PrefetchWindowStore.TAIL_WINDOW_BYTES, other)))
    }
}
