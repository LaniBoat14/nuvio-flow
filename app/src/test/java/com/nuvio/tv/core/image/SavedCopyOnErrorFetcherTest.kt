package com.nuvio.tv.core.image

import android.content.Context
import coil3.Image
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.network.HttpException
import coil3.network.NetworkResponse
import coil3.request.CachePolicy
import coil3.request.Options
import coil3.toUri
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SavedCopyOnErrorFetcherTest {
    private val saved = ImageFetchResult(mockk<Image>(relaxed = true), false, DataSource.DISK)
    private val network = ImageFetchResult(mockk<Image>(relaxed = true), false, DataSource.NETWORK)

    private fun fetcher(block: suspend () -> FetchResult?) = object : Fetcher {
        override suspend fun fetch(): FetchResult? = block()
    }

    private fun failing(error: Exception) = fetcher { throw error }

    private suspend fun expectThrows(fetcher: Fetcher, type: Class<out Throwable>) {
        try {
            fetcher.fetch()
            fail("expected ${type.simpleName}")
        } catch (e: Throwable) {
            assertTrue(type.isInstance(e))
        }
    }

    @Test fun failedOnlineCheckShowsTheSavedCopy() = runTest {
        val before = SavedCopyOnErrorFetcher.savedCopiesServed.get()
        listOf(
            java.net.SocketTimeoutException(),
            java.net.UnknownHostException(),
            HttpException(NetworkResponse(code = 503)),
            HttpException(NetworkResponse(code = 504)),
            HttpException(NetworkResponse(code = 429)),
        ).forEach { error ->
            assertSame(saved, SavedCopyOnErrorFetcher(failing(error)) { saved }.fetch())
        }
        assertEquals(before + 5, SavedCopyOnErrorFetcher.savedCopiesServed.get())
    }

    @Test fun successfulFetchNeverOpensTheSavedCopy() = runTest {
        var opened = false
        val result = SavedCopyOnErrorFetcher(fetcher { network }) { opened = true; saved }.fetch()
        assertSame(network, result)
        assertFalse(opened)
    }

    @Test fun missingSavedCopyKeepsTheOriginalError() = runTest {
        expectThrows(SavedCopyOnErrorFetcher(failing(java.io.IOException())) { null }, java.io.IOException::class.java)
        expectThrows(SavedCopyOnErrorFetcher(failing(java.io.IOException())) { error("disk") }, java.io.IOException::class.java)
    }

    @Test fun permanentAnswersAndCancellationAreNotHidden() = runTest {
        var opened = false
        val open: () -> FetchResult? = { opened = true; saved }
        expectThrows(SavedCopyOnErrorFetcher(failing(HttpException(NetworkResponse(code = 404))), openSavedCopy = open), HttpException::class.java)
        expectThrows(SavedCopyOnErrorFetcher(failing(IllegalStateException()), openSavedCopy = open), IllegalStateException::class.java)
        expectThrows(SavedCopyOnErrorFetcher(failing(CancellationException()), openSavedCopy = open), CancellationException::class.java)
        assertFalse(opened)
    }

    @Test fun cancelledLoadIsNotTurnedIntoASavedCopy() = runTest {
        var opened = false
        var thrown: Throwable? = null
        launch {
            coroutineContext.job.cancel()
            try {
                SavedCopyOnErrorFetcher(failing(java.io.IOException("Canceled"))) { opened = true; saved }.fetch()
            } catch (e: Throwable) {
                thrown = e
            }
        }.join()
        assertTrue(thrown is CancellationException)
        assertFalse(opened)
    }

    @Test fun factoryShowsTheFileSavedUnderTheRequestKeyAndReleasesIt() = runTest {
        val dir = kotlin.io.path.createTempDirectory("saved").toFile()
        try {
            val cache = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(1L shl 20).build()
            cache.openEditor("key")!!.apply {
                cache.fileSystem.write(data) { writeUtf8("jpeg") }
                cache.fileSystem.write(metadata) {}
                commit()
            }
            val loader = mockk<ImageLoader> { every { diskCache } returns cache }
            val network = object : Fetcher.Factory<Uri> {
                override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher =
                    failing(java.net.SocketTimeoutException())
            }
            val factory = SavedCopyOnErrorFetcher.Factory(network)
            val url = "https://img.example/p.jpg".toUri()
            val context = mockk<Context>(relaxed = true)

            val result = factory.create(url, Options(context, diskCacheKey = "key"), loader)!!.fetch() as SourceFetchResult
            assertEquals(DataSource.DISK, result.dataSource)
            assertNull(cache.openEditor("key"))
            assertEquals("jpeg", result.source.source().readUtf8())
            result.source.close()
            val editor = cache.openEditor("key")
            assertNotNull(editor)
            editor!!.abort()

            val noDiskRead = Options(context, diskCacheKey = "key", diskCachePolicy = CachePolicy.WRITE_ONLY)
            expectThrows(factory.create(url, noDiskRead, loader)!!, java.net.SocketTimeoutException::class.java)
            cache.shutdown()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun transientFailureRules() {
        assertTrue(isTransientImageFailure(java.net.ConnectException()))
        assertTrue(isTransientImageFailure(HttpException(NetworkResponse(code = 500))))
        assertTrue(isTransientImageFailure(HttpException(NetworkResponse(code = 408))))
        assertFalse(isTransientImageFailure(HttpException(NetworkResponse(code = 403))))
        assertFalse(isTransientImageFailure(RuntimeException()))
    }
}
