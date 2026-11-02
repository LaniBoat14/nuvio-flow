package com.nuvio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.dvmkv.ChaptersMetadata
import com.nuvio.tv.data.mediaserver.ServerChapter
import java.util.Locale

/** A chapter as the player shows it; [title] is null when the file only numbers its chapters. */
data class PlayerChapter(val number: Int, val startMs: Long, val title: String?)

internal object PlayerChapters {

    /** Previous goes to the start of the current chapter first when playback is further in than this. */
    const val RESTART_WINDOW_MS = 3_000L

    private const val MAX_CHAPTERS = 999
    private const val EVEN_SPACING_TOLERANCE_MS = 1_000L

    private val numberedOnly = Regex(
        "^(chapter|chap\\.?|ch\\.?|chapitre|kapitel|capitolo|cap[ií]tulo|hoofdstuk|rozdzia[lł]|глава)\\s*0*\\d+$|^第\\s*0*\\d+\\s*章$",
        RegexOption.IGNORE_CASE
    )

    private val segmentName = Regex(
        "(?iu)^(?:recap|previously|previously on\\b.*|intro|opening|opening (?:credits|titles|theme|song)|op|" +
            "vorspann|r[üu]ckblick|was bisher geschah|zuvor bei\\b.*|g[ée]n[ée]rique(?: de d[ée]but)?|r[ée]sum[ée]|" +
            "pr[ée]c[ée]demment(?: dans\\b.*)?|riassunto|sigla(?: iniziale)?|nelle puntate precedenti|" +
            "resumen|anteriormente(?: en\\b.*)?|cabecera|abertura|eerder(?: in\\b.*)?|" +
            "オープニング|あらすじ|前回のあらすじ)(?:[\\s._#-]*\\d+)?[.:!]?$"
    )

    /** Matroska files often use the ISO 639-2/B codes; Locale gives the /T ones. */
    private val bibliographic = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym"
    )

    @OptIn(UnstableApi::class)
    fun fromTracks(tracks: Tracks, locale: Locale = Locale.getDefault()): List<PlayerChapter> =
        fileChapters(tracks, locale)?.second.orEmpty()

    /** The file's chapters with the container they came from ("mkv" or "mp4"), or null when it has none. */
    @OptIn(UnstableApi::class)
    fun fileChapters(tracks: Tracks, locale: Locale = Locale.getDefault()): Pair<String, List<PlayerChapter>>? {
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val chapters = group.getTrackFormat(i).metadata?.chapters() ?: continue
                return chapters.source to fromMetadata(chapters, locale)
            }
        }
        return null
    }

    /** Every chapter of the file with its name, segments included, or null when it has none. */
    @OptIn(UnstableApi::class)
    fun namedFileChapters(tracks: Tracks, locale: Locale = Locale.getDefault()): List<PlayerChapter>? {
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val chapters = group.getTrackFormat(i).metadata?.chapters() ?: continue
                return chapters.chapters.take(MAX_CHAPTERS).mapIndexed { index, chapter ->
                    PlayerChapter(
                        index + 1,
                        chapter.startUs / 1000,
                        chosenName(chapter.names.map { name -> name.text to name.language }, locale)
                    )
                }
            }
        }
        return null
    }

    /**
     * A media server's chapters, same rules as the file's. Unnamed chapters at a fixed interval are the server's
     * own filler (a mark every few minutes), not chapters of the film, and are left out.
     */
    fun fromServer(chapters: List<ServerChapter>, locale: Locale = Locale.getDefault()): List<PlayerChapter> {
        val unique = chapters.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }.take(MAX_CHAPTERS)
        if (unique.size < 2) return emptyList()
        val names = unique.map { chapter -> chapter.name?.let { chosenName(listOf(it to null), locale) } }
        if (names.all { it == null || numberedOnly.matches(it) } && evenlySpaced(unique.map { it.startMs })) return emptyList()
        return storyChapters(unique.mapIndexed { index, chapter -> chapter.startMs to names[index] })
    }

    private fun evenlySpaced(starts: List<Long>): Boolean {
        val gaps = starts.zipWithNext { a, b -> b - a }
        return gaps.size >= 2 && gaps.max() - gaps.min() <= EVEN_SPACING_TOLERANCE_MS
    }

    @OptIn(UnstableApi::class)
    fun fromMetadata(metadata: ChaptersMetadata, locale: Locale = Locale.getDefault()): List<PlayerChapter> =
        storyChapters(metadata.chapters.map { it.startUs / 1000 to chosenName(it.names.map { name -> name.text to name.language }, locale) })

    fun fromMpv(chapters: List<PlayerChapter>): List<PlayerChapter> = storyChapters(chapters.map { it.startMs to it.title })

    /** Recaps, intros and openings are segments, not chapters: they leave; fewer than two left means no chapters. */
    private fun storyChapters(chapters: List<Pair<Long, String?>>): List<PlayerChapter> {
        val kept = chapters.filterNot { (_, name) -> name != null && isSegmentName(name) }
        if (kept.size < 2) return emptyList()
        return kept.mapIndexed { index, (startMs, name) ->
            PlayerChapter(index + 1, startMs, name?.takeUnless { numberedOnly.matches(it) })
        }
    }

    fun isSegmentName(name: String): Boolean = segmentName.matches(name.trim())

    /** The name in the viewer's language when the file has one, else the first; generic "Chapter 3" names are dropped. */
    fun pickName(names: List<Pair<String, String?>>, locale: Locale): String? =
        chosenName(names, locale)?.takeUnless { numberedOnly.matches(it) }

    private fun chosenName(names: List<Pair<String, String?>>, locale: Locale): String? {
        val usable = names.filter { (text, _) -> text.isNotBlank() }
        if (usable.isEmpty()) return null
        val wanted = setOfNotNull(
            locale.language.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() },
            runCatching { locale.isO3Language.lowercase(Locale.ROOT) }.getOrNull()?.takeIf { it.isNotEmpty() }
        )
        val chosen = usable.firstOrNull { (_, language) ->
            language?.lowercase(Locale.ROOT)?.substringBefore('-')?.let { bibliographic[it] ?: it } in wanted
        } ?: usable.first()
        return chosen.first.trim()
    }

    /** Index of the chapter playing at [positionMs], or -1 before the first one. */
    fun indexAt(chapters: List<PlayerChapter>, positionMs: Long): Int {
        var found = -1
        for (i in chapters.indices) {
            if (chapters[i].startMs <= positionMs) found = i else break
        }
        return found
    }

    fun next(chapters: List<PlayerChapter>, positionMs: Long): PlayerChapter? =
        chapters.getOrNull(indexAt(chapters, positionMs) + 1)

    fun previous(chapters: List<PlayerChapter>, positionMs: Long): PlayerChapter? {
        val index = indexAt(chapters, positionMs)
        if (index < 0) return null
        val current = chapters[index]
        return if (positionMs - current.startMs > RESTART_WINDOW_MS) current else chapters.getOrNull(index - 1) ?: current
    }

    /** Start of each chapter but the first, as a share of [durationMs], for the timeline. */
    fun timelineFractions(chapters: List<PlayerChapter>, durationMs: Long): List<Float> {
        if (durationMs <= 0L) return emptyList()
        return chapters.drop(1).map { it.startMs.toFloat() / durationMs }.filter { it > 0f && it < 1f }
    }

    @OptIn(UnstableApi::class)
    private fun Metadata.chapters(): ChaptersMetadata? {
        for (i in 0 until length()) {
            val entry = get(i)
            if (entry is ChaptersMetadata) return entry
        }
        return null
    }
}
