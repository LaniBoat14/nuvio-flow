package com.nuvio.tv.data.mediaserver.mediabrowser

import com.nuvio.tv.data.mediaserver.ServerBytes
import com.nuvio.tv.data.mediaserver.ServerConnectTimeout
import com.nuvio.tv.data.mediaserver.ServerException
import com.nuvio.tv.data.mediaserver.ServerFailure
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

class ServerClientIdentity(
    val device: String,
    val version: String,
    val deviceId: () -> String
)

internal class MediaBrowserClient(
    private val authorizationHeader: String,
    http: OkHttpClient,
    private val identity: ServerClientIdentity
) {
    private val http = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val limitedHttp = ConcurrentHashMap<Long, OkHttpClient>()

    /** Binary downloads stay on HTTP/1.1 connections of their own, never multiplexed with other calls. */
    private val bytesHttp = this.http.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .callTimeout(BYTES_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    val deviceId: String
        get() = identity.deviceId()

    suspend fun <T> get(
        baseUrl: String,
        path: String,
        deserializer: DeserializationStrategy<T>,
        token: String? = null,
        query: Map<String, String?> = emptyMap()
    ): T = json.decodeFromString(deserializer, execute("GET", baseUrl, path, token, query).body)

    suspend fun execute(
        method: String,
        baseUrl: String,
        path: String,
        token: String?,
        query: Map<String, String?> = emptyMap(),
        body: String? = null,
        allowRedirect: Boolean = false,
        authorization: String? = authorizationValue(token),
        headers: Map<String, String> = emptyMap()
    ): ApiResponse {
        val request = try {
            Request.Builder()
                .url(buildUrl(baseUrl, path, query))
                .header("Accept", "application/json")
                .apply { if (authorization != null) header(authorizationHeader, authorization) }
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .method(
                    method,
                    body?.toRequestBody(JSON_MEDIA_TYPE) ?: if (method == "POST") ByteArray(0).toRequestBody() else null
                )
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val response = try {
            send(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: SSLPeerUnverifiedException) {
            throw ServerException(ServerFailure.CERTIFICATE)
        } catch (_: SSLHandshakeException) {
            throw ServerException(ServerFailure.CERTIFICATE)
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE, network = true)
        }
        if (allowRedirect && response.status in 300..399) return response
        throwForStatus(response.status, response.body)
        return response
    }

    fun authHeaders(token: String): Map<String, String> = mapOf(authorizationHeader to authorizationValue(token))

    fun authorizationValue(token: String?): String = buildString {
        append("MediaBrowser Client=\"Nuvio\", Device=\"")
        append(identity.device.headerValue())
        append("\", DeviceId=\"")
        append(deviceId.headerValue())
        append("\", Version=\"")
        append(identity.version.headerValue())
        append('"')
        if (token != null) {
            append(", Token=\"")
            append(token.headerValue())
            append('"')
        }
    }

    /**
     * A binary GET signed like every API call, or unsigned when the link carries its own permission ([signed] false).
     * A body over [maxBytes] is cut there and marked truncated.
     */
    suspend fun bytes(url: String, token: String?, maxBytes: Long, range: LongRange? = null, signed: Boolean = true): ServerBytes {
        val request = try {
            Request.Builder()
                .url(url)
                .apply { if (signed) header(authorizationHeader, authorizationValue(token)) }
                .apply { if (range != null) header("Range", "bytes=${range.first}-${range.last}") }
                .build()
        } catch (_: IllegalArgumentException) {
            throw ServerException(ServerFailure.NOT_FOUND)
        }
        val (status, reply) = try {
            send(bytesHttp, request) { response ->
                val read = if (response.code in 200..299) {
                    readBoundedBytes(response.body.source(), response.body.contentLength(), maxBytes)
                } else {
                    ByteArray(0) to false
                }
                response.code to ServerBytes(
                    bytes = read.first,
                    partial = response.code == 206,
                    totalLength = totalLength(response),
                    truncated = read.second
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SSLPeerUnverifiedException) {
            throw ServerException(ServerFailure.CERTIFICATE)
        } catch (_: SSLHandshakeException) {
            throw ServerException(ServerFailure.CERTIFICATE)
        } catch (_: Exception) {
            throw ServerException(ServerFailure.UNREACHABLE, network = true)
        }
        throwForStatus(status, "")
        return reply
    }

    private fun totalLength(response: Response): Long? =
        if (response.code == 206) {
            response.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
        } else {
            response.body.contentLength().takeIf { it >= 0 }
        }

    private suspend fun send(request: Request): ApiResponse =
        send(request) { response ->
            val body = response.body?.let { readBoundedBody(it.source(), it.contentLength()) }.orEmpty()
            ApiResponse(response.code, body, response.header("Location"), response.header("Retry-After")?.trim()?.toLongOrNull())
        }

    private suspend fun <T> send(request: Request, read: (Response) -> T): T {
        val client = currentCoroutineContext()[ServerConnectTimeout]?.millis?.let { millis ->
            limitedHttp.getOrPut(millis) { http.newBuilder().connectTimeout(millis, TimeUnit.MILLISECONDS).build() }
        } ?: http
        return send(client, request, read)
    }

    private suspend fun <T> send(client: OkHttpClient, request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val value = read(response)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun throwForStatus(status: Int, body: String) {
        val failure = when {
            status in 200..299 -> return
            status == 401 -> ServerFailure.AUTH_REQUIRED
            status == 403 -> ServerFailure.FORBIDDEN
            status == 404 -> ServerFailure.NOT_FOUND
            status in 500..599 -> ServerFailure.UNREACHABLE
            else -> ServerFailure.FAILED
        }
        throw ServerException(failure, "HTTP $status", detail = errorDetail(body), httpStatus = status, code = problemCode(body))
    }

    /** The last part of a problem body's type, the way Silo names its errors. */
    private fun problemCode(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject["type"]?.jsonPrimitive?.contentOrNull
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun errorDetail(body: String): String? = try {
        val reply = json.parseToJsonElement(body).jsonObject
        ERROR_TEXT_KEYS.firstNotNullOfOrNull { key -> reply[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
    } catch (_: Exception) {
        null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val ERROR_TEXT_KEYS = listOf("Message", "detail")
        const val BYTES_CALL_TIMEOUT_SECONDS = 180L
    }
}

internal class ApiResponse(
    val status: Int,
    val body: String,
    val location: String?,
    val retryAfterSeconds: Long? = null
) {
    override fun toString(): String = "ApiResponse(status=$status)"
}

internal fun buildUrl(baseUrl: String, path: String, query: Map<String, String?> = emptyMap()): String {
    require(path.startsWith('/'))
    val base = requireNotNull(baseUrl.toHttpUrlOrNull())
    return base.newBuilder()
        .encodedPath(base.encodedPath.trimEnd('/') + path)
        .query(null)
        .fragment(null)
        .apply { query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) } }
        .build()
        .toString()
}

internal fun pathSegment(value: String): String =
    HttpUrl.Builder().scheme("http").host("h").addPathSegment(value).build().encodedPath.removePrefix("/")

internal fun dropsEncryption(from: String, to: String): Boolean =
    from.startsWith("https://", ignoreCase = true) && !to.startsWith("https://", ignoreCase = true)

internal fun normalizeServerAddress(input: String, apiPath: String = ""): String? {
    var value = input.trim().trimEnd('/')
    if (value.isEmpty()) return null
    if (!value.contains("://")) value = "http://$value"
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.host.isBlank() || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    val path = url.encodedPath.trimEnd('/')
        .removeSuffix("/web/index.html")
        .removeSuffix("/web")
        .trimEnd('/')
        .let { if (apiPath.isNotEmpty() && it.endsWith(apiPath, ignoreCase = true)) it.dropLast(apiPath.length) else it }
    return url.newBuilder()
        .encodedPath(path.ifEmpty { "/" })
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

private fun readBoundedBody(source: BufferedSource, contentLength: Long, maxBytes: Long = 16L * 1024L * 1024L): String {
    if (contentLength > maxBytes) throw IOException("Response exceeds size limit")
    val buffer = Buffer()
    while (true) {
        val count = source.read(buffer, minOf(8_192L, maxBytes - buffer.size + 1L))
        if (count == -1L) return buffer.readUtf8()
        if (buffer.size > maxBytes) throw IOException("Response exceeds size limit")
    }
}

/** The body up to [maxBytes], read straight into one array when its length is known. True when cut short. */
private fun readBoundedBytes(source: BufferedSource, contentLength: Long, maxBytes: Long): Pair<ByteArray, Boolean> {
    if (contentLength >= 0) {
        val bytes = ByteArray(minOf(contentLength, maxBytes).toInt())
        source.readFully(bytes)
        return bytes to (contentLength > maxBytes)
    }
    val buffer = Buffer()
    while (buffer.size < maxBytes) {
        if (source.read(buffer, minOf(65_536L, maxBytes - buffer.size)) == -1L) return buffer.readByteArray() to false
    }
    return buffer.readByteArray() to !source.exhausted()
}

private fun String.headerValue(): String = filter { it != '"' && it != '\r' && it != '\n' }
