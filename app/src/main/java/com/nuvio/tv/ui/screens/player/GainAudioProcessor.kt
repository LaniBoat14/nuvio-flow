package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.nuvio.tv.data.local.NightModeLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class GainAudioProcessor : BaseAudioProcessor() {

    private val dynamics = PcmDynamics()

    @Volatile
    private var gainDb: Int = AUDIO_AMPLIFICATION_MIN_DB

    @Volatile
    private var nightMode: NightModeLevel = NightModeLevel.OFF

    @Volatile
    private var centerGainDb: Int = 0

    /** PCM channel count of the last configuration, 0 before the first one. */
    @Volatile
    var pcmChannelCount: Int = 0
        private set

    /** Called on the playback thread when [pcmChannelCount] changes. */
    @Volatile
    var onPcmChannelCountChanged: ((Int) -> Unit)? = null

    private var frame = FloatArray(0)

    fun setGainDb(db: Int) {
        val clampedDb = db.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
        gainDb = clampedDb
        dynamics.setGainDb(clampedDb.toFloat())
    }

    fun setNightMode(level: NightModeLevel) {
        nightMode = level
        dynamics.setNightMode(level)
    }

    fun setCenterGainDb(db: Int) {
        val clampedDb = db.coerceIn(DIALOGUE_BOOST_MIN_DB, DIALOGUE_BOOST_MAX_DB)
        centerGainDb = clampedDb
        dynamics.setCenterGainDb(clampedDb.toFloat())
    }

    /** True when the decoded PCM has a centre channel this processor can raise (5.1 or 7.1). */
    fun canBoostCenter(): Boolean = PcmDynamics.centerChannelIndex(pcmChannelCount) >= 0

    fun isGainEnabled(): Boolean {
        return gainDb != AUDIO_AMPLIFICATION_MIN_DB
    }

    fun isNightModeEnabled(): Boolean = nightMode != NightModeLevel.OFF

    fun isProcessingEnabled(): Boolean =
        isGainEnabled() || isNightModeEnabled() || (centerGainDb > 0 && canBoostCenter())

    override fun isActive(): Boolean {
        return super.isActive() && isProcessingEnabled()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> {
                notePcmChannelCount(inputAudioFormat.channelCount)
                inputAudioFormat
            }
            else -> {
                notePcmChannelCount(0)
                AudioProcessor.AudioFormat.NOT_SET
            }
        }
    }

    private fun notePcmChannelCount(channels: Int) {
        if (channels == pcmChannelCount) return
        pcmChannelCount = channels
        onPcmChannelCountChanged?.invoke(channels)
    }

    override fun onFlush() {
        val format = inputAudioFormat
        if (format.channelCount > 0 && format.sampleRate > 0) {
            if (frame.size != format.channelCount) frame = FloatArray(format.channelCount)
            dynamics.configure(format.sampleRate, format.channelCount)
            Log.d(
                TAG,
                "SOUND_PROCESSING: rate=${format.sampleRate} ch=${format.channelCount} enc=${format.encoding} " +
                    "gainDb=$gainDb night=$nightMode centerDb=$centerGainDb"
            )
        }
    }

    override fun onReset() {
        frame = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        dynamics.prepareBuffer()

        if (frame.isNotEmpty()) when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer)
        }
        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    private fun processPcm16(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val samples = frame
        val frameBytes = samples.size * 2
        while (inputBuffer.remaining() >= frameBytes) {
            for (c in samples.indices) {
                samples[c] = inputBuffer.short / PCM16_SCALE
            }
            dynamics.processFrame(samples)
            for (c in samples.indices) {
                val value = (samples[c] * PCM16_SCALE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                outputBuffer.putShort(value.toShort())
            }
        }
    }

    private fun processPcmFloat(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val samples = frame
        val frameBytes = samples.size * 4
        while (inputBuffer.remaining() >= frameBytes) {
            for (c in samples.indices) {
                samples[c] = inputBuffer.float
            }
            dynamics.processFrame(samples)
            for (c in samples.indices) {
                outputBuffer.putFloat(samples[c])
            }
        }
    }

    private companion object {
        const val TAG = "GainAudioProcessor"
        const val PCM16_SCALE = 32768f
    }
}
