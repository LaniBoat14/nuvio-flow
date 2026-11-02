package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.performance.ThermalHeadroom
import java.io.File
import kotlin.math.roundToInt

/**
 * Temperature for the playback stats HUD.
 *
 * Tiers, tried in order:
 *   1. sysfs thermal zone in degrees C (first zone whose type contains "soc"
 *      or "cpu"). Many boxes deny the read by SELinux, so it is tried once and
 *      the verdict cached. When it works, headroom is not asked for at all.
 *   2. PowerManager.getThermalHeadroom(0). The framework computes it from the
 *      hottest SKIN sensor as (T - (severe - 30)) / 30, so it converts back to
 *      degrees exactly once the SKIN severe threshold is known. Apps cannot read
 *      that threshold, hence [SKIN_SEVERE_C_BY_MODEL]; elsewhere the row shows
 *      the distance to the threshold, which needs no table.
 *
 * Headroom is read through [ThermalHeadroom], at most once per 10 s. A missed
 * read keeps the last valid value for [HEADROOM_HOLD_MS], which outlasts one
 * read interval. Runs on the HUD sampling path only.
 */
internal class PlaybackThermalSampler(
    context: Context,
    private val model: String = Build.MODEL,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime
) {

    data class Reading(
        /** Exact chip temperature, only when sysfs is app-readable on this device. */
        val celsius: Float?,
        /** Normalised distance to severe throttling (1.0 = severe), when the HAL serves it. */
        val headroom: Float?,
        /** SKIN severe threshold for this model, when known. */
        val skinLimitC: Float? = null
    )

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    @Volatile
    private var sysfsTempNode: File? = null

    @Volatile
    private var sysfsProbed: Boolean = false

    private var lastHeadroom: Float? = null
    private var lastHeadroomAtMs: Long = 0L

    fun sample(): Reading? {
        val celsius = readSysfsCelsius()
        if (celsius != null) return Reading(celsius = celsius, headroom = null)
        val headroom = heldHeadroom(readHeadroom()) ?: return null
        return Reading(celsius = null, headroom = headroom, skinLimitC = SKIN_SEVERE_C_BY_MODEL[model])
    }

    internal fun heldHeadroom(fresh: Float?): Float? {
        val now = nowMs()
        if (fresh != null) {
            lastHeadroom = fresh
            lastHeadroomAtMs = now
            return fresh
        }
        return lastHeadroom?.takeIf { now - lastHeadroomAtMs <= HEADROOM_HOLD_MS }
    }

    private fun readSysfsCelsius(): Float? {
        if (!sysfsProbed) probeSysfsOnce()
        val node = sysfsTempNode ?: return null
        return runCatching {
            node.readText().trim().toLong().let { milli ->
                if (milli > 1000L) milli / 1000f else milli.toFloat()
            }
        }.getOrElse {
            Log.w(TAG, "sysfs temp read failed after successful probe: ${it.message}")
            sysfsTempNode = null
            null
        }
    }

    private fun probeSysfsOnce() {
        sysfsProbed = true
        val result = runCatching {
            for (i in 0 until MAX_ZONES) {
                val zone = File("/sys/class/thermal/thermal_zone$i")
                if (!zone.exists()) break
                val type = runCatching { File(zone, "type").readText().trim().lowercase() }
                    .getOrNull() ?: continue
                if ("soc" in type || "cpu" in type) {
                    val temp = File(zone, "temp")
                    temp.readText().trim().toLong()
                    sysfsTempNode = temp
                    Log.i(TAG, "sysfs thermal readable: zone$i type=$type")
                    return@runCatching
                }
            }
            Log.i(TAG, "sysfs thermal: no readable soc/cpu zone (expected under vendor SELinux)")
        }
        result.onFailure {
            Log.i(TAG, "sysfs thermal unreadable (${it.javaClass.simpleName}), using headroom tier")
        }
    }

    private fun readHeadroom(): Float? =
        ThermalHeadroom.read(powerManager, 0).takeIf { !it.isNaN() && it >= 0f }

    internal companion object {
        const val TAG = "ThermalSampler"
        const val MAX_ZONES = 16
        const val HEADROOM_HOLD_MS = 15_000L

        /** Degrees between headroom 0.0 and 1.0, fixed by the framework's ThermalManagerService. */
        const val HEADROOM_SPAN_C = 30f

        /** SKIN severe threshold by Build.MODEL, from the vendor thermal HAL. */
        val SKIN_SEVERE_C_BY_MODEL: Map<String, Float> = mapOf(
            "AFTKRT" to 50f
        )
    }
}

internal const val CHIP_TEMP_LABEL = "Chip temp"
internal const val DEVICE_TEMP_LABEL = "Device temp"

internal fun thermalStatsRow(reading: PlaybackThermalSampler.Reading): StatsRow? {
    val t = PlaybackStatsThresholds
    reading.celsius?.let { celsius ->
        val dot = when {
            celsius < t.SOC_TEMP_WARN_C -> StatsDot.GOOD
            celsius < t.SOC_TEMP_BAD_C -> StatsDot.WARN
            else -> StatsDot.BAD
        }
        return StatsRow(CHIP_TEMP_LABEL, String.format("%.1f °C", celsius), dot)
    }
    val headroom = reading.headroom ?: return null
    val dot = when {
        headroom < t.THERMAL_HEADROOM_WARN -> StatsDot.GOOD
        headroom < t.THERMAL_HEADROOM_BAD -> StatsDot.WARN
        else -> StatsDot.BAD
    }
    val span = PlaybackThermalSampler.HEADROOM_SPAN_C
    val fromLimit = ((headroom - 1f) * span).roundToInt()
    val limit = reading.skinLimitC?.roundToInt()
    val value = when {
        limit == null && headroom <= 0f -> "${span.roundToInt()}+ °C below limit"
        limit == null && fromLimit < 0 -> "${-fromLimit} °C below limit"
        limit == null && fromLimit == 0 -> "at limit"
        limit == null -> "$fromLimit °C over limit"
        headroom <= 0f -> "≤ ${limit - span.roundToInt()} °C · limit $limit °C"
        fromLimit < 0 -> "≈ ${limit + fromLimit} °C · limit $limit °C"
        fromLimit == 0 -> "≈ $limit °C · at limit"
        else -> "≈ ${limit + fromLimit} °C · over $limit °C limit"
    }
    return StatsRow(DEVICE_TEMP_LABEL, value, dot)
}
