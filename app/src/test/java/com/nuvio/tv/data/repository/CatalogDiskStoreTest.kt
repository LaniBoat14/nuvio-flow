package com.nuvio.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDiskStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L
    private val minute = 60_000L

    private fun TestScope.store(
        dir: File,
        legacy: File = File(dir.parentFile, "catalog_rows_v1.json"),
        maxEntryBytes: Long = 1_000,
        maxTotalBytes: Long = 100_000,
        maxEntries: Int = 128,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    ) = CatalogDiskStore(
        dir = dir,
        legacyFiles = listOf(legacy),
        scope = scope,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { now },
        maxEntryBytes = maxEntryBytes,
        maxTotalBytes = maxTotalBytes,
        maxEntries = maxEntries,
        maxAgeMs = 7L * 24 * 60 * 60 * 1000,
        debounceMs = 2_000
    )

    private fun CatalogDiskStore.putText(key: String, text: String, onEncode: () -> Unit = {}) =
        put(key, text.length) { sink ->
            onEncode()
            sink.writeUtf8(text)
        }

    private suspend fun CatalogDiskStore.readText(key: String): String? = read(key) { it.readUtf8() }

    private fun catFiles(dir: File) = dir.listFiles { f -> f.name.endsWith(".cat") }.orEmpty().toList()

    @Test
    fun `round trip survives a new store on the same folder`() = runTest {
        val dir = File(tmp.root, "rows")
        val first = store(dir)
        first.putText("https://addon.example/catalog/movie/top.json", """{"metas":[]}""")
        advanceUntilIdle()

        assertEquals("""{"metas":[]}""", first.readText("https://addon.example/catalog/movie/top.json"))
        assertEquals(1, catFiles(dir).size)
        assertFalse(catFiles(dir).single().name.contains("addon"))

        val second = store(dir)
        assertEquals("""{"metas":[]}""", second.readText("https://addon.example/catalog/movie/top.json"))
        assertNull(second.readText("https://addon.example/catalog/series/top.json"))
    }

    @Test
    fun `body above the size cap is not stored and stops being encoded`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir, maxEntryBytes = 100)
        store.putText("small-then-big", "x".repeat(50))
        advanceUntilIdle()
        assertEquals(1, catFiles(dir).size)

        var encodes = 0
        store.putText("small-then-big", "y".repeat(5_000)) { encodes++ }
        advanceUntilIdle()
        assertEquals(1, encodes)
        assertNull(store.readText("small-then-big"))
        assertTrue(catFiles(dir).isEmpty())

        store.putText("small-then-big", "y".repeat(5_000)) { encodes++ }
        advanceUntilIdle()
        assertEquals(1, encodes)

        store.putText("small-then-big", "z".repeat(20)) { encodes++ }
        advanceUntilIdle()
        assertEquals(2, encodes)
        assertEquals("z".repeat(20), store.readText("small-then-big"))
    }

    @Test
    fun `total cap evicts the least recently refreshed entry`() = runTest {
        val dir = File(tmp.root, "rows")
        val body = "b".repeat(100)
        val fileBytes = 72L + body.length
        val store = store(dir, maxTotalBytes = fileBytes * 3)

        for (key in listOf("a", "b", "c")) {
            store.putText(key, body)
            advanceUntilIdle()
            now += 11 * minute
        }
        store.putText("a", body)
        advanceUntilIdle()
        now += 11 * minute
        store.putText("d", body)
        advanceUntilIdle()

        assertEquals(body, store.readText("a"))
        assertNull(store.readText("b"))
        assertEquals(body, store.readText("c"))
        assertEquals(body, store.readText("d"))
        assertEquals(3, catFiles(dir).size)
        assertTrue(catFiles(dir).sumOf { it.length() } <= fileBytes * 3)
    }

    @Test
    fun `rows refreshed in the last ten minutes are not evicted for a new row`() = runTest {
        val dir = File(tmp.root, "rows")
        val body = "b".repeat(100)
        val fileBytes = 72L + body.length
        val store = store(dir, maxTotalBytes = fileBytes * 3)
        for (key in listOf("a", "b", "c")) {
            store.putText(key, body)
            advanceUntilIdle()
            now += minute
        }

        var encodes = 0
        store.putText("d", body) { encodes++ }
        advanceUntilIdle()
        assertEquals(1, encodes)
        assertEquals(3, store.filesWritten.get())
        assertNull(store.readText("d"))
        for (key in listOf("a", "b", "c")) assertEquals(body, store.readText(key))

        store.putText("d", body) { encodes++ }
        advanceUntilIdle()
        assertEquals(1, encodes)

        now += 10 * minute
        store.putText("e", body)
        advanceUntilIdle()
        assertEquals(body, store.readText("e"))
        assertNull(store.readText("a"))
        assertEquals(body, store.readText("b"))
        assertEquals(3, catFiles(dir).size)
    }

    @Test
    fun `entry limit evicts the oldest entry`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir, maxEntries = 2)
        for (key in listOf("a", "b", "c")) {
            store.putText(key, key)
            advanceUntilIdle()
            now += 11 * minute
        }
        assertNull(store.readText("a"))
        assertEquals("b", store.readText("b"))
        assertEquals("c", store.readText("c"))
    }

    @Test
    fun `old single file is deleted without being parsed`() = runTest {
        val dir = File(tmp.root, "rows")
        val legacy = File(tmp.root, "catalog_rows_v1.json")
        legacy.writeText("{ not json at all")
        val store = store(dir, legacy = legacy)

        store.warm()

        assertFalse(legacy.exists())
        assertNull(store.readText("anything"))
    }

    @Test
    fun `unchanged body is not rewritten but its age is refreshed`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir)
        store.putText("k", "same body")
        advanceUntilIdle()
        assertEquals(1, store.filesWritten.get())

        now += 6L * 24 * 60 * 60 * 1000
        store.putText("k", "same body")
        advanceUntilIdle()
        assertEquals(1, store.filesWritten.get())
        assertEquals(now, catFiles(dir).single().lastModified())

        val restarted = store(dir)
        now += 6L * 24 * 60 * 60 * 1000
        assertEquals("same body", restarted.readText("k"))
        restarted.putText("k", "same body")
        advanceUntilIdle()
        assertEquals(0, restarted.filesWritten.get())

        restarted.putText("k", "new body")
        advanceUntilIdle()
        assertEquals(1, restarted.filesWritten.get())
        assertEquals("new body", restarted.readText("k"))
    }

    @Test
    fun `entry older than the age limit is dropped`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir)
        store.putText("k", "body")
        advanceUntilIdle()

        now += 7L * 24 * 60 * 60 * 1000 + 1
        assertNull(store.readText("k"))
        assertTrue(catFiles(dir).isEmpty())
    }

    @Test
    fun `writes for the same key within the window coalesce to the latest`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir)
        val encoded = mutableListOf<String>()
        listOf("one", "two", "three").forEach { body ->
            store.putText("k", body) { encoded += body }
        }
        store.putText("other", "x")
        advanceUntilIdle()

        assertEquals(listOf("three"), encoded)
        assertEquals(2, store.filesWritten.get())
        assertEquals("three", store.readText("k"))
        assertEquals("x", store.readText("other"))
    }

    @Test
    fun `corrupt file reads as a miss and is removed`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir)
        store.putText("k", "body")
        advanceUntilIdle()
        catFiles(dir).single().writeText("garbage")

        assertNull(store.readText("k"))
        assertTrue(catFiles(dir).isEmpty())
    }

    @Test
    fun `damaged body behind a good header reads as a miss and is removed`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir)
        store.putText("k", "body one")
        advanceUntilIdle()
        val file = catFiles(dir).single()
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = 'X'.code.toByte()
        file.writeBytes(bytes)

        assertNull(store.readText("k"))
        assertTrue(catFiles(dir).isEmpty())
    }

    @Test
    fun `clear removes every entry`() = runTest {
        val dir = File(tmp.root, "rows")
        val store = store(dir)
        store.putText("a", "1")
        store.putText("b", "2")
        advanceUntilIdle()
        assertEquals(2, catFiles(dir).size)

        store.clear()

        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertNull(store.readText("a"))
    }
}
