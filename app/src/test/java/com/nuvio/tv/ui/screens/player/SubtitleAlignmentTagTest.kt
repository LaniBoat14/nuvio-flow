package com.nuvio.tv.ui.screens.player

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.SpannedString
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.subrip.SubripParser
import com.nuvio.tv.data.local.SubtitleStyleSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(UnstableApi::class)
class SubtitleAlignmentTagTest {
    private val srt = """
        1
        00:00:01,000 --> 00:00:04,000
        Plain line

        2
        00:00:05,000 --> 00:00:08,000
        <i>Italic line</i>

        3
        00:00:13,000 --> 00:00:16,000
        {\an8}Top centre

        4
        00:00:17,000 --> 00:00:20,000
        {\an7}Top left

        5
        00:00:21,000 --> 00:00:24,000
        {\an9}<i>Top right</i>

        6
        00:00:25,000 --> 00:00:27,500
        {\an2}Bottom centre

        7
        00:00:27,600 --> 00:00:29,800
        {\an8}Top with another line

        8
        00:00:27,600 --> 00:00:29,800
        Bottom with another line
    """.trimIndent() + "\n"

    private val style = SubtitleStyleSettings()
    private val authoredSize = AbsoluteSizeSpan(40)

    @Before fun setUp() {
        // Every parsed cue carries an italic StyleSpan and an authored size span.
        mockkStatic(TextUtils::class, Html::class, SpannedString::class)
        every { TextUtils.isEmpty(any()) } answers { firstArg<CharSequence?>().isNullOrEmpty() }
        every { Html.fromHtml(any<String>()) } answers { mockk<Spanned>(relaxed = true) }
        every { SpannedString.valueOf(any()) } answers { mockk<SpannedString>(relaxed = true) }
        mockkConstructor(SpannableString::class)
        every { anyConstructed<SpannableString>().getSpans(any(), any(), Any::class.java) } answers {
            arrayOf<Any>(StyleSpan(0), authoredSize)
        }
    }

    @After fun tearDown() = unmockkAll()

    private fun parsed(): List<CuesWithTiming> {
        val out = ArrayList<CuesWithTiming>()
        SubripParser().parse(srt.toByteArray(), SubtitleParser.OutputOptions.allCues()) { out += it }
        return out
    }

    private fun embedded(timed: List<CuesWithTiming>, seconds: Double): List<Cue> {
        val presentation = SubtitlePresentation()
        val processed = collectActiveSidecarCues(timed, (seconds * 1_000_000).toLong()).map { cue ->
            var c = SubtitleMojibakeSanitizer.sanitizeCue(cue)
            c = PlayerSubtitleRtlFix.fixCueText(c, true)
            c = PlayerSubtitleUtils.normalizeBottomCuePosition(c)
            presentation.apply(c, style, false, false)
        }
        return PlayerSubtitleUtils.mergeOverlappingCues(processed)
    }

    private fun sidecar(timed: List<CuesWithTiming>, seconds: Double): List<Cue> {
        val presentation = SubtitlePresentation()
        val active = collectActiveSidecarCues(timed, (seconds * 1_000_000).toLong())
        val sanitized = active.map { SubtitleMojibakeSanitizer.sanitizeCue(it) }
        return PlayerSubtitleUtils.mergeOverlappingCues(sanitized).map { presentation.apply(it, style, false, false) }
    }

    private fun assertTop(cue: Cue, position: Float, positionAnchor: Int) {
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
        assertEquals(.08f, cue.line, .0001f)
        assertEquals(Cue.ANCHOR_TYPE_START, cue.lineAnchor)
        assertEquals(position, cue.position, .0001f)
        assertEquals(positionAnchor, cue.positionAnchor)
    }

    private fun assertFollowsPositionSetting(cue: Cue) {
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(Cue.TYPE_UNSET, cue.lineType)
    }

    // Addon SRT cues are only normalised for WebVTT, so an2 stays at Media3's own bottom line.
    private fun assertBottom(cue: Cue) {
        if (cue.line == Cue.DIMEN_UNSET) return
        assertEquals(Cue.LINE_TYPE_FRACTION, cue.lineType)
        assertEquals(.92f, cue.line, .0001f)
        assertEquals(Cue.ANCHOR_TYPE_END, cue.lineAnchor)
    }

    @Test fun `alignment tags survive the embedded and the addon subtitle paths`() {
        val timed = parsed()
        assertEquals(8, timed.size)
        val sidecarCues = parseSidecarTimedCuesRobust(srt, "https://example.org/sub.srt")
        assertEquals("media3", sidecarCues.source)
        for ((name, path) in listOf<Pair<String, (Double) -> List<Cue>>>(
            "embedded" to { s -> embedded(timed, s) },
            "addon" to { s -> sidecar(sidecarCues.cues, s) }
        )) {
            assertFollowsPositionSetting(path(2.0).single())
            if (name == "embedded") assertFollowsPositionSetting(path(26.0).single())
            assertTop(path(14.0).single(), .5f, Cue.ANCHOR_TYPE_MIDDLE)
            assertTop(path(18.0).single(), .08f, Cue.ANCHOR_TYPE_START)
            assertTop(path(22.0).single(), .92f, Cue.ANCHOR_TYPE_END)
            assertBottom(path(26.0).single())
            val both = path(28.5)
            assertEquals(name, 2, both.size)
            assertTop(both.single { it.line != Cue.DIMEN_UNSET }, .5f, Cue.ANCHOR_TYPE_MIDDLE)
            assertTrue(name, both.any { it.line == Cue.DIMEN_UNSET })
        }
        verify { anyConstructed<SpannableString>().removeSpan(authoredSize) }
        verify(exactly = 0) { anyConstructed<SpannableString>().removeSpan(ofType(StyleSpan::class)) }
    }
}
