package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R

/**
 * The fork's own badge pictures for what is playing, used when no badge pack is imported.
 * Picture format, resolution, codec and channels come from the player; only Atmos (when the
 * codec family can carry it) and the source type come from the release name. [audioFromRelease]
 * is false when the release name does not speak for the playing audio track.
 */
internal fun builtInPlayerBadges(info: PlayerMediaInfo, releaseText: String, audioFromRelease: Boolean = true): List<Int> {
    val out = mutableListOf<Int>()
    val width = info.videoWidth ?: 0
    val height = info.videoHeight ?: 0
    when {
        width >= 3200 || height >= 2000 -> out += R.drawable.badge_res_2160
        width >= 1800 || height >= 1000 -> out += R.drawable.badge_res_1080
        width >= 1200 || height >= 700 -> out += R.drawable.badge_res_720
        width > 0 || height > 0 -> out += R.drawable.badge_res_480
    }
    val release = releaseText.lowercase()
    sourceBadge(release)?.let { out += it }
    when {
        "DV" in info.hdr -> out += R.drawable.badge_dv
        "HDR10" in info.hdr -> out += R.drawable.badge_hdr10
        "HLG" in info.hdr -> out += R.drawable.badge_hdr
    }
    val audioRelease = if (audioFromRelease) release else ""
    val codec = info.audioCodec.orEmpty().uppercase()
    val profile = info.audioProfiles.joinToString(" ").uppercase()
    val audio = when {
        codec == "TRUEHD" -> R.drawable.badge_truehd
        codec.startsWith("DTS") && (profile.contains("MA") || releaseSaysDtsHdMa(codec, audioRelease)) ->
            R.drawable.badge_dts_hd_ma
        codec == "DTS-HD" || codec == "DTS" && profile.contains("HD") -> R.drawable.badge_dts_hd
        codec == "DTS" -> R.drawable.badge_dts
        codec.startsWith("E-AC-3") -> R.drawable.badge_ddp
        codec == "AC-3" -> R.drawable.badge_dd
        else -> null
    }
    audio?.let { out += it }
    if (codec == "E-AC-3-JOC" || profile.contains("ATMOS") || releaseSaysAtmos(codec, audioRelease)) out += R.drawable.badge_atmos
    val channels = info.audioChannels ?: 0
    when {
        channels >= 8 -> out += R.drawable.badge_ch_7_1
        channels >= 6 -> out += R.drawable.badge_ch_5_1
    }
    return out
}

private fun sourceBadge(release: String): Int? = when {
    REMUX.containsMatchIn(release) -> R.drawable.badge_remux
    WEB_DL.containsMatchIn(release) -> R.drawable.badge_webdl
    WEBRIP.containsMatchIn(release) -> R.drawable.badge_webrip
    BLURAY.containsMatchIn(release) -> R.drawable.badge_bluray
    HDTV.containsMatchIn(release) -> R.drawable.badge_hdtv
    DVDRIP.containsMatchIn(release) -> R.drawable.badge_dvdrip
    else -> null
}

/** [codec] upper case, [release] lower case. Only a codec family that can carry it takes it from the release name. */
internal fun releaseSaysAtmos(codec: String, release: String): Boolean =
    (codec == "TRUEHD" || codec.startsWith("E-AC-3")) && ATMOS.containsMatchIn(release)

internal fun releaseSaysDtsHdMa(codec: String, release: String): Boolean =
    codec == "DTS-HD" && DTS_HD_MA.containsMatchIn(release)

/** DTS:X rides on a DTS-HD MA stream, which ExoPlayer reports as DTS-HD. */
internal fun releaseSaysDtsX(codec: String, release: String): Boolean =
    codec == "DTS-HD" && DTS_X.containsMatchIn(release)

private val ATMOS = Regex("""(?:(?<![a-z])|(?<=ddp|truehd))atmos(?![a-z])""")
private val DTS_HD_MA = Regex("""(?<![a-z])dts[\W_]*hd[\W_]*(?:ma|master[\W_]*audio)(?![a-z])""")
private val DTS_X = Regex("""(?<![a-z])dts[ .:_-]?x(?![a-z0-9])""")
private val REMUX =Regex("""(?<![a-z])remux(?![a-z])""")
private val WEB_DL = Regex("""(?<![a-z])web[ ._-]?dl(?![a-z])""")
private val WEBRIP = Regex("""(?<![a-z])web[ ._-]?rip(?![a-z])""")
private val BLURAY = Regex("""(?<![a-z])(blu[ ._-]?ray|bdrip|brrip)(?![a-z])""")
private val HDTV = Regex("""(?<![a-z])hdtv(?![a-z])""")
private val DVDRIP = Regex("""(?<![a-z])dvd[ ._-]?rip(?![a-z])""")
