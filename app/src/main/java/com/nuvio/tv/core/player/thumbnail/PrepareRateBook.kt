package com.nuvio.tv.core.player.thumbnail

import android.content.Context

/** Seconds per thumbnail the generating screen measured on this box, per kind of title, so its first estimate is near. */
internal object PrepareRateBook {
    private const val PREFS = "seek_thumbs_rate"
    /** Fewer than this many thumbnails say little about the speed. */
    const val MIN_MADE = 10
    const val MIN_SECONDS = 0.05
    const val MAX_SECONDS = 10.0

    fun key(fourK: Boolean, remux: Boolean, hardware: Boolean): String =
        (if (!fourK) "hd" else if (remux) "remux" else "4k") + if (hardware) "|hw" else "|sw"

    /** Half the old value, half the new: one slow source does not set the next estimate on its own. */
    fun blend(old: Double?, measured: Double): Double =
        (if (old == null) measured else (old + measured) / 2).coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun get(context: Context, key: String): Double? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(key, -1f).toDouble().takeIf { it > 0 }
    }.getOrNull()

    fun note(context: Context, key: String, seconds: Double) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getFloat(key, -1f).toDouble().takeIf { it > 0 }
            prefs.edit().putFloat(key, blend(old, seconds).toFloat()).apply()
        }
    }
}
