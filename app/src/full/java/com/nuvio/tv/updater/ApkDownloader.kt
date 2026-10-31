package com.nuvio.tv.updater

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    // APKs must not share the API disk cache. Bound the whole operation as well
    // as stalled reads, so a queued foreground retry cannot wait indefinitely.
    private val downloadClient = okHttpClient.newBuilder()
        .cache(null)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun download(
        url: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        return try {
            Result.success(run {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()

            val request = Request.Builder()
                .url(url)
                .build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        check(downloaded > 0 && (total == null || downloaded == total)) { "Incomplete APK download" }
                        output.flush()
                    }
                }
            }

            destinationFile
            })
        } catch (cancelled: CancellationException) {
            destinationFile.delete()
            throw cancelled
        } catch (error: Exception) {
            destinationFile.delete()
            Result.failure(error)
        }
    }
}
