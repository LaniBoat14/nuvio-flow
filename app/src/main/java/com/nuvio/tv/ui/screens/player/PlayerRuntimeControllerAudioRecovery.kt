package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.ui.screens.player.iec.LiveDirectAudioPlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Lossless-audio recovery and routing helpers ported from the Arc fork: the IEC output
// failure ladder, bitstream-open retries, learned rejections, passthrough track promotion
// and output-return rebuilds. Kept in one file so the shared player files only carry hooks.

/**
 * A runtime check that tripped in the audio output path (audio renderer, audio sink or the IEC
 * engine). Video fallbacks such as dropping Dolby Vision cannot cure it. Read from the failing
 * renderer's format when there is one, otherwise from the cause's stack. media3 class names
 * survive minification and every audio output stack passes through them; the app's own iec
 * package only shows by name in debug builds.
 */
internal fun isAudioOutputRuntimeFailure(
    errorCode: Int,
    rendererSampleMimeType: String?,
    cause: Throwable?
): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) return false
    if (rendererSampleMimeType != null) return MimeTypes.isAudio(rendererSampleMimeType)
    return generateSequence(cause) { it.cause }.take(8).any { throwable ->
        throwable.stackTrace.any { frame -> isAudioOutputFrame(frame.className) }
    }
}

private fun isAudioOutputFrame(className: String): Boolean =
    className.startsWith("androidx.media3.exoplayer.audio.") ||
        className.startsWith("com.nuvio.tv.ui.screens.player.iec.")

/**
 * The first audio rung turns the app's own IEC output off, when the failing player sent the
 * current TrueHD or DTS-HD track through it. A known failing format must be one of those too.
 */
internal fun iecOffRungApplies(audioPathFailure: Boolean, iecInvolved: Boolean, failingMimeType: String?): Boolean =
    audioPathFailure && iecInvolved &&
        (failingMimeType == null || PassthroughWaterLevelPacer.isHbrMime(failingMimeType))

internal const val IEC_FAILURES_BEFORE_OFF = 2

/**
 * Counts an own IEC output failure for [url]. The first one only lets the film carry on through
 * the normal output, the next rebuild tries IEC again; a second one keeps the stream off IEC.
 * Returns the failure count for the stream.
 */
internal fun noteIecFailure(strikes: MutableMap<String, Int>, iecOff: MutableSet<String>, url: String): Int {
    val count = (strikes[url] ?: 0) + 1
    strikes[url] = count
    if (count >= IEC_FAILURES_BEFORE_OFF) iecOff.add(url)
    return count
}

internal fun PlayerRuntimeController.onOwnIecOutputFailed() {
    val url = currentStreamUrl
    val count = noteIecFailure(iecFailureStrikes, iecOffStreamUrls, url)
    Log.i(
        PlayerRuntimeController.TAG,
        if (url in iecOffStreamUrls) {
            "IEC_OFF_FOR_STREAM: own IEC output failed $count times host=${url.safeHost()}"
        } else {
            "IEC_FAILURE_NOTED: own IEC output failed once, tried again on the next rebuild host=${url.safeHost()}"
        }
    )
}

internal fun PlayerRuntimeController.retryCurrentStreamWithoutIec(fromPositionMs: Long) {
    iecOffStreamUrls.add(currentStreamUrl)
    Log.i(
        PlayerRuntimeController.TAG,
        "IEC_OFF_AUDIO_FALLBACK: rebuilding without IEC output host=${currentStreamUrl.safeHost()}"
    )
    scheduleDeferredPlayerReinitialize(fromPositionMs = fromPositionMs)
}

internal fun PlayerRuntimeController.audioTrackPcmFallbackAvailable(): Boolean =
    !hasTriedAudioPcmFallback &&
        cachedDecoderPriority == 1 && // Only for EXTENSION_RENDERER_MODE_ON
        !_uiState.value.tunnelingEnabled

/** Bounded retry of a verified bitstream sink configuration; delays are policy, not a root-cause claim. */
internal fun PlayerRuntimeController.tryBitstreamAudioTrackSameConfigRetry(error: PlaybackException): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    if (AudioTrackRejectionLog.labelForMime(mime) == null || currentAudioPassthroughPolicy?.deniesPassthrough(mime) != false) return false
    val pcmFallbackAvailable = audioTrackPcmFallbackAvailable()
    val reading = if (pcmFallbackAvailable) bitstreamOutputReading(error) else null
    if (reading != null && CompressedOutputCheck.skipSameConfigRetry(
            pcmFallbackAvailable = pcmFallbackAvailable,
            refusedListedLive = reading.refusedListedLive,
            ownTrackMayHold = CompressedOutputCheck.ownTrackMayHoldOutput(
                LiveDirectAudioPlayback.ownTrackOpen(),
                LiveDirectAudioPlayback.msSinceOwnTrackClosed()
            ),
            companionOpened = reading.companionOpened
        )
    ) {
        Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY skipped: bitstream output unavailable, decoding to PCM")
        return false
    }
    val player = _exoPlayer ?: return false
    if (!playbackRecoveryGate.canRetryAudio()) return false
    val delayMs = playbackRecoveryGate.nextAudioDelayMs() ?: return false
    Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY delayMs=$delayMs")
    scheduleOwnedPlaybackRecovery(
        player.currentPosition,
        delayMs,
        awaitOutputFor = PassthroughOutputReturn.encodingOf(failedAudioTrackInputFormat(error))
    )
    return true
}



/**
 * The learned-rejection entry for a refused open, or null when it must not be learned:
 * a same-configuration retry is already scheduled, or the failure is not a bitstream open.
 */
internal fun audioRejectionToStash(
    errorCode: Int,
    retryScheduled: Boolean,
    failingMime: String?,
    routeKey: String?
): String? {
    if (retryScheduled) return null
    if (errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return null
    val group = com.nuvio.tv.core.player.AudioPassthroughPolicy.groupOf(failingMime) ?: return null
    if (routeKey == null) return null
    return AudioRejectionLedger.entry(routeKey, group)
}

/** Snapshot identity before releasing. A LAZY job is assigned before it can run on Main.immediate. */
internal fun PlayerRuntimeController.scheduleOwnedPlaybackRecovery(
    positionMs: Long,
    delayMs: Long,
    awaitOutputFor: Int? = null
) {
    val url = currentStreamUrl
    val headers = currentHeaders.toMap()
    val generation = playbackRecoveryGate.generation
    var route = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
    val paused = userPausedManually
    val position = _uiState.value.pendingSeekPosition ?: positionMs.coerceAtLeast(0L)
    // The track that plays is kept: a restored choice is cleared once it matched, so it is not always remembered.
    val playingAudio = _uiState.value.audioTracks.firstOrNull { it.isSelected }
    val preference = (rememberedTrackPreference ?: persistedTrackPreference).let { base ->
        if (playingAudio == null) base else (base ?: currentTrackPreferenceForPersistence()).copy(
            audio = PlayerRuntimeController.RememberedTrackSelection(
                language = playingAudio.language,
                name = playingAudio.name,
                trackId = playingAudio.trackId
            )
        )
    }
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    pendingResumeProgress = null
    showRecoveryOverlay()
    playbackRecoveryJob?.cancel()
    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
        kotlinx.coroutines.yield()
        if (!playbackRecoveryGate.isCurrent(generation, url, headers)) return@launch
        releasePlayer(flushPlaybackState = false, preserveRecovery = true)
        delay(delayMs)
        if (!playbackRecoveryGate.isCurrent(generation, url, headers) || currentStreamUrl != url || currentHeaders != headers) return@launch
        if (awaitOutputFor != null) {
            val waited = awaitAudioOutputForRetry(awaitOutputFor) {
                playbackRecoveryGate.isCurrent(generation, url, headers)
            } ?: return@launch
            if (waited) route = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
        }
        if (runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull() != route) {
            _uiState.update { it.copy(error = "Audio output changed during recovery. Start playback again.", showLoadingOverlay = false) }
            return@launch
        }
        if (preference != null) pendingEngineSwitchTrackPreference = PlayerRuntimeController.PendingEngineSwitchTrackPreference(
            streamUrl = url, preference = preference, sourceEngine = InternalPlayerEngine.EXOPLAYER
        )
        _uiState.update { it.copy(pendingSeekPosition = position, error = null) }
        playbackRecoveryJob = null
        initializePlayer(url, headers, startPaused = paused || userPausedManually)
    }
    playbackRecoveryJob = job
    job.start()
}

/**
 * Waits until the audio output is back before a refused bitstream open of [encoding] is retried.
 * Without this, a refusal while HDMI is unplugged (TV on another input) rebuilds the player
 * with PCM-only capabilities. Returns whether it had to wait, or null when the recovery became
 * stale while waiting.
 */
internal suspend fun PlayerRuntimeController.awaitAudioOutputForRetry(
    encoding: Int,
    stillCurrent: () -> Boolean
): Boolean? {
    val startedMs = SystemClock.elapsedRealtime()
    var logged = false
    while (true) {
        if (!stillCurrent()) return null
        val routeKey = runCatching { AudioOutputRouteDetector.detect(context)?.key }.getOrNull()
        val ready = PassthroughOutputReturn.outputReadyForRetry(
            live = PassthroughOutputReturn.liveEncodings(context),
            routeIsHdmi = PassthroughOutputReturn.isHdmiRoute(routeKey)
        )
        val waitedMs = SystemClock.elapsedRealtime() - startedMs
        if (PassthroughOutputReturn.retryMayProceed(ready, waitedMs)) {
            if (logged) {
                Log.i(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY output back after ${waitedMs}ms ready=$ready route=$routeKey")
            }
            return logged
        }
        if (!logged) {
            Log.w(PlayerRuntimeController.TAG, "AUDIO_BITSTREAM_RETRY waiting for audio output encoding=$encoding route=$routeKey")
            logged = true
        }
        delay(PassthroughOutputReturn.POLL_MS)
    }
}

/**
 * FFmpeg-preferred rebuild for ERROR_CODE_DECODER_INIT_FAILED (4001) on an audio
 * renderer whose failing format belongs to a policy-denied group.
 *
 * A hybrid track (e.g. DTS-HD MA in Matroska) is selected under its base MIME
 * (audio/vnd.dts), then upgrades mid-stream to the denied MIME
 * (audio/vnd.dts.hd), leaving the selected renderer without a decoder. media3
 * never remaps a track mid-stream, so a plain retry fails the same way.
 * Preferring FFmpeg audio makes it win the tie for the whole family.
 *
 * While active (this stream only) FFmpeg decodes every audio format it
 * supports, so a track that could have passed through plays as PCM.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDeniedAudioFfmpegFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (currentStreamUrl in preferFfmpegAudioStreamUrls) return false
    if (cachedDecoderPriority == 0) return false // No FFmpeg renderer without extensions.
    val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
        ?.rendererFormat?.sampleMimeType
    if (failingMime == null || !androidx.media3.common.MimeTypes.isAudio(failingMime)) return false
    val policy = currentAudioPassthroughPolicy ?: return false
    if (!policy.deniesPassthrough(failingMime)) return false

    preferFfmpegAudioStreamUrls.add(currentStreamUrl)

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4001) on policy-denied audio $failingMime - retrying with FFmpeg audio preferred, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

internal fun surroundTargetToOutputChannels(
    channels: Int,
    fallback: com.nuvio.tv.data.local.AudioOutputChannels
): com.nuvio.tv.data.local.AudioOutputChannels = when (channels) {
    2 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_0
    3 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_1
    4 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_3_1
    6 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_5_1
    8 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_7_1
    else -> fallback
}

/**
 * Notes a refused bitstream open. The session log feeds the diagnostics card; the stash is
 * persisted as a learned denial only once a fallback has opened audio.
 */
internal fun PlayerRuntimeController.noteAudioTrackRejection(error: PlaybackException, retryScheduled: Boolean) {
    if (!error.hasAudioTrackFailure()) return
    val mime = failedAudioTrackInputFormat(error)?.sampleMimeType
    val routeKey = currentAudioOutputRoute?.key
    AudioTrackRejectionLog.labelForMime(mime)?.let { label ->
        AudioTrackRejectionLog.record(label, routeKey, System.currentTimeMillis())
        Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION encoding=$label route=$routeKey retry=$retryScheduled")
    }
    val group = com.nuvio.tv.core.player.AudioPassthroughPolicy.groupOf(mime)
    if (group != null && routeKey != null && error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
        AudioRejectionReverifier.ledger.noteOpenRefused(routeKey, group)
        saveAudioRefusalEvidence()
    }
    if (!retryScheduled && error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED &&
        BusyOutputReturn.startsFor(bitstreamOutputReading(error), retryScheduled)
    ) {
        startBusyOutputChecks()
    }
    if (group != null && !playbackRecoveryGate.shouldLearnAudioRejection(group)) {
        Log.w(PlayerRuntimeController.TAG, "AUDIO_TRACK_REJECTION_NOT_LEARNED group=${group.name} opened earlier in this playback")
        return
    }
    audioRejectionToStash(error.errorCode, retryScheduled, mime, routeKey)?.let { entry ->
        if (bitstreamOutputReading(error)?.outputUnavailable == true) {
            Log.w(
                PlayerRuntimeController.TAG,
                "AUDIO_TRACK_REJECTION_NOT_LEARNED group=${group?.name} route=$routeKey: " +
                    "bitstream output unavailable (a second listed format refused too)"
            )
            return
        }
        AudioRejectionReverifier.ledger.stashPending(currentStreamUrl, entry)
    }
}

internal fun PlayerRuntimeController.bitstreamOutputReading(error: PlaybackException): BitstreamOutputReading? {
    lastBitstreamOutputReading?.let { (readFor, reading) -> if (readFor === error) return reading }
    val reading = readBitstreamOutput(failedAudioTrackInputFormat(error))
    lastBitstreamOutputReading = error to reading
    return reading
}

private fun PlayerRuntimeController.readBitstreamOutput(format: Format?): BitstreamOutputReading? {
    val refused = PassthroughOutputReturn.encodingOf(format) ?: return null
    val live = PassthroughOutputReturn.liveEncodings(context)
    val listed = PassthroughOutputReturn.canPass(live, refused)
    val companionOpened = if (listed) {
        CompressedOutputCheck.companionEncoding(refused, live)?.let { AudioRejectionReverifier.opensNow(it) }
    } else {
        null
    }
    return BitstreamOutputReading(listed, companionOpened)
}

internal fun PlayerRuntimeController.saveAudioRefusalEvidence() {
    scope.launch {
        playerSettingsDataStore.saveAudioRefusalEvidence(AudioRejectionReverifier.ledger.evidenceSnapshot())
    }
}

internal fun PlayerRuntimeController.recordStashedAudioRejection(openedEncoding: Int) {
    val entry = AudioRejectionReverifier.ledger.takePendingFor(currentStreamUrl) ?: return
    val routeKey = AudioRejectionLedger.routeOf(entry) ?: return
    val group = AudioRejectionLedger.groupOf(entry) ?: return
    val commit = AudioRejectionReverifier.ledger.commit(entry)
    saveAudioRefusalEvidence()
    if (!commit.learn) {
        Log.w(
            PlayerRuntimeController.TAG,
            "AUDIO_REJECTION_NOT_LEARNED group=${group.name} route=$routeKey: " +
                "${commit.refusedOnRoute.joinToString(",") { it.name }} refused with no bitstream open in between, " +
                "AC-3 or E-AC-3 among them: the output is down, not the format" +
                if (commit.forget.isEmpty()) "" else "; forgetting ${commit.forget.joinToString(",")}"
        )
        for (stale in commit.forget) scope.launch { playerSettingsDataStore.clearAudioRejection(stale) }
        return
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AUDIO_REJECTION: ${group.name} refused at open on $routeKey, fallback opened " +
            "encoding=$openedEncoding; recording denial"
    )
    scope.launch { playerSettingsDataStore.recordAudioRejection(routeKey, group.name) }
}


@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.failingAudioMimeType(): String? =
    (this as? androidx.media3.exoplayer.ExoPlaybackException)?.rendererFormat?.sampleMimeType
        ?: failedAudioTrackInputFormat(this)?.sampleMimeType

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.isAudioOutputRuntimeFailure(): Boolean =
    isAudioOutputRuntimeFailure(
        errorCode = errorCode,
        rendererSampleMimeType = (this as? androidx.media3.exoplayer.ExoPlaybackException)
            ?.rendererFormat?.sampleMimeType,
        cause = cause
    )

internal fun promotePassthroughAudioWhenRendererAlive(
    mappedTrackInfo: androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    passthroughPolicy: AudioPassthroughPolicy?,
    audioSink: AudioSink?
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        var rendererAlive = false
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                if (RendererCapabilities.getFormatSupport(supports[groupIndex][trackIndex]) ==
                    C.FORMAT_HANDLED
                ) {
                    rendererAlive = true
                }
            }
        }
        if (!rendererAlive) continue
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val mime = group.getFormat(trackIndex).sampleMimeType ?: continue
                if (!isPassthroughAudioMime(mime)) continue
                val current = supports[groupIndex][trackIndex]
                val sinkSupport = audioSink?.getFormatSupport(group.getFormat(trackIndex))
                    ?: AudioSink.SINK_FORMAT_UNSUPPORTED
                if (!PassthroughTrackPromotion.shouldPromote(
                        current,
                        sinkSupport,
                        policyDenies = passthroughPolicy?.deniesPassthrough(mime) == true
                    )
                ) continue
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    RendererCapabilities.ADAPTIVE_SEAMLESS,
                    RendererCapabilities.getTunnelingSupport(current),
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

internal fun demoteAudioTunnelingWhereItCannotBeClocked(
    mappedTrackInfo: androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    ffmpegRendererName: String?,
    audioSink: PlaybackSpeedAwareAudioSink?,
    deadClockAudioClasses: Set<String>
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val rendererName = mappedTrackInfo.getRendererName(rendererIndex)
        val isFfmpegRenderer = ffmpegRendererName != null && rendererName == ffmpegRendererName
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val reason = when {
                    isFfmpegRenderer ->
                        if (deadClockAudioClasses.contains(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM)) {
                            "dead-tunnel-clock"
                        } else {
                            null
                        }
                    audioSink == null -> null
                    audioSink.demandsNonTunnelledVideo(format) -> "iec-hbr"
                    audioSink.hbrDemandsNonTunnelledVideo(format) -> "hbr-format"
                    deadClockAudioClasses.isNotEmpty() &&
                        deadClockAudioClasses.contains(audioSink.tunnelAudioClass(format)) -> "dead-tunnel-clock"
                    else -> null
                } ?: continue
                val current = supports[groupIndex][trackIndex]
                if (RendererCapabilities.getTunnelingSupport(current) ==
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED
                ) {
                    continue
                }
                Log.d(
                    "NuvioTrackSelector",
                    "Tunnelling cleared for renderer=$rendererName mime=${format.sampleMimeType} reason=$reason"
                )
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    RendererCapabilities.getFormatSupport(current),
                    RendererCapabilities.getAdaptiveSupport(current),
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

private fun isPassthroughAudioMime(mime: String): Boolean {
    return PassthroughWaterLevelPacer.isPassthroughMime(mime)
}

internal const val PCM_AUDIO_TRACK_BUFFER_US = 500_000

// Decoded PCM always gets a 500 ms track buffer, passthrough keeps the default sizes.
private val defaultAudioTrackBuffers = DefaultAudioTrackBufferSizeProvider.Builder()
    .setMinPcmBufferDurationUs(PCM_AUDIO_TRACK_BUFFER_US)
    .setMaxPcmBufferDurationUs(PCM_AUDIO_TRACK_BUFFER_US)
    .build()

internal val cappedPassthroughBufferSizeProvider =
    DefaultAudioSink.AudioTrackBufferSizeProvider { minBuffer, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed ->
        val size = defaultAudioTrackBuffers.getBufferSizeInBytes(
            minBuffer,
            encoding,
            outputMode,
            pcmFrameSize,
            sampleRate,
            bitrate,
            maxSpeed
        )
        val capBytes = if (outputMode == DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH) {
            passthroughBufferCapBytes(encoding)
        } else {
            Int.MAX_VALUE
        }
        maxOf(minBuffer, minOf(size, capBytes))
    }

private fun passthroughBufferCapBytes(encoding: Int): Int {
    return when (encoding) {
        C.ENCODING_DOLBY_TRUEHD -> 2 * 61_440
        C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> 4 * 30_720
        C.ENCODING_DTS -> 16 * 2_012
        C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> 2 * 10_752
        C.ENCODING_AC3 -> 1_536 * 8
        C.ENCODING_AC4 -> 16_384
        else -> 16_384
    }
}

/** Human-readable source codec from the selected track's mime. */
internal fun describeAudioMime(mime: String?): String = when (mime) {
    androidx.media3.common.MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    androidx.media3.common.MimeTypes.AUDIO_DTS -> "DTS"
    androidx.media3.common.MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    androidx.media3.common.MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    androidx.media3.common.MimeTypes.AUDIO_AC3 -> "AC-3"
    androidx.media3.common.MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
    androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 JOC"
    androidx.media3.common.MimeTypes.AUDIO_AC4 -> "AC-4"
    androidx.media3.common.MimeTypes.AUDIO_AAC -> "AAC"
    androidx.media3.common.MimeTypes.AUDIO_FLAC -> "FLAC"
    androidx.media3.common.MimeTypes.AUDIO_OPUS -> "Opus"
    null -> "Audio"
    else -> mime.substringAfterLast('/').uppercase()
}

/** Sink output mode from the negotiated AudioTrack encoding. */
internal fun describeAudioEncoding(encoding: Int): String = when (encoding) {
    C.ENCODING_AC3 -> "Passthrough (AC-3)"
    C.ENCODING_E_AC3 -> "Passthrough (E-AC-3)"
    C.ENCODING_E_AC3_JOC -> "Passthrough (E-AC-3 JOC)"
    C.ENCODING_DOLBY_TRUEHD -> "Passthrough (TrueHD)"
    C.ENCODING_DTS -> "Passthrough (DTS)"
    C.ENCODING_DTS_HD -> "Passthrough (DTS-HD)"
    C.ENCODING_AC4 -> "Passthrough (AC-4)"
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_24BIT_BIG_ENDIAN,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_32BIT_BIG_ENDIAN,
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_FLOAT -> "PCM decode"
    else -> "Encoding $encoding"
}

private fun String.safeHost(): String =
    runCatching { android.net.Uri.parse(this).host }.getOrNull().orEmpty()

/** Same test as the file-private check in PlayerRuntimeControllerInitialization, via the shared classifier. */
private fun PlaybackException.hasAudioTrackFailure(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isAudioTrackFailure(errorCode, details)
}
