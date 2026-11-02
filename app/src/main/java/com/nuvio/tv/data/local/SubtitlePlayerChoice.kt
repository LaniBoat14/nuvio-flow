package com.nuvio.tv.data.local

import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils

internal data class SubtitleLanguageRanking(
    val first: String,
    val second: String?,
    val third: String?
)

/**
 * The three preferred subtitle languages after [chosen] is picked in the player, or null when they stay as they
 * are. [first] is the current first language (while subtitles are off, the one kept aside), null when there is
 * none. A language outside the three goes first and the others move down one place, so the third drops off.
 * Languages are compared the way the player matches tracks: English covers English (UK), but Portuguese and
 * Portuguese (Brazil), Spanish and Spanish (Latin America), or Chinese and either Chinese script, stay apart.
 */
internal fun subtitleLanguagesAfterPlayerChoice(
    first: String?,
    second: String?,
    third: String?,
    chosen: String
): SubtitleLanguageRanking? {
    val current = listOfNotNull(first, second, third).filter(::isRankedSubtitleLanguage)
    if (first != null && current.any { coversSubtitleLanguage(it, chosen) }) return null
    val ranked = (listOf(chosen) + current.filterNot { coversSubtitleLanguage(chosen, it) })
        .distinctBy { it.lowercase() }
    return SubtitleLanguageRanking(ranked[0], ranked.getOrNull(1), ranked.getOrNull(2))
}

internal fun coversSubtitleLanguage(preferred: String, chosen: String): Boolean {
    if (preferred.equals(chosen, ignoreCase = true)) return true
    if (isChineseSubtitleLanguage(preferred) || isChineseSubtitleLanguage(chosen)) return false
    return PlayerSubtitleUtils.matchesLanguageCode(chosen, preferred)
}

private fun isChineseSubtitleLanguage(language: String): Boolean =
    PlayerSubtitleUtils.normalizeLanguageCode(language).substringBefore('-') == "zh"

private fun isRankedSubtitleLanguage(language: String): Boolean =
    language.isNotBlank() &&
        !language.equals("none", ignoreCase = true) &&
        !language.equals(SUBTITLE_LANGUAGE_FORCED, ignoreCase = true) &&
        !language.equals(SubtitleLanguageOption.DEVICE, ignoreCase = true)
