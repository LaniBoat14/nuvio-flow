package com.nuvio.tv.core.player.thumbnail

/**
 * 4K HEVC keyframes coded as several slices (UHD Blu-ray remuxes) decode one strip at a time (hevc_strips.cpp), at a
 * fraction of a whole picture's memory.
 */
internal object StripDecode {
    /** -1 = not known yet. Sticky at 0 once any keyframe of the title decodes whole. */
    fun next(known: Int, strips: Int): Int = when {
        known == 0 -> 0
        strips >= 2 -> if (known < 0) strips else known
        else -> 0
    }

    fun tier(base: DecodeTier, strips: Int): DecodeTier =
        if (base == DecodeTier.SW_4K && strips >= 2) DecodeTier.SW_4K_STRIPS else base

    /** The charge for one keyframe in hand: a strip-priced decode of a keyframe that will not split costs a whole one. */
    fun chargeFor(tier: DecodeTier, keyframeStrips: () -> Int): DecodeTier =
        if (tier == DecodeTier.SW_4K_STRIPS && keyframeStrips() < 2) DecodeTier.SW_4K else tier

    /** Small boxes decode a 4K remux in strips while it plays only with `adb shell setprop debug.nuvio.thumb.strips_play 1`. */
    val whilePlaying: Boolean
        get() = property("debug.nuvio.thumb.strips_play") == "1"

    /** Strips this Annex B keyframe would decode in, 0 when whole or when the native decoder is not loaded. */
    fun count(au: ByteArray): Int =
        if (!ThumbNative.available) 0 else runCatching { ThumbNative.nativeStripCount(au, au.size) }.getOrDefault(0)

    private fun property(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
    }.getOrNull()
}
