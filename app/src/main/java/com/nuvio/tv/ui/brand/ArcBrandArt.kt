package com.nuvio.tv.ui.brand

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nuvio.tv.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The horizontal lockup, decoded at the size a slot needs and coloured for the active palette. */
object ArcBrandArt {
    @DrawableRes val LockupResource = R.drawable.app_logo_wordmark
    const val LockupWidth = 1209
    const val LockupHeight = 314
    /** The symbol on its own is the left part of the lockup. */
    const val MarkWidth = 318
    const val LockupAspect = LockupWidth.toFloat() / LockupHeight
    const val MarkAspect = MarkWidth.toFloat() / LockupHeight

    private const val GlassSmallHeight = 112

    private val cache = object : LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun release() = cache.evictAll()

    /** Art is prepared at the slot's own height, so it is drawn 1:1 instead of being resampled a second time. */
    fun bucketFor(heightPx: Int): Int = if (heightPx <= 0) 0 else min(heightPx, LockupHeight)

    private fun key(palette: ArcPalette, bucket: Int) = "${palette.key}@$bucket"

    fun cached(palette: ArcPalette, bucket: Int): ImageBitmap? =
        if (bucket <= 0) null else cache.get(key(palette, bucket))

    /** Blocking; call off the main thread. */
    fun load(context: Context, palette: ArcPalette, bucket: Int): ImageBitmap {
        cached(palette, bucket)?.let { return it }
        // Glass is a material, not a colour: it has its own art, with bolder edges for small slots.
        val glass = palette == ArcPalette.Glass
        val resource = when {
            !glass -> LockupResource
            bucket <= GlassSmallHeight -> R.drawable.app_logo_wordmark_glass_small
            else -> R.drawable.app_logo_wordmark_glass
        }
        val source = BitmapFactory.decodeResource(context.resources, resource,
            BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        val srcPixels = IntArray(source.width * source.height)
        source.getPixels(srcPixels, 0, source.width, 0, 0, source.width, source.height)
        val height = min(bucket, source.height)
        val width = max(1, (source.width * height.toFloat() / source.height).roundToInt())
        val pixels = if (height == source.height) srcPixels else ArcPixels.areaResample(srcPixels, source.width, source.height, width, height)
        source.recycle()
        if (!glass) palette.mapPixels(pixels)
        if (height <= SharpenBelow) ArcPixels.sharpen(pixels, width, height)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        out.prepareToDraw()
        return out.asImageBitmap().also { cache.put(key(palette, bucket), it) }
    }

    private const val SharpenBelow = 160
}

internal class ArcArtPainter(private val markOnly: Boolean) : Painter() {
    var image by mutableStateOf<ImageBitmap?>(null)
    private var alpha = 1f
    private var colorFilter: ColorFilter? = null

    override val intrinsicSize: Size = Size(
        (if (markOnly) ArcBrandArt.MarkWidth else ArcBrandArt.LockupWidth).toFloat(),
        ArcBrandArt.LockupHeight.toFloat()
    )

    override fun applyAlpha(alpha: Float): Boolean { this.alpha = alpha; return true }
    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean { this.colorFilter = colorFilter; return true }

    override fun DrawScope.onDraw() {
        val img = image ?: return
        val srcWidth = if (markOnly) {
            (img.width * ArcBrandArt.MarkWidth.toFloat() / ArcBrandArt.LockupWidth).roundToInt()
        } else img.width
        val slotWidth = size.width.roundToInt()
        val slotHeight = size.height.roundToInt()
        // Art prepared for this slot is drawn pixel for pixel; anything else is scaled into the slot.
        val exact = abs(srcWidth - slotWidth) <= 2 && abs(img.height - slotHeight) <= 1
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(srcWidth, img.height),
            dstOffset = if (exact) IntOffset((slotWidth - srcWidth) / 2, (slotHeight - img.height) / 2) else IntOffset.Zero,
            dstSize = if (exact) IntSize(srcWidth, img.height) else IntSize(slotWidth, slotHeight),
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = if (exact) FilterQuality.Low else FilterQuality.Medium
        )
    }
}
