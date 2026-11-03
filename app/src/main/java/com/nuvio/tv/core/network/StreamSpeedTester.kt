package com.nuvio.tv.core.network

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import okhttp3.Request

@UnstableApi
object StreamSpeedTester {

    private const val WARMUP_BYTES = 8L * 1024 * 1024
    private const val WARMUP_MAX_MS = 750L
    private const val MEASURE_BYTES = 64L * 1024 * 1024
    private const val MEASURE_MAX_MS = 8_000L
    private const val SUB_WINDOW_MS = 500L
    private const val MEASURE_MIN_MS = 2_500L

    data class ParallelPassResult(
        val mbps: Double,
        val subWindowMbps: List<Double> = emptyList(),
        val failureReason: String? = null,
        val clampTrips: Int = 0
    )

    suspend fun runBaselineTest(
        url: String,
        headers: Map<String, String>,
        context: android.content.Context
    ): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val source = PlayerPlaybackNetworking.createDataSourceFactory(context, headers).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(url).build())
            val buffer = ByteArray(64 * 1024)
            val warmStart = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - warmStart < 1500) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (source.read(buffer, 0, buffer.size) == -1) return@withContext 0.0
            }
            var bytes = 0L
            val start = android.os.SystemClock.elapsedRealtime()
            while (android.os.SystemClock.elapsedRealtime() - start < 10000) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = source.read(buffer, 0, buffer.size)
                if (count == -1) break
                bytes += count
            }
            bytes * 8.0 / ((android.os.SystemClock.elapsedRealtime() - start).coerceAtLeast(1) * 1000.0)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("StreamSpeedTester", "Single-connection test failed", error)
            0.0
        } finally { runCatching { source.close() } }
    }

    suspend fun runParallelChunkTest(
        url: String,
        headers: Map<String, String>,
        chunkSizeBytes: Long,
        parallelConnections: Int,
        prefetchDepthChunks: Int = parallelConnections + 1
    ): ParallelPassResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val totalBytesDownloaded = java.util.concurrent.atomic.AtomicLong(0L)

        val transferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) {
                    totalBytesDownloaded.addAndGet(bytesTransferred.toLong())
                }
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }

        var openSource: ParallelRangeDataSource? = null
        var sampler: kotlinx.coroutines.Job? = null
        try {
            ParallelRangeDataSource.releaseRetainedSession()
            val okHttpFactory = OkHttpDataSource.Factory(PlayerPlaybackNetworking.parallelHttpClient).apply {
                setDefaultRequestProperties(headers)
            }
            val dataSource = ParallelRangeDataSource(
                upstreamFactory = okHttpFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSizeBytes,
                useNativeMemory = true,
                prefetchDepthChunks = prefetchDepthChunks
            ).apply {
                addTransferListener(transferListener)
            }
            openSource = dataSource
            dataSource.open(DataSpec(android.net.Uri.parse(url)))
            val buffer = ByteArray(64 * 1024)

            var eof = false

            val warmStart = System.currentTimeMillis()
            while (totalBytesDownloaded.get() < WARMUP_BYTES &&
                System.currentTimeMillis() - warmStart < WARMUP_MAX_MS
            ) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == -1) {
                    eof = true
                    break
                }
            }

            val networkAtMeasureStart = totalBytesDownloaded.get()
            val tStart = System.currentTimeMillis()
            val tDeadline = tStart + MEASURE_MAX_MS
            val subWindowMbps = mutableListOf<Double>()
            val samplerJob = launch {
                var wStartMs = System.currentTimeMillis()
                var wStartBytes = totalBytesDownloaded.get()
                while (true) {
                    kotlinx.coroutines.delay(SUB_WINDOW_MS)
                    val now = System.currentTimeMillis()
                    val bytes = totalBytesDownloaded.get()
                    subWindowMbps += ((bytes - wStartBytes) * 8.0) /
                        ((now - wStartMs) * 1000.0)
                    wStartMs = now
                    wStartBytes = bytes
                }
            }
            sampler = samplerJob
            while (!eof &&
                (totalBytesDownloaded.get() - networkAtMeasureStart < MEASURE_BYTES ||
                    System.currentTimeMillis() - tStart < MEASURE_MIN_MS) &&
                System.currentTimeMillis() < tDeadline
            ) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == -1) {
                    eof = true
                }
            }
            val endMs = System.currentTimeMillis()
            samplerJob.cancel()
            samplerJob.join()
            val elapsed = (endMs - tStart).coerceAtLeast(1)
            val networkDelta = totalBytesDownloaded.get() - networkAtMeasureStart
            val clampTrips = ParallelRangeDataSource.hudClampTrips
            dataSource.close()

            return@withContext ParallelPassResult(
                mbps = (networkDelta * 8.0) / (elapsed * 1000.0),
                subWindowMbps = subWindowMbps.toList(),
                clampTrips = clampTrips
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            android.util.Log.e(
                "StreamSpeedTester",
                "Parallel measure failed (${parallelConnections}c/${chunkSizeBytes / (1024L * 1024L)}MB)",
                t
            )
            ParallelRangeDataSource.releaseRetainedSession()
            ParallelRangeDataSource.drainIdleBuffers(chunkSizeBytes)
            val reason = t.javaClass.simpleName +
                (t.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            return@withContext ParallelPassResult(0.0, emptyList(), failureReason = reason)
        } finally {
            sampler?.cancel()
            try {
                openSource?.close()
            } catch (_: Exception) {
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ParallelPassResult(0.0, emptyList())
    }

    suspend fun getStreamContentLength(
        url: String,
        headers: Map<String, String>
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val len = response.headers["Content-Length"]?.toLongOrNull()
                    if (len != null && len > 0) return@withContext len
                }
            }

            val getRequest = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            PlayerPlaybackNetworking.playbackHttpClient.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        return@withContext body.contentLength().coerceAtLeast(0L)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        0L
    }
}
