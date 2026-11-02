package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import com.nuvio.tv.data.mediaserver.ServerChapter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerChaptersTest {

    private val chapters = listOf(
        PlayerChapter(1, 0L, "Overture"),
        PlayerChapter(2, 60_000L, "One Day More"),
        PlayerChapter(3, 120_000L, null),
    )

    @Test
    fun `current chapter follows the position`() {
        assertEquals(0, PlayerChapters.indexAt(chapters, 0L))
        assertEquals(0, PlayerChapters.indexAt(chapters, 59_999L))
        assertEquals(1, PlayerChapters.indexAt(chapters, 60_000L))
        assertEquals(2, PlayerChapters.indexAt(chapters, 500_000L))
        assertEquals(-1, PlayerChapters.indexAt(chapters.drop(1), 10_000L))
    }

    @Test
    fun `next goes to the following chapter and stops at the last`() {
        assertEquals(2, PlayerChapters.next(chapters, 5_000L)?.number)
        assertEquals(3, PlayerChapters.next(chapters, 61_000L)?.number)
        assertNull(PlayerChapters.next(chapters, 130_000L))
        assertEquals(2, PlayerChapters.next(chapters.drop(1), 10_000L)?.number)
    }

    @Test
    fun `previous restarts the chapter after three seconds, else goes one back`() {
        assertEquals(2, PlayerChapters.previous(chapters, 70_000L)?.number)
        assertEquals(1, PlayerChapters.previous(chapters, 62_000L)?.number)
        assertEquals(1, PlayerChapters.previous(chapters, 1_000L)?.number)
        assertNull(PlayerChapters.previous(chapters.drop(1), 10_000L))
    }

    @Test
    fun `timeline gaps leave out the first chapter`() {
        assertEquals(listOf(0.25f, 0.5f), PlayerChapters.timelineFractions(chapters, 240_000L))
        assertEquals(emptyList<Float>(), PlayerChapters.timelineFractions(chapters, 0L))
    }

    @Test
    fun `name in the viewer's language wins and numbered names are dropped`() {
        val names = listOf("Overture" to "eng", "Ouverture" to "fre", "Ouvertüre" to "de")
        assertEquals("Ouverture", PlayerChapters.pickName(names, Locale.FRENCH))
        assertEquals("Ouvertüre", PlayerChapters.pickName(names, Locale.GERMAN))
        assertEquals("Overture", PlayerChapters.pickName(names, Locale.JAPANESE))
        assertNull(PlayerChapters.pickName(listOf("Chapter 01" to "eng"), Locale.ENGLISH))
        assertNull(PlayerChapters.pickName(listOf("第 01 章" to "en"), Locale.ENGLISH))
        assertNull(PlayerChapters.pickName(listOf("Kapitel 3" to "ger"), Locale.GERMAN))
        assertEquals("Scene 1", PlayerChapters.pickName(listOf("Scene 1" to "und"), Locale.ENGLISH))
        assertNull(PlayerChapters.pickName(emptyList(), Locale.ENGLISH))
    }

    @Test
    fun `metadata becomes numbered chapters in milliseconds`() {
        val metadata = ChaptersMetadata(
            listOf(
                ChaptersMetadata.Chapter(0L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("Prologue", "eng"))),
                ChaptersMetadata.Chapter(90_500_000L, C.TIME_UNSET, emptyList()),
            )
        )
        assertEquals(
            listOf(PlayerChapter(1, 0L, "Prologue"), PlayerChapter(2, 90_500L, null)),
            PlayerChapters.fromMetadata(metadata, Locale.ENGLISH)
        )
    }

    @Test
    fun `server chapters are sorted, numbered and need two`() {
        val server = listOf(
            ServerChapter(600_000L, "The Harbour"),
            ServerChapter(0L, "Arrival"),
            ServerChapter(600_000L, "Duplicate"),
            ServerChapter(-5L, "Before the start"),
            ServerChapter(1_500_000L, "Chapter 3"),
        )
        assertEquals(
            listOf(PlayerChapter(1, 0L, "Arrival"), PlayerChapter(2, 600_000L, "The Harbour"), PlayerChapter(3, 1_500_000L, null)),
            PlayerChapters.fromServer(server, Locale.ENGLISH)
        )
        assertEquals(emptyList<PlayerChapter>(), PlayerChapters.fromServer(listOf(ServerChapter(0L, "Arrival")), Locale.ENGLISH))
    }

    @Test
    fun `unnamed server chapters at a fixed interval are left out`() {
        val filler = (0 until 6).map { ServerChapter(it * 300_000L, if (it % 2 == 0) null else "Chapter ${it + 1}") }
        assertEquals(emptyList<PlayerChapter>(), PlayerChapters.fromServer(filler, Locale.ENGLISH))

        val uneven = listOf(ServerChapter(0L, null), ServerChapter(250_000L, null), ServerChapter(910_000L, null))
        assertEquals(3, PlayerChapters.fromServer(uneven, Locale.ENGLISH).size)

        val named = filler.mapIndexed { index, chapter -> chapter.copy(name = "Part ${'A' + index}") }
        assertEquals(6, PlayerChapters.fromServer(named, Locale.ENGLISH).size)
    }

    @Test
    fun `recaps, intros and openings are segments, credits and previews are not`() {
        val segments = listOf(
            "Recap", "previously", "Previously on The Harbour", "Intro", "INTRO 2", "Opening", "Opening 1", "Opening Credits",
            "OP", "OP2", "Vorspann", "Rückblick", "Générique", "GÉNÉRIQUE", "Résumé", "Riassunto", "Resumen", "Précédemment dans la série",
        )
        segments.forEach { assertTrue(it, PlayerChapters.isSegmentName(it)) }
        val chapters = listOf(
            "Credits", "End Credits", "Ending", "Preview", "Next time", "The Opening Night", "Intro to the Harbour", "Opera",
            "Operation", "Chapter 1", "Introduction", "Recapture",
        )
        chapters.forEach { assertFalse(it, PlayerChapters.isSegmentName(it)) }
    }

    @Test
    fun `hidden segments leave every source and the rest is renumbered`() {
        val metadata = ChaptersMetadata(
            listOf(
                ChaptersMetadata.Chapter(0L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("Previously on", "eng"))),
                ChaptersMetadata.Chapter(60_000_000L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("Opening", "eng"))),
                ChaptersMetadata.Chapter(150_000_000L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("The Harbour", "eng"))),
                ChaptersMetadata.Chapter(900_000_000L, C.TIME_UNSET, emptyList()),
                ChaptersMetadata.Chapter(1_500_000_000L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("End Credits", "eng"))),
            ),
            ChaptersMetadata.SOURCE_MP4
        )
        assertEquals(
            listOf(PlayerChapter(1, 150_000L, "The Harbour"), PlayerChapter(2, 900_000L, null), PlayerChapter(3, 1_500_000L, "End Credits")),
            PlayerChapters.fromMetadata(metadata, Locale.ENGLISH)
        )
        val server = listOf(ServerChapter(0L, "Recap"), ServerChapter(90_000L, "Intro"), ServerChapter(200_000L, "Night Train"), ServerChapter(800_000L, "Credits"))
        assertEquals(listOf(200_000L, 800_000L), PlayerChapters.fromServer(server, Locale.ENGLISH).map { it.startMs })
    }

    @Test
    fun `fewer than two chapters left after hiding means none`() {
        val metadata = ChaptersMetadata(
            listOf(
                ChaptersMetadata.Chapter(0L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("Intro", "eng"))),
                ChaptersMetadata.Chapter(90_000_000L, C.TIME_UNSET, listOf(ChaptersMetadata.Name("Episode", "eng"))),
            )
        )
        assertEquals(emptyList<PlayerChapter>(), PlayerChapters.fromMetadata(metadata, Locale.ENGLISH))
        assertEquals(
            emptyList<PlayerChapter>(),
            PlayerChapters.fromServer(listOf(ServerChapter(0L, "Opening"), ServerChapter(80_000L, "Main")), Locale.ENGLISH)
        )
    }

    @Test
    fun `mpv chapters drop recap segments and renumber`() {
        val mpv = listOf(
            PlayerChapter(1, 0L, "Recap"),
            PlayerChapter(2, 90_000L, "Arrival"),
            PlayerChapter(3, 600_000L, "Chapter 3"),
        )
        assertEquals(
            listOf(PlayerChapter(1, 90_000L, "Arrival"), PlayerChapter(2, 600_000L, null)),
            PlayerChapters.fromMpv(mpv)
        )
    }
}
