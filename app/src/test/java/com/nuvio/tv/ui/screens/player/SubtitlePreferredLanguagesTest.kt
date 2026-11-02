package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SubtitleLanguageRanking
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.domain.model.Subtitle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlePreferredLanguagesTest {
    private fun style(
        preferred: String = "fr",
        secondary: String? = "de",
        tertiary: String? = "en",
        showOnly: Boolean = true
    ) = SubtitleStyleSettings(
        preferredLanguage = preferred,
        isPreferredLanguageSystemDefault = false,
        secondaryPreferredLanguage = secondary,
        tertiaryPreferredLanguage = tertiary,
        showOnlyPreferredLanguages = showOnly
    )

    private fun controller(style: SubtitleStyleSettings) = mockk<PlayerRuntimeController>(relaxed = true) {
        every { _uiState } returns MutableStateFlow(PlayerUiState(subtitleStyle = style))
    }

    private fun track(index: Int, language: String) = TrackInfo(index = index, name = "Track ${index + 1}", language = language)

    private fun subtitle(lang: String) = Subtitle(id = "$lang-1", url = "https://subs.example/$lang.srt", lang = lang, addonName = "Addon", addonLogo = null)

    @Test fun `targets run preferred then secondary then third`() {
        assertEquals(listOf("fr", "de", "en"), subtitleLanguageTargets(style()))
        assertEquals(listOf("fr", "de"), subtitleLanguageTargets(style(tertiary = null)))
        assertEquals(listOf("fr", "en"), subtitleLanguageTargets(style(secondary = null)))
        assertEquals(emptyList<String>(), subtitleLanguageTargets(style(preferred = "none")))
    }

    @Test fun `the third language is picked only when the first two are missing`() {
        val runtime = controller(style())
        val targets = runtime.subtitleLanguageTargets()
        fun pick(vararg languages: String) = runtime.findBestInternalSubtitleTrackIndex(
            subtitleTracks = languages.mapIndexed { index, language -> track(index, language) },
            targets = targets,
            normalOnly = true
        )

        assertEquals(1, pick("it", "en"))
        assertEquals(0, pick("de", "en"))
        assertEquals(2, pick("en", "de", "fr"))
        assertEquals(-1, pick("it", "nl"))
        assertEquals(-1, controller(style(tertiary = null)).findBestInternalSubtitleTrackIndex(
            subtitleTracks = listOf(track(0, "it"), track(1, "en")),
            targets = subtitleLanguageTargets(style(tertiary = null)),
            normalOnly = true
        ))
    }

    @Test fun `show only preferred languages keeps all three and hides the rest`() {
        val all = listOf("fr", "it", "de", "nl", "en").map(::subtitle)

        assertEquals(listOf("fr", "de", "en"), controller(style()).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(listOf("fr", "de"), controller(style(tertiary = null)).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(all, controller(style(showOnly = false)).filterToVisibleAddonSubtitles(all))
    }

    @Test fun `show only preferred languages keeps the second and third when the first is none`() {
        val all = listOf("fr", "de", "en").map(::subtitle)

        assertEquals(listOf("de", "en"), controller(style(preferred = "none")).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(listOf("en"), controller(style(preferred = "none", secondary = null)).filterToVisibleAddonSubtitles(all).map { it.lang })
        assertEquals(emptyList<Subtitle>(), controller(style(preferred = "none", secondary = null, tertiary = null)).filterToVisibleAddonSubtitles(all))
    }

    @Test fun `a pick of one of the three languages leaves the preferences alone`() {
        assertEquals(null, subtitleLanguagesAfterPlayerChoice(style(), "de"))
        assertEquals(null, subtitleLanguagesAfterPlayerChoice(style(), "en"))
        assertEquals(null, subtitleLanguagesAfterPlayerChoice(style(), "fr"))
    }

    @Test fun `a pick of another language goes first and moves the three down`() {
        assertEquals(SubtitleLanguageRanking("it", "fr", "de"), subtitleLanguagesAfterPlayerChoice(style(), "it"))
        assertEquals(SubtitleLanguageRanking("it", "fr", "en"), subtitleLanguagesAfterPlayerChoice(style(secondary = null), "it"))
        assertEquals(SubtitleLanguageRanking("it", "fr", null), subtitleLanguagesAfterPlayerChoice(style(secondary = null, tertiary = null), "it"))
    }

    @Test fun `a regional pick of a preferred language leaves the preferences alone`() {
        assertEquals(null, subtitleLanguagesAfterPlayerChoice(style(), "en-GB"))
        assertEquals(null, subtitleLanguagesAfterPlayerChoice(style(), "en-AU"))
        val off = style(preferred = "none").copy(preferredLanguageBeforeOff = "fr")
        assertEquals(SubtitleLanguageRanking("fr", "de", "en"), subtitleLanguagesAfterPlayerChoice(off, "en-CA"))
    }

    @Test fun `a pick covering a regional preference takes its place`() {
        assertEquals(SubtitleLanguageRanking("en", "fr", "de"), subtitleLanguagesAfterPlayerChoice(style(tertiary = "en-GB"), "en"))
        assertEquals(SubtitleLanguageRanking("en-GB", "fr", "de"), subtitleLanguagesAfterPlayerChoice(style(tertiary = "en-AU"), "en-GB"))
    }

    @Test fun `regional Portuguese and Spanish stay apart`() {
        assertEquals(SubtitleLanguageRanking("pt-br", "pt", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "pt"), "pt-br"))
        assertEquals(SubtitleLanguageRanking("pt", "pt-br", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "pt-br"), "pt"))
        assertEquals(SubtitleLanguageRanking("es-419", "es", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "es"), "es-419"))
        assertEquals(SubtitleLanguageRanking("es", "es-419", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "es-419"), "es"))
        assertEquals(SubtitleLanguageRanking("zh-TW", "zh-CN", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "zh-CN"), "zh-TW"))
    }

    @Test fun `plain Chinese and each Chinese script stay apart`() {
        assertEquals(SubtitleLanguageRanking("zh", "zh-TW", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "zh-TW"), "zh"))
        assertEquals(SubtitleLanguageRanking("zh", "fr", "zh-CN"), subtitleLanguagesAfterPlayerChoice(style(secondary = "zh-CN", tertiary = null), "zh"))
        assertEquals(SubtitleLanguageRanking("zh-TW", "zh", "de"), subtitleLanguagesAfterPlayerChoice(style(preferred = "zh"), "zh-TW"))
        assertEquals(null, subtitleLanguagesAfterPlayerChoice(style(preferred = "zh-TW"), "zh-TW"))
    }

    @Test fun `a pick after subtitles were turned off brings the first language back`() {
        val off = style(preferred = "none").copy(preferredLanguageBeforeOff = "fr")
        assertEquals(SubtitleLanguageRanking("fr", "de", "en"), subtitleLanguagesAfterPlayerChoice(off, "de"))
        assertEquals(SubtitleLanguageRanking("fr", "de", "en"), subtitleLanguagesAfterPlayerChoice(off, "fr"))
        assertEquals(SubtitleLanguageRanking("it", "fr", "de"), subtitleLanguagesAfterPlayerChoice(off, "it"))
        assertEquals(SubtitleLanguageRanking("de", "en", null), subtitleLanguagesAfterPlayerChoice(style(preferred = "none"), "de"))
        assertEquals(SubtitleLanguageRanking("it", "de", "en"), subtitleLanguagesAfterPlayerChoice(style(preferred = "none"), "it"))
    }

    @Test fun `show only preferred languages still lists the first language while subtitles are off`() {
        val off = style(preferred = "none").copy(preferredLanguageBeforeOff = "fr")
        val all = listOf("fr", "it", "de", "nl", "en").map(::subtitle)
        assertEquals(listOf("fr", "de", "en"), controller(off).filterToVisibleAddonSubtitles(all).map { it.lang })

        val rail = buildSubtitleLanguageRailItems(
            internalTracks = listOf(track(0, "fre"), track(1, "ger"), track(2, "ita")),
            addonSubtitles = listOf(subtitle("en")),
            preferredLanguage = off.preferredLanguage,
            secondaryPreferredLanguage = off.secondaryPreferredLanguage,
            tertiaryPreferredLanguage = off.tertiaryPreferredLanguage,
            preferredLanguageBeforeOff = off.preferredLanguageBeforeOff,
            showOnlyPreferredLanguages = true,
            currentLanguageKey = "__off__",
            noneLabel = "None",
            unknownLabel = "Unknown"
        ).map { it.key }
        assertEquals(listOf("__off__", "fr", "de", "en"), rail)
    }
}
