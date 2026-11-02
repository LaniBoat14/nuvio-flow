package com.nuvio.tv.ui.screens.detail

import com.nuvio.tv.domain.model.NextToWatch
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextToWatchEmptyDataGuardTest {

    private val episodes = (1..10).map { 1 to it }

    private class Screen {
        var nextToWatch: NextToWatch? = null
        var fromData = false

        fun update(candidate: NextToWatch, candidateFromData: Boolean) {
            if (!shouldReplaceNextToWatch(nextToWatch, fromData, candidate, candidateFromData)) {
                if (candidate == nextToWatch && candidateFromData) fromData = true
                return
            }
            nextToWatch = candidate
            fromData = candidateFromData
        }
    }

    private class FakeRemoteProvider {
        val remote = MutableStateFlow<Map<Pair<Int, Int>, WatchProgress>>(emptyMap())

        fun episodeProgress(): Flow<Map<Pair<Int, Int>, WatchProgress>> =
            remote.onStart { emit(emptyMap()) }
    }

    @Test
    fun `remote refine reading the empty seed keeps next S1 E7`() = runTest {
        val provider = FakeRemoteProvider()
        val screen = Screen()

        provider.remote.value = completed(1..6)
        val observed = provider.remote.value
        screen.update(target(observed, emptySet()), hasNextToWatchData(observed, emptySet()))
        assertEquals(7, screen.nextToWatch?.nextEpisode)

        val oneShot = provider.episodeProgress().first()
        assertTrue(oneShot.isEmpty())
        screen.update(target(oneShot, emptySet()), hasNextToWatchData(oneShot, emptySet()))

        assertEquals(1, screen.nextToWatch?.nextSeason)
        assertEquals(7, screen.nextToWatch?.nextEpisode)
        assertEquals("Next S1 E7", screen.nextToWatch?.displayText)
    }

    @Test
    fun `snapshot inputs prefer the observed state over the empty seed`() = runTest {
        val provider = FakeRemoteProvider()
        provider.remote.value = completed(1..6)

        val (progress, watched) = pickNextToWatchInputs(
            observedProgress = provider.remote.value,
            observedWatched = (1..6).map { 1 to it }.toSet(),
            readProgress = { provider.episodeProgress().first() },
            readWatched = { emptySet() }
        )

        assertEquals(6, progress.size)
        assertEquals(7, target(progress, watched).nextEpisode)
    }

    @Test
    fun `snapshot inputs fall back to a read when nothing is observed yet`() = runTest {
        val local = completed(1..3)
        val (progress, watched) = pickNextToWatchInputs(
            observedProgress = emptyMap(),
            observedWatched = emptySet(),
            readProgress = { local },
            readWatched = { setOf(1 to 1) }
        )
        assertEquals(local, progress)
        assertEquals(setOf(1 to 1), watched)
    }

    @Test
    fun `media server states count as data and keep next S1 E7`() {
        val screen = Screen()
        val watched = (1..6).map { 1 to it }.toSet()
        screen.update(target(emptyMap(), watched), hasNextToWatchData(emptyMap(), watched))
        assertEquals(7, screen.nextToWatch?.nextEpisode)

        screen.update(target(emptyMap(), emptySet()), hasNextToWatchData(emptyMap(), emptySet()))
        assertEquals(7, screen.nextToWatch?.nextEpisode)

        val serverPlayed = (1..8).map { 1 to it }.toSet()
        assertTrue(hasNextToWatchData(emptyMap(), emptySet(), serverStateCount = 8))
        screen.update(target(emptyMap(), serverPlayed), hasNextToWatchData(emptyMap(), emptySet(), serverStateCount = 8))
        assertEquals(9, screen.nextToWatch?.nextEpisode)
    }

    @Test
    fun `new progress still moves the target`() {
        val screen = Screen()
        screen.update(target(completed(1..6), emptySet()), true)
        assertEquals(7, screen.nextToWatch?.nextEpisode)

        val moved = completed(1..7)
        screen.update(target(moved, emptySet()), hasNextToWatchData(moved, emptySet()))
        assertEquals(8, screen.nextToWatch?.nextEpisode)
    }

    @Test
    fun `unmarking episodes moves the target back`() {
        val screen = Screen()
        screen.update(target(emptyMap(), (1..6).map { 1 to it }.toSet()), true)
        assertEquals(7, screen.nextToWatch?.nextEpisode)

        val afterUnmark = (1..3).map { 1 to it }.toSet()
        screen.update(target(emptyMap(), afterUnmark), hasNextToWatchData(emptyMap(), afterUnmark))
        assertEquals(4, screen.nextToWatch?.nextEpisode)
    }

    @Test
    fun `a default from empty data can still replace another default`() {
        val screen = Screen()
        screen.update(target(emptyMap(), emptySet()), false)
        val other = NextToWatch(null, false, "s1e2", 1, 2, "Play S1 E2")
        assertTrue(shouldReplaceNextToWatch(screen.nextToWatch, screen.fromData, other, false))
        screen.update(other, false)
        assertEquals(2, screen.nextToWatch?.nextEpisode)
    }

    @Test
    fun `first target is always taken`() {
        val candidate = NextToWatch(null, false, "s1e1", 1, 1, "Play S1 E1")
        assertTrue(shouldReplaceNextToWatch(null, false, candidate, false))
        assertFalse(shouldReplaceNextToWatch(candidate, true, candidate, true))
    }

    private fun target(
        progress: Map<Pair<Int, Int>, WatchProgress>,
        watched: Set<Pair<Int, Int>>
    ): NextToWatch {
        val done = progress.filterValues { it.isCompleted() }.keys + watched
        val next = episodes.firstOrNull { it !in done } ?: episodes.last()
        val label = if (done.isEmpty()) "Play" else "Next"
        return NextToWatch(
            watchProgress = null,
            isResume = false,
            nextVideoId = "s${next.first}e${next.second}",
            nextSeason = next.first,
            nextEpisode = next.second,
            displayText = "$label S${next.first} E${next.second}"
        )
    }

    private fun completed(range: IntRange): Map<Pair<Int, Int>, WatchProgress> =
        range.associate { e ->
            (1 to e) to WatchProgress(
                contentId = "tt4236770",
                contentType = "series",
                name = "Show",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "s1e$e",
                season = 1,
                episode = e,
                episodeTitle = null,
                position = 1000L,
                duration = 1000L,
                lastWatched = e.toLong(),
                progressPercent = 100f
            )
        }
}
