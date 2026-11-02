package com.nuvio.tv.core.image

import android.content.Context
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toUri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SavedCopyFirstTest {
    private val context = mockk<Context>(relaxed = true)
    private val url = "https://posters.example/p.jpg".toUri()

    private fun withCache(block: suspend TestScope.(DiskCache, ImageLoader) -> Unit) = runTest {
        val dir = kotlin.io.path.createTempDirectory("saved-first").toFile()
        try {
            val cache = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(1L shl 20).build()
            cache.openEditor("key")!!.apply {
                cache.fileSystem.write(data) { writeUtf8("old") }
                cache.fileSystem.write(metadata) {}
                commit()
            }
            block(cache, mockk { every { diskCache } returns cache })
            cache.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun network(onFetch: () -> Unit) = object : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = object : Fetcher {
            override suspend fun fetch() = onFetch().let { null }
        }
    }

    @Test fun aSavedPictureDueForACheckIsShownAtOnceAndCheckedAfter() = withCache { cache, loader ->
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val factory = SavedCopyOnErrorFetcher.Factory(network { online++ }, checks) { _, _, _, _ -> false }
        val result = factory.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch() as SourceFetchResult
        assertEquals(DataSource.DISK, result.dataSource)
        assertEquals("old", result.source.source().readUtf8())
        result.source.close()
        assertEquals(0, online)
        testScheduler.runCurrent()
        assertEquals(1, online)
        factory.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch().let { (it as SourceFetchResult).source.close() }
        testScheduler.runCurrent()
        assertEquals("checked once per cooldown", 1, online)
        assertTrue(cache.openSnapshot("key")!!.use { true })
    }

    @Test fun aFreshSavedPictureGoesThroughTheNormalPath() = withCache { _, loader ->
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val fresh = SavedCopyOnErrorFetcher.Factory(network { online++ }, checks) { _, _, _, _ -> true }
        fresh.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch()
        assertEquals(1, online)
        testScheduler.runCurrent()
        assertEquals("no background check", 1, online)
    }

    @Test fun aMissingSavedPictureGoesOnline() = withCache { _, loader ->
        var online = 0
        val checks = SavedImageChecks(TestScope(StandardTestDispatcher(testScheduler)))
        val due = SavedCopyOnErrorFetcher.Factory(network { online++ }, checks) { _, _, _, _ -> false }
        due.create(url, Options(context, diskCacheKey = "other"), loader)!!.fetch()
        assertEquals(1, online)
    }

    @Test fun onlyNoCacheOrMaxAgeZeroAskForTheServerFirst() {
        assertTrue(onlineCheckRequested("no-cache"))
        assertTrue(onlineCheckRequested("max-age=0"))
        assertFalse(onlineCheckRequested("max-age=3600"))
        assertFalse(onlineCheckRequested(null))
    }
}
