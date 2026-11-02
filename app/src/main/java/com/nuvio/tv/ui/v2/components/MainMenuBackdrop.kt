package com.nuvio.tv.ui.v2.components

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.graphics.get
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** Home's full-screen hero names the picture the open main menu sits on. */
@Stable
class MainMenuBackdrop {
    var artworkUrl by mutableStateOf<String?>(null)
}

val LocalMainMenuBackdrop = staticCompositionLocalOf<MainMenuBackdrop?> { null }

/** Assumed brightness when no hero picture is known: the page backgrounds are dark. */
const val MAIN_MENU_DEFAULT_LUMINANCE = 0.2f

/** Brightness before the first sample of a new picture arrives; errs on the readable side. */
const val MAIN_MENU_UNKNOWN_PICTURE_LUMINANCE = 0.6f

/**
 * Darkness of the shade under the menu text. Glass transparency sets it on calm pictures;
 * on bright ones a floor keeps the unselected row colour at about 3:1 against the darkened picture.
 */
fun mainMenuShadeAlpha(luminance: Float, transparencyPercent: Int): Float {
    val floor = if (luminance <= MAX_TEXT_BACKGROUND_LUMINANCE) 0f else 1f - MAX_TEXT_BACKGROUND_LUMINANCE / luminance
    val style = 0.15f + 0.6f * (100 - transparencyPercent.coerceIn(0, 100)) / 100f
    return maxOf(floor, style).coerceIn(0f, 0.85f)
}

private const val MAX_TEXT_BACKGROUND_LUMINANCE = 0.2f

/** Menu text area in image pixels for a picture drawn with Crop and TopEnd alignment. */
data class MenuRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

fun menuRegionInImage(
    imageWidth: Int,
    imageHeight: Int,
    screenWidth: Float,
    screenHeight: Float,
    menuWidthFraction: Float,
    rtl: Boolean = false
): MenuRegion? {
    if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0f || screenHeight <= 0f) return null
    val scale = maxOf(screenWidth / imageWidth, screenHeight / imageHeight)
    // TopEnd follows the layout direction: the overhang is on the left, or on the right in RTL.
    val offsetX = if (rtl) 0f else screenWidth - imageWidth * scale
    val menuWidth = screenWidth * menuWidthFraction.coerceIn(0f, 1f)
    val (screenLeft, screenRight) = if (rtl) screenWidth - menuWidth to screenWidth else 0f to menuWidth
    fun imageX(x: Float) = ((x - offsetX) / scale).toInt().coerceIn(0, imageWidth)
    fun imageY(y: Float) = (y / scale).toInt().coerceIn(0, imageHeight)
    val region = MenuRegion(imageX(screenLeft), imageY(screenHeight * 0.04f), imageX(screenRight), imageY(screenHeight * 0.8f))
    return region.takeIf { it.right > it.left && it.bottom > it.top }
}

/** Bright spots behind letters matter more than the average, so this reads the 85th percentile. */
fun menuRegionLuminance(pixels: IntArray, width: Int, region: MenuRegion): Float? {
    val values = ArrayList<Float>((region.right - region.left) * (region.bottom - region.top))
    for (y in region.top until region.bottom) {
        for (x in region.left until region.right) {
            val pixel = pixels.getOrNull(y * width + x) ?: continue
            if ((pixel ushr 24) < 128) continue
            values += relativeLuminance(pixel)
        }
    }
    if (values.isEmpty()) return null
    values.sort()
    return values[((values.size - 1) * 0.85f).toInt()]
}

private fun relativeLuminance(pixel: Int): Float {
    fun linear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    return 0.2126f * linear((pixel ushr 16) and 255) + 0.7152f * linear((pixel ushr 8) and 255) +
        0.0722f * linear(pixel and 255)
}

/**
 * A small software decode of the hero's file in the disk cache, never a network fetch. The disk key stays
 * the hero's own URL (a small request would otherwise be mapped to a smaller TMDB size that is not on disk),
 * and the sample stays out of the memory cache so it cannot replace the hero's full-size bitmap.
 */
suspend fun sampleMainMenuBackdrop(
    context: Context,
    url: String,
    screenWidth: Float,
    screenHeight: Float,
    menuWidthFraction: Float,
    rtl: Boolean
): Float? = withContext(Dispatchers.IO) {
    val result = try {
        context.imageLoader.execute(ImageRequest.Builder(context).data(url).diskCacheKey(url).size(96, 54)
            .allowHardware(false).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.READ_ONLY)
            .networkCachePolicy(CachePolicy.DISABLED).build())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { null }
    val bitmap = ((result as? SuccessResult)?.image as? BitmapImage)?.bitmap ?: return@withContext null
    // A compatible cache hit can be larger than the decode asked for; keep the sample bounded.
    val width = minOf(96, bitmap.width)
    val height = (bitmap.height.toLong() * width / bitmap.width).toInt().coerceIn(1, 96)
    val pixels = IntArray(width * height) { index ->
        bitmap[(index % width) * bitmap.width / width, (index / width) * bitmap.height / height]
    }
    val region = menuRegionInImage(width, height, screenWidth, screenHeight, menuWidthFraction, rtl)
        ?: return@withContext null
    menuRegionLuminance(pixels, width, region)
}

/**
 * The hero scrim while the menu opens over the picture: under the menu rows it gives way to the
 * menu's own shade, and it is back to normal before the page content (title, description) starts.
 * [stops] are (fraction of [fadeWidthPx], alpha) pairs; the result uses the same coordinates.
 */
fun openMenuScrimStops(
    stops: List<Pair<Float, Float>>,
    fadeWidthPx: Float,
    menuWidthPx: Float,
    progress: Float,
    samples: Int = 24
): List<Pair<Float, Float>> {
    if (progress <= 0f || fadeWidthPx <= 0f || menuWidthPx <= 0f) return stops
    val clearEnd = menuWidthPx * 0.7f / fadeWidthPx
    val rampEnd = menuWidthPx * 0.95f / fadeWidthPx
    fun normal(f: Float): Float {
        if (f <= stops.first().first) return stops.first().second
        stops.zipWithNext().forEach { (a, b) ->
            if (f <= b.first) return a.second + (b.second - a.second) * (f - a.first) / (b.first - a.first)
        }
        return stops.last().second
    }
    fun cleared(f: Float): Float = when {
        f <= clearEnd -> 1f
        f >= rampEnd -> 0f
        else -> (rampEnd - f) / (rampEnd - clearEnd)
    }
    val positions = (List(samples + 1) { it / samples.toFloat() } + stops.map { it.first } +
        listOf(clearEnd, rampEnd).filter { it in 0f..1f }).distinct().sorted()
    return positions.map { f -> f to normal(f) * (1f - progress.coerceIn(0f, 1f) * cleared(f)) }
}
