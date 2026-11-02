package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NightModeLevel
import java.util.Locale
import kotlin.math.pow

/**
 * The mpv audio filter chain for amplification and night mode. Mirrors [PcmDynamics]: gain
 * first, then the compressor with the same presets, then a peak limiter at the same ceiling.
 * Empty when both are off, so the mpv audio path is left exactly as it was.
 *
 * With [stereoDownmixBoostDb] (mpv plays surround out as stereo), the downmix to stereo is done
 * here with the same levels as [mpvSwresampleOptions], so the limiter comes after it.
 */
internal fun mpvSoundFilterChain(gainDb: Int, nightMode: NightModeLevel, stereoDownmixBoostDb: Int? = null): String {
    val preset = PcmDynamics.presetFor(nightMode)
    if (gainDb <= 0 && preset == null && stereoDownmixBoostDb == null) return ""
    val filters = buildList {
        if (gainDb > 0) add("volume=volume=${gainDb}dB:precision=float")
        if (preset != null) {
            add(
                "acompressor=threshold=${linear(preset.thresholdDb)}:ratio=${number(preset.ratio)}" +
                    ":attack=10:release=350:knee=2:makeup=${linear(preset.makeupDb)}" +
                    ":detection=peak:link=maximum"
            )
        }
        if (stereoDownmixBoostDb != null) {
            add("aresample=${mpvDownmixLevels(stereoDownmixBoostDb).joinToString(":")}")
            add("aformat=channel_layouts=stereo")
        }
        add("alimiter=limit=${number(PcmDynamics.LIMITER_CEILING)}:attack=1:release=100:level=0")
    }
    return "lavfi=[${filters.joinToString(",")}]"
}

/**
 * libswresample options for mpv's downmix. The LFE is folded into the front channels at -6 dB
 * (libswresample drops it by default). The centre goes in at -3 dB by default; Dialogue boost
 * raises it from there.
 */
internal fun mpvSwresampleOptions(dialogueBoostDb: Int): String = mpvDownmixLevels(dialogueBoostDb).joinToString(",")

private fun mpvDownmixLevels(dialogueBoostDb: Int): List<String> = buildList {
    add("lfe_mix_level=${number(LFE_MIX_LEVEL)}")
    if (dialogueBoostDb > 0) add("center_mix_level=${number(DEFAULT_CENTER_MIX_LEVEL * 10f.pow(dialogueBoostDb / 20f))}")
}

internal fun mpvStereoDownmixNeedsLimiter(outputChannels: Int, trackChannels: Int): Boolean =
    outputChannels == 2 && trackChannels > 2

private const val LFE_MIX_LEVEL = 0.5f

private const val DEFAULT_CENTER_MIX_LEVEL = 0.70710677f

private fun linear(db: Float): String = number(10f.pow(db / 20f))

private fun number(value: Float): String = String.format(Locale.ROOT, "%.4f", value)
