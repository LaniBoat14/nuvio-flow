package com.nuvio.tv.ui.brand

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colours for the Arc artwork. The artwork is drawn in the Original cyan to violet range and every
 * other palette is mapped from it by hue, so one source image serves every theme and custom colours.
 */
@Immutable
class ArcPalette private constructor(val key: String, private val stops: IntArray?) {

    val isOriginal: Boolean get() = stops == null

    /** Maps one non-premultiplied ARGB pixel, keeping its alpha. */
    fun map(argb: Int): Int {
        val p = stops ?: return argb
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val hi = max(r, max(g, b))
        if (hi < 8) return argb
        val d = hi - min(r, min(g, b))
        val hue = when {
            d == 0 -> 220f
            hi == r -> 60f * ((g - b).toFloat() / d + if (g < b) 6f else 0f)
            hi == g -> 60f * ((b - r).toFloat() / d + 2f)
            else -> 60f * ((r - g).toFloat() / d + 4f)
        }.coerceIn(185f, 305f)
        val u = ((hue - 185f) / 120f).coerceIn(0f, 1f) * 2f
        val index = min(1, floor(u).toInt())
        val f = u - index
        val white = WhiteMix[d * 255 / hi]
        val level = hi / 255f
        return (argb and 0xFF000000.toInt()) or
            (channel(p, index, f, white, level, 16) shl 16) or
            (channel(p, index, f, white, level, 8) shl 8) or
            channel(p, index, f, white, level, 0)
    }

    private fun channel(p: IntArray, index: Int, f: Float, white: Float, level: Float, shift: Int): Int {
        val a = (p[index] shr shift) and 0xFF
        val c = a + (((p[index + 1] shr shift) and 0xFF) - a) * f
        val w = 255f + (((p[0] shr shift) and 0xFF) - 255f) * 0.07f
        return ((c + (w - c) * white) * level).roundToInt().coerceIn(0, 255)
    }

    fun mapPixels(pixels: IntArray) {
        if (stops == null) return
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 != 0) pixels[i] = map(pixels[i])
        }
    }

    override fun equals(other: Any?): Boolean = other is ArcPalette && other.key == key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "ArcPalette($key)"

    companion object {
        private val WhiteMix = FloatArray(256) { (1f - it / 255f).toDouble().pow(1.35).toFloat() }

        val Original = ArcPalette("original", null)

        private fun of(key: String, vararg stops: Int) = ArcPalette(key, stops)

        val Crimson = of("crimson", 0xFF9B91, 0xE53935, 0xA51929)
        val Ocean = of("ocean", 0x8BDEFF, 0x1E88E5, 0x1565C0)
        val Violet = of("violet", 0xDFB3FF, 0x8E24AA, 0x6A1B9A)
        val Emerald = of("emerald", 0xB5EF9E, 0x43A047, 0x2E7D32)
        val Amber = of("amber", 0xFFE5A3, 0xFB8C00, 0xEF6C00)
        val Copper = of("copper", 0xFFD4AD, 0xCD7D4B, 0x853E2D)
        val Rose = of("rose", 0xFFAACB, 0xD81B60, 0xC2185B)
        val White = of("white", 0xFFFFFF, 0xE0E0E0, 0x969DA8)
        val Gold = of("gold", 0xFFF1A8, 0xE8A91C, 0x9A6200)
        val Jade = of("jade", 0x7BF08D, 0x22D37C, 0x0BBF9A)
        val RoseGold = of("rose_gold", 0xFFB37A, 0xEC70A9, 0xB75AFF)
        val ArcticBlue = of("arctic_blue", 0x4DE3FF, 0x3185F5, 0x4D55E8)
        val Graphite = of("graphite", 0xF3F5F7, 0xAAB2BE, 0x687381)
        val Glass = of("glass", 0xF3F6F8, 0xD5DDE3, 0x8FABB9)

        /** Very dark custom colours get a lifted highlight so the mark stays visible on black. */
        fun custom(colors: CustomThemeColors): ArcPalette {
            val lifted = colors.colors.map { color ->
                val channels = intArrayOf((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
                val peak = channels.max()
                if (peak >= 96) color else channels.fold(0) { acc, c -> (acc shl 8) or min(255, c + 96 - peak) }
            }
            return ArcPalette("custom:" + lifted.joinToString(",") { it.toString(16) }, lifted.toIntArray())
        }

        fun forTheme(theme: AppTheme, customColors: CustomThemeColors = CustomThemeColors.Default): ArcPalette =
            when (theme) {
                AppTheme.CUSTOM -> custom(customColors)
                AppTheme.GOLD -> Gold
                AppTheme.JADE -> Jade
                AppTheme.ROSE_GOLD -> RoseGold
                AppTheme.ARCTIC_BLUE -> ArcticBlue
                AppTheme.GRAPHITE -> Graphite
                AppTheme.CRIMSON -> Crimson
                AppTheme.OCEAN -> Ocean
                AppTheme.VIOLET -> Violet
                AppTheme.EMERALD -> Emerald
                AppTheme.AMBER -> Amber
                AppTheme.COPPER -> Copper
                AppTheme.ROSE -> Rose
                AppTheme.WHITE -> White
                AppTheme.GLASS -> Glass
            }
    }
}
