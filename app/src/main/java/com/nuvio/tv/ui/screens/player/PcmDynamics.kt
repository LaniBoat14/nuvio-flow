package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NightModeLevel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Gain, a centre channel boost, night mode compression and a peak limiter for interleaved float PCM.
 *
 * Light and Medium only lift. Their threshold sits where the make-up gain is used up at full
 * scale, so quiet passages come up by the full make-up, louder ones by less, and nothing is turned
 * below how it was mixed. Strong also takes the loudest peaks down by about 6 dB, while anything
 * below about -7 dBFS still keeps or gains level. The limiter catches the peaks.
 *
 * Feed-forward with no look-ahead, so it adds no latency. All channels share one
 * detector and one gain, which keeps the channel balance and the stereo image.
 * Nothing is allocated after [configure].
 */
internal class PcmDynamics {

    internal class Preset(val thresholdDb: Float, val ratio: Float, val makeupDb: Float)

    @Volatile
    private var targetGainDb = 0f

    @Volatile
    private var nightMode = NightModeLevel.OFF

    @Volatile
    private var targetCenterDb = 0f

    private var channelCount = 0
    private var centerIndex = -1

    private var gainSmoothCoef = 0f
    private var envAttackCoef = 0f
    private var envReleaseCoef = 0f
    private var compAttackCoef = 0f
    private var compReleaseCoef = 0f
    private var limiterReleaseCoef = 0f
    private var limiterHoldFrames = 0

    private var bufferGainTarget = 1f
    private var bufferCenterTarget = 1f
    private var bufferPreset: Preset? = null
    private var bufferCeiling = 1f

    private var gainLinear = 1f
    private var centerLinear = 1f
    private var envelope = 0f
    private var compGainDb = 0f
    private var compLinear = 1f
    private var compStep = 0f
    private var blockPosition = 0
    private var limiterGain = 1f
    private var limiterHold = 0

    fun setGainDb(db: Float) {
        targetGainDb = db
    }

    fun setNightMode(level: NightModeLevel) {
        nightMode = level
    }

    /** Boost for the front centre channel. Only applied when [hasCenterChannel]. */
    fun setCenterGainDb(db: Float) {
        targetCenterDb = db
    }

    fun isEnabled(): Boolean =
        targetGainDb != 0f || nightMode != NightModeLevel.OFF || (targetCenterDb != 0f && hasCenterChannel())

    fun hasCenterChannel(): Boolean = centerIndex >= 0

    fun configure(sampleRate: Int, channels: Int) {
        require(sampleRate > 0 && channels > 0)
        channelCount = channels
        centerIndex = centerChannelIndex(channels)
        val rate = sampleRate.toFloat()
        val blockRate = rate / BLOCK_FRAMES
        gainSmoothCoef = coefficient(GAIN_SMOOTH_SECONDS, rate)
        envAttackCoef = coefficient(ENVELOPE_ATTACK_SECONDS, rate)
        envReleaseCoef = coefficient(ENVELOPE_RELEASE_SECONDS, rate)
        compAttackCoef = coefficient(COMPRESSOR_ATTACK_SECONDS, blockRate)
        compReleaseCoef = coefficient(COMPRESSOR_RELEASE_SECONDS, blockRate)
        limiterReleaseCoef = coefficient(LIMITER_RELEASE_SECONDS, rate)
        limiterHoldFrames = (LIMITER_HOLD_SECONDS * rate).toInt()
        reset()
    }

    fun reset() {
        gainLinear = dbToLinear(targetGainDb)
        centerLinear = if (centerIndex >= 0) dbToLinear(targetCenterDb) else 1f
        envelope = 0f
        compGainDb = 0f
        compLinear = 1f
        compStep = 0f
        blockPosition = 0
        limiterGain = 1f
        limiterHold = 0
    }

    /** Reads the current settings once per buffer so a buffer never mixes two. */
    fun prepareBuffer() {
        val gainDb = targetGainDb
        val level = nightMode
        val centerDb = if (centerIndex >= 0) targetCenterDb else 0f
        bufferGainTarget = dbToLinear(gainDb)
        bufferCenterTarget = dbToLinear(centerDb)
        bufferPreset = presetFor(level)
        bufferCeiling = if (gainDb != 0f || centerDb != 0f || level != NightModeLevel.OFF) LIMITER_CEILING else 1f
    }

    /** Processes one interleaved frame of [channelCount] samples in place. */
    fun processFrame(frame: FloatArray) {
        val channels = channelCount
        gainLinear = bufferGainTarget + gainSmoothCoef * (gainLinear - bufferGainTarget)
        val gain = gainLinear

        val center = centerIndex
        if (center >= 0) {
            centerLinear = bufferCenterTarget + gainSmoothCoef * (centerLinear - bufferCenterTarget)
        }

        var peak = 0f
        for (c in 0 until channels) {
            var v = frame[c]
            if (!v.isFinite()) v = 0f
            if (c == center) v *= centerLinear
            frame[c] = v
            peak = max(peak, abs(v * gain))
        }
        envelope = if (peak > envelope) {
            peak + envAttackCoef * (envelope - peak)
        } else {
            peak + envReleaseCoef * (envelope - peak)
        }

        if (blockPosition == 0) {
            val target = compressorTargetDb(envelope, bufferPreset)
            val coef = if (target < compGainDb) compAttackCoef else compReleaseCoef
            compGainDb = target + coef * (compGainDb - target)
            compStep = (dbToLinear(compGainDb) - compLinear) / BLOCK_FRAMES
        }
        blockPosition = (blockPosition + 1) % BLOCK_FRAMES
        compLinear += compStep

        val scale = gain * compLinear
        var outPeak = 0f
        for (c in 0 until channels) {
            val v = frame[c] * scale
            frame[c] = v
            outPeak = max(outPeak, abs(v))
        }

        val ceiling = bufferCeiling
        val needed = if (outPeak > ceiling) ceiling / outPeak else 1f
        if (needed < limiterGain) {
            limiterGain = needed
            limiterHold = limiterHoldFrames
        } else if (limiterHold > 0) {
            limiterHold--
        } else {
            limiterGain = 1f + limiterReleaseCoef * (limiterGain - 1f)
            if (limiterGain * outPeak > ceiling) limiterGain = needed
        }
        val lg = limiterGain
        for (c in 0 until channels) {
            frame[c] = (frame[c] * lg).coerceIn(-1f, 1f)
        }
    }

    internal companion object {
        const val BLOCK_FRAMES = 16
        const val LIMITER_CEILING = 0.891f
        private const val GAIN_SMOOTH_SECONDS = 0.02f
        private const val ENVELOPE_ATTACK_SECONDS = 0.002f
        private const val ENVELOPE_RELEASE_SECONDS = 0.15f
        private const val COMPRESSOR_ATTACK_SECONDS = 0.01f
        private const val COMPRESSOR_RELEASE_SECONDS = 0.35f
        private const val LIMITER_HOLD_SECONDS = 0.025f
        private const val LIMITER_RELEASE_SECONDS = 0.1f
        private const val KNEE_DB = 6f
        private const val MAKEUP_FADE_START_DB = -66f
        private const val MAKEUP_FADE_RANGE_DB = 12f

        // threshold = -makeup * ratio / (ratio - 1), so the gain is back to unity at 0 dBFS
        private val LIGHT = Preset(thresholdDb = -12f, ratio = 2f, makeupDb = 6f)
        private val MEDIUM = Preset(thresholdDb = -15f, ratio = 3f, makeupDb = 10f)
        // about -6 dB at 0 dBFS
        private val STRONG = Preset(thresholdDb = -25f, ratio = 6f, makeupDb = 15f)

        /**
         * Front centre position in PCM as Android and ExoPlayer deliver it (FL FR FC LFE ...). Only the 5.1 and
         * 7.1 layouts are certain; other counts have no centre, or one this code cannot place.
         */
        fun centerChannelIndex(channels: Int): Int = if (channels == 6 || channels == 8) 2 else -1

        fun presetFor(level: NightModeLevel): Preset? = when (level) {
            NightModeLevel.OFF -> null
            NightModeLevel.LIGHT -> LIGHT
            NightModeLevel.MEDIUM -> MEDIUM
            NightModeLevel.STRONG -> STRONG
        }

        /** Static gain change in dB for a steady [level] (linear peak), before smoothing. */
        fun compressorTargetDb(level: Float, preset: Preset?): Float {
            if (preset == null) return 0f
            val levelDb = 20f * log10(max(level, 1e-6f))
            val over = levelDb - preset.thresholdDb
            val slope = 1f / preset.ratio - 1f
            val reduction = when {
                over <= -KNEE_DB / 2f -> 0f
                over >= KNEE_DB / 2f -> slope * over
                else -> slope * (over + KNEE_DB / 2f).pow(2) / (2f * KNEE_DB)
            }
            // Fade the make-up gain out near silence so hiss and room noise are not lifted.
            val makeupWeight = ((levelDb - MAKEUP_FADE_START_DB) / MAKEUP_FADE_RANGE_DB).coerceIn(0f, 1f)
            return reduction + preset.makeupDb * makeupWeight
        }

        private fun coefficient(seconds: Float, rate: Float): Float = exp(-1f / (seconds * rate))

        private fun dbToLinear(db: Float): Float = if (db == 0f) 1f else 10f.pow(db / 20f)
    }
}
