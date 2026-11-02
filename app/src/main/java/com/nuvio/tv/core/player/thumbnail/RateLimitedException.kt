package com.nuvio.tv.core.player.thumbnail

import java.io.IOException

internal class RateLimitedException(
    val retryAfterMs: Long?,
    val status: Int = TOO_MANY_REQUESTS,
    val url: String? = null,
) : IOException("rate limited") {
    companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
