package com.nuvio.tv.ui.screens.player

/** Whether Dialogue boost can act on the current sound, and if not, why. */
enum class DialogueBoostState {
    AVAILABLE,
    PASSTHROUGH,
    STEREO_TRACK,
    MIXED_TO_STEREO,
    MPV_MULTICHANNEL
}

/**
 * Dialogue boost raises the centre channel, so it needs the separate channels in decoded sound:
 * the FFmpeg downmix applies it while mixing to stereo, and the PCM processor applies it to 5.1
 * and 7.1 output. On mpv it goes into mpv's own downmix to stereo.
 *
 * @param trackChannels channel count of the selected track, 0 when unknown
 * @param pcmChannels channel count of the decoded PCM the processor last saw, 0 when unknown
 * @param mpvOutputChannels channel count mpv sends to the device, 0 when unknown
 */
internal fun dialogueBoostState(
    isMpv: Boolean,
    isBypassing: Boolean,
    trackChannels: Int,
    ffmpegDownmixActive: Boolean,
    pcmChannels: Int,
    mpvOutputChannels: Int
): DialogueBoostState {
    if (!isMpv && isBypassing) return DialogueBoostState.PASSTHROUGH
    if (trackChannels in 1..2) return DialogueBoostState.STEREO_TRACK
    if (isMpv) {
        return if (mpvOutputChannels > 2) DialogueBoostState.MPV_MULTICHANNEL else DialogueBoostState.AVAILABLE
    }
    if (ffmpegDownmixActive && trackChannels > 2) return DialogueBoostState.AVAILABLE
    if (PcmDynamics.centerChannelIndex(pcmChannels) >= 0) return DialogueBoostState.AVAILABLE
    return if (trackChannels > 2 || pcmChannels in 1..2) {
        DialogueBoostState.MIXED_TO_STEREO
    } else {
        DialogueBoostState.STEREO_TRACK
    }
}
