package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackRowTextTest {

    private fun row(track: TrackInfo, languageName: String? = "English") = audioTrackRowText(
        track = track,
        languageName = languageName,
        fallbackTitle = "Audio ${track.index + 1}",
        commentaryLabel = "Commentary",
        audioDescriptionLabel = "Audio description"
    )

    private fun exoTrack(
        title: String?,
        codec: String? = "AC-3",
        channels: Int? = 2,
        sampleRate: Int? = 48_000,
        role: TrackRole? = null,
        index: Int = 0
    ) = TrackInfo(
        index = index,
        name = "${title ?: "English"} (${
            listOfNotNull(codec, channels?.let { CustomDefaultTrackNameProvider.getChannelLayoutName(it) })
                .joinToString(" ")
        })",
        language = "en",
        codec = codec,
        channelCount = channels,
        sampleRate = sampleRate,
        title = title,
        role = role
    )

    @Test
    fun `file title is the main line and details go below`() {
        val text = row(exoTrack(title = "Commentary with the director"))
        assertEquals("Commentary with the director", text.title)
        assertEquals("English · AC-3 · Stereo · 48 kHz", text.subtitle)
    }

    @Test
    fun `language is not repeated when the title already names it`() {
        val text = row(exoTrack(title = "English DTS-HD MA 7.1 (Original)", codec = "DTS-HD", channels = 8))
        assertEquals("English DTS-HD MA 7.1 (Original)", text.title)
        assertEquals("DTS-HD · 7.1 · 48 kHz", text.subtitle)
    }

    @Test
    fun `no title falls back to the language and the flag adds the role`() {
        val text = row(exoTrack(title = null, role = TrackRole.COMMENTARY))
        assertEquals("English · Commentary", text.title)
        assertEquals("AC-3 · Stereo · 48 kHz", text.subtitle)
    }

    @Test
    fun `role is not doubled when the title already says it`() {
        assertEquals(
            "Audio Commentary with cast and crew",
            row(exoTrack(title = "Audio Commentary with cast and crew", role = TrackRole.COMMENTARY)).title
        )
        assertEquals(
            "Descriptive Audio (English)",
            row(exoTrack(title = "Descriptive Audio (English)", role = TrackRole.AUDIO_DESCRIPTION)).title
        )
    }

    @Test
    fun `audio description flag is named on a plain title`() {
        val text = row(exoTrack(title = "English 5.1", channels = 6, role = TrackRole.AUDIO_DESCRIPTION))
        assertEquals("English 5.1 · Audio description", text.title)
        assertEquals("AC-3 · 5.1 · 48 kHz", text.subtitle)
    }

    @Test
    fun `hearing impaired and original roles add no tag`() {
        assertEquals("Main", row(exoTrack(title = "Main", role = TrackRole.HEARING_IMPAIRED)).title)
        assertEquals("Main", row(exoTrack(title = "Main", role = TrackRole.ORIGINAL)).title)
    }

    @Test
    fun `no title and no language uses the numbered fallback`() {
        val text = row(exoTrack(title = null, index = 2), languageName = null)
        assertEquals("Audio 3", text.title)
        assertEquals("AC-3 · Stereo · 48 kHz", text.subtitle)
    }

    @Test
    fun `channel counts read as layouts`() {
        assertEquals("AC-3 · Mono", row(exoTrack("x", channels = 1, sampleRate = null), null).subtitle)
        assertEquals("AC-3 · 5.1", row(exoTrack("x", channels = 6, sampleRate = null), null).subtitle)
        assertEquals("AC-3 · 7.1", row(exoTrack("x", channels = 8, sampleRate = null), null).subtitle)
        assertEquals("AC-3 · 3ch", row(exoTrack("x", channels = 3, sampleRate = null), null).subtitle)
    }

    @Test
    fun `mpv codec names get the usual spelling`() {
        assertEquals("AC-3", audioCodecDisplayName("ac3"))
        assertEquals("E-AC-3", audioCodecDisplayName("eac3"))
        assertEquals("TrueHD", audioCodecDisplayName("truehd"))
        assertEquals("DTS", audioCodecDisplayName("dts"))
        assertEquals("PCM", audioCodecDisplayName("pcm_s24le"))
        assertEquals("FLAC", audioCodecDisplayName("flac"))
        assertEquals("DTS-HD", audioCodecDisplayName("DTS-HD"))
        assertEquals("wmapro", audioCodecDisplayName("wmapro"))
        assertNull(audioCodecDisplayName(" "))
    }

    @Test
    fun `mpv track with title`() {
        val track = TrackInfo(
            index = 1,
            name = "Commentary by Ridley Scott (ac3 2ch)",
            language = "eng",
            codec = "ac3",
            channelCount = 2,
            title = "Commentary by Ridley Scott"
        )
        val text = row(track)
        assertEquals("Commentary by Ridley Scott", text.title)
        assertEquals("English · AC-3 · Stereo", text.subtitle)
    }

    @Test
    fun `sample rates`() {
        assertEquals("48 kHz", sampleRateLabel(48_000))
        assertEquals("44.1 kHz", sampleRateLabel(44_100))
        assertEquals("96 kHz", sampleRateLabel(96_000))
        assertNull(sampleRateLabel(0))
    }

    @Test
    fun `server track without details keeps its own label`() {
        val track = TrackInfo(index = 0, name = "English - Dolby Digital - 5.1 - Default", language = "eng")
        val text = row(track)
        assertEquals("English - Dolby Digital - 5.1 - Default", text.title)
        assertNull(text.subtitle)
    }

    @Test
    fun `server track language shows below a label that lacks it`() {
        val track = TrackInfo(index = 0, name = "Dolby Digital 5.1", language = "eng")
        assertEquals("English", row(track).subtitle)
    }

    @Test
    fun `role words in other languages are not doubled`() {
        assertEquals("Kommentar", row(exoTrack(title = "Kommentar", role = TrackRole.COMMENTARY)).title)
        assertEquals("Comentarios", row(exoTrack(title = "Comentarios", role = TrackRole.COMMENTARY)).title)
        assertEquals(
            "Audiodeskription",
            row(exoTrack(title = "Audiodeskription", role = TrackRole.AUDIO_DESCRIPTION)).title
        )
    }

    @Test
    fun `role flags map to roles`() {
        assertEquals(TrackRole.COMMENTARY, trackRoleFromRoleFlags(C.ROLE_FLAG_COMMENTARY, null))
        assertEquals(
            TrackRole.COMMENTARY,
            trackRoleFromRoleFlags(C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO, null)
        )
        assertEquals(TrackRole.AUDIO_DESCRIPTION, trackRoleFromRoleFlags(C.ROLE_FLAG_DESCRIBES_VIDEO, null))
        assertEquals(
            TrackRole.HEARING_IMPAIRED,
            trackRoleFromRoleFlags(C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, null)
        )
        assertEquals(TrackRole.ORIGINAL, trackRoleFromRoleFlags(C.ROLE_FLAG_MAIN, MimeTypes.VIDEO_MATROSKA))
        assertNull(trackRoleFromRoleFlags(C.ROLE_FLAG_MAIN, MimeTypes.APPLICATION_M3U8))
        assertNull(trackRoleFromRoleFlags(0, MimeTypes.VIDEO_MATROSKA))
    }
}
