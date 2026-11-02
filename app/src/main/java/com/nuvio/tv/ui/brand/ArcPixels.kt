package com.nuvio.tv.ui.brand

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel work for small brand art; plain arrays so it runs off the main thread and in tests. */
internal object ArcPixels {
    private const val SharpenAmount = 0.6f

    /**
     * Box-filter resampling: every target pixel averages exactly the source area it covers (alpha-weighted), which keeps
     * thin lettering and the lightning crisp where bilinear steps would smear them.
     */
    internal fun areaResample(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val sx = sw.toFloat() / dw
        val sy = sh.toFloat() / dh
        for (y in 0 until dh) {
            val y0 = y * sy
            val y1 = y0 + sy
            for (x in 0 until dw) {
                val x0 = x * sx
                val x1 = x0 + sx
                var a = 0f; var r = 0f; var g = 0f; var b = 0f; var area = 0f
                var yy = y0.toInt()
                while (yy < ceil(y1).toInt() && yy < sh) {
                    val wy = min(y1, yy + 1f) - max(y0, yy.toFloat())
                    var xx = x0.toInt()
                    while (xx < ceil(x1).toInt() && xx < sw) {
                        val w = wy * (min(x1, xx + 1f) - max(x0, xx.toFloat()))
                        val c = src[yy * sw + xx]
                        val ca = (c ushr 24) * w
                        a += ca
                        r += ((c shr 16) and 0xFF) * ca
                        g += ((c shr 8) and 0xFF) * ca
                        b += (c and 0xFF) * ca
                        area += w
                        xx++
                    }
                    yy++
                }
                out[y * dw + x] = if (a <= 0f) 0 else {
                    val alpha = (a / area).roundToInt().coerceIn(0, 255)
                    (alpha shl 24) or ((r / a).roundToInt().coerceIn(0, 255) shl 16) or
                        ((g / a).roundToInt().coerceIn(0, 255) shl 8) or (b / a).roundToInt().coerceIn(0, 255)
                }
            }
        }
        return out
    }

    /** A light unsharp mask on the colour (3x3 binomial blur), so small renders keep the edges a 2x art has. */
    internal fun sharpen(px: IntArray, w: Int, h: Int) {
        if (w < 3 || h < 3) return
        val src = px.copyOf()
        val weights = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val c = src[y * w + x]
            if ((c ushr 24) == 0) continue
            var r = 0; var g = 0; var b = 0; var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val n = src[(y + dy) * w + x + dx]
                val wt = weights[k++] * ((n ushr 24) + 1)
                r += ((n shr 16) and 0xFF) * wt; g += ((n shr 8) and 0xFF) * wt; b += (n and 0xFF) * wt
            }
            var total = 0
            k = 0
            for (dy in -1..1) for (dx in -1..1) total += weights[k++] * ((src[(y + dy) * w + x + dx] ushr 24) + 1)
            fun lift(v: Int, blur: Int) = (v + SharpenAmount * (v - blur)).roundToInt().coerceIn(0, 255)
            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
            px[y * w + x] = (c and 0xFF000000.toInt()) or (lift(cr, r / total) shl 16) or (lift(cg, g / total) shl 8) or lift(cb, b / total)
        }
    }
}
