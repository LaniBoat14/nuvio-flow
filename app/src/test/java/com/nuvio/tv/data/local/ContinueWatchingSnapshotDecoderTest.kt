package com.nuvio.tv.data.local

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ContinueWatchingSnapshotDecoderTest {
    private val gson = Gson()
    private val valid = """{"contentId":"tt123","contentType":"movie","name":"Example","videoId":"tt123","position":100,"duration":1000}"""

    @Test fun invalidEntriesDoNotDiscardTheValidNeighbour() {
        val json = """[null,{},42,{"contentId":null},{"a":"old-obfuscated-id"},$valid]"""
        val items = ContinueWatchingSnapshotDecoder.decode(json, CachedInProgressItem::class.java, gson)
        assertEquals(1, items.size)
        assertEquals("tt123", items.single().contentId)
        assertEquals(100L, items.single().position)
        assertTrue(items.single().genres.isEmpty())
        assertTrue(items.single().ratingVisible)
    }

    @Test fun missingBlankAndWronglyTypedIdsAreRejected() {
        for (replacement in listOf("null", "\"\"", "\"  \"", "123", "{}")) {
            val bad = valid.replace("\"contentId\":\"tt123\"", "\"contentId\":$replacement")
            assertTrue(ContinueWatchingSnapshotDecoder.decode("[$bad]", CachedInProgressItem::class.java, gson).isEmpty())
        }
    }

    @Test fun nextUpDefaultsAndGenreElementsAreSafe() {
        val item = valid.dropLast(1) + """, "genres":[null,2,"Drama"],"ratingVisible":null}"""
        val items = ContinueWatchingSnapshotDecoder.decode("[$item]", CachedNextUpItem::class.java, gson)
        assertEquals(listOf("Drama"), items.single().genres)
        assertTrue(items.single().hasAired)
        assertTrue(items.single().ratingVisible)
    }

    @Test fun snapshotsRoundTripWithStableNamedFields() {
        val first = ContinueWatchingSnapshotDecoder.decode("[$valid]", CachedInProgressItem::class.java, gson)
        val second = ContinueWatchingSnapshotDecoder.decode(gson.toJson(first), CachedInProgressItem::class.java, gson)
        assertEquals(first, second)
        assertTrue(gson.toJson(first).contains("\"contentId\""))
    }

    @Test fun nonArraySnapshotsAreIgnored() {
        assertTrue(ContinueWatchingSnapshotDecoder.decode("null", CachedInProgressItem::class.java, gson).isEmpty())
        assertTrue(ContinueWatchingSnapshotDecoder.decode("{}", CachedNextUpItem::class.java, gson).isEmpty())
    }
}
