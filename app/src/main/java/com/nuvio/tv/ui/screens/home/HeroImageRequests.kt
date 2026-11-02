package com.nuvio.tv.ui.screens.home

import android.content.Context
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale

/** The compositor and neighbour prefetch share dimensions, URL sizing and cache identity. */
internal object HeroImageRequests {
    @Volatile var width: Int = 0
    @Volatile var height: Int = 0

    fun backdrop(context: Context, url: String, width: Int, height: Int): ImageRequest.Builder {
        val source = heroTransferUrl(url, width)
        return ImageRequest.Builder(context).data(source)
            .memoryCacheKey("home-hero:$source:${width}x$height")
            .size(width, height).scale(Scale.FILL).crossfade(false)
    }

    fun logo(context: Context, url: String): ImageRequest.Builder {
        val density = context.resources.displayMetrics.density
        return ImageRequest.Builder(context).data(url)
            .memoryCacheKey(com.nuvio.tv.core.image.titleLogoCacheKey(url))
            .size((220 * density).toInt().coerceAtLeast(1), (100 * density).toInt().coerceAtLeast(1))
            .scale(Scale.FIT).crossfade(false)
    }
}
