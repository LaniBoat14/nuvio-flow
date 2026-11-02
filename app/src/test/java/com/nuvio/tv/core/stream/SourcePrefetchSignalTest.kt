package com.nuvio.tv.core.stream

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridStreamAudioChannel
import com.nuvio.tv.domain.model.DebridStreamAudioTag
import com.nuvio.tv.domain.model.DebridStreamEncode
import com.nuvio.tv.domain.model.DebridStreamQuality
import com.nuvio.tv.domain.model.DebridStreamResolution
import com.nuvio.tv.domain.model.DebridStreamVisualTag
import com.nuvio.tv.domain.model.StreamBadge
import org.junit.Assert.*
import org.junit.Test

class SourcePrefetchSignalTest {
    @Test fun `size setting hides known size and invalid sizes never display`() {
        val signal = SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 1024)
        assertEquals(1024L, signal.withFileSizeVisibility(true).fileSizeBytes)
        assertNull(signal.withFileSizeVisibility(false).fileSizeBytes)
        listOf(null, 0L, -1L).forEach { size -> assertNull(signal.copy(fileSizeBytes = size).withFileSizeVisibility(true).fileSizeBytes) }
    }

    @Test fun `line switch keeps the phase sign and the group switch drops only the chip`() {
        val facts = DirectDebridStreamFilter.StreamFacts(
            DebridStreamResolution.P2160, DebridStreamQuality.BLURAY_REMUX,
            listOf(DebridStreamVisualTag.DV), listOf(DebridStreamAudioTag.TRUEHD, DebridStreamAudioTag.ATMOS),
            listOf(DebridStreamAudioChannel.CH_7_1), DebridStreamEncode.HEVC, emptyList(), "FraMeSToR", 1024,
            0, 0, 0, 0, 0, 0, 0, 0
        )
        val fusion = listOf(StreamBadge(name = "4K"))
        val signal = SourcePrefetchSignal("a", SourcePrefetchPhase.READY, facts, fusion, fileSizeBytes = 1024)
        assertSame(signal, signal.withSourceLineVisibility(showLine = true, showReleaseGroup = true))
        listOf(true, false).forEach { group ->
            val ready = signal.withSourceLineVisibility(showLine = false, showReleaseGroup = group)
            assertEquals(SourcePrefetchSignal("a", SourcePrefetchPhase.READY, null, badgesHidden = true), ready)
        }
        val ranked = signal.copy(phase = SourcePrefetchPhase.RANKED).withSourceLineVisibility(false, true)
        assertEquals(SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, badgesHidden = true), ranked)
        val noGroup = signal.withSourceLineVisibility(showLine = true, showReleaseGroup = false)
        assertEquals("", noGroup?.facts?.releaseGroup)
        assertEquals(signal.copy(facts = facts.copy(releaseGroup = "")), noGroup)
        assertEquals(1024L, noGroup?.fileSizeBytes)
        assertFalse(noGroup!!.badgesHidden)
        val searching = SourcePrefetchSignal("a", SourcePrefetchPhase.SEARCHING, null)
        assertSame(searching, searching.withSourceLineVisibility(showLine = true, showReleaseGroup = false))
        assertEquals(searching.copy(badgesHidden = true), searching.withSourceLineVisibility(showLine = false, showReleaseGroup = false))
        val none: SourcePrefetchSignal? = null
        assertNull(none.withSourceLineVisibility(showLine = true, showReleaseGroup = true))
        assertNull(none.withSourceLineVisibility(showLine = false, showReleaseGroup = true))
    }

    @Test fun `tick shows for a ranked pick only when the badges are hidden`() {
        fun tick(phase: SourcePrefetchPhase, hidden: Boolean) =
            SourcePrefetchSignal("a", phase, null, badgesHidden = hidden).showsReadyTick
        assertFalse(tick(SourcePrefetchPhase.SEARCHING, hidden = false))
        assertFalse(tick(SourcePrefetchPhase.RANKED, hidden = false))
        assertTrue(tick(SourcePrefetchPhase.READY, hidden = false))
        assertFalse(tick(SourcePrefetchPhase.EMPTY, hidden = false))
        assertFalse(tick(SourcePrefetchPhase.SEARCHING, hidden = true))
        assertTrue(tick(SourcePrefetchPhase.RANKED, hidden = true))
        assertTrue(tick(SourcePrefetchPhase.READY, hidden = true))
        assertFalse(tick(SourcePrefetchPhase.EMPTY, hidden = true))
        val ranked = SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 1024)
        assertTrue(ranked.withSourceLineVisibility(showLine = false, showReleaseGroup = true)!!.showsReadyTick)
        assertFalse(ranked.withSourceLineVisibility(showLine = true, showReleaseGroup = true)!!.showsReadyTick)
    }

    @Test fun `new pick clears old size and late A results cannot replace a new visit to A`() {
        val store = SourcePrefetchSignalStore()
        val first = store.begin("a")
        store.publish(first, SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 100))
        val second = store.begin("b")
        assertNull(store.signals.value?.fileSizeBytes)
        store.publish(second, SourcePrefetchSignal("b", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 200))
        store.ready(first)
        assertEquals(SourcePrefetchPhase.RANKED, store.signals.value?.phase)
        val third = store.begin("a")
        store.publish(first, SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 100))
        assertNull(store.signals.value?.fileSizeBytes)
        store.publish(third, SourcePrefetchSignal("a", SourcePrefetchPhase.RANKED, null, fileSizeBytes = 300))
        store.ready(third)
        assertEquals(300L, store.signals.value?.fileSizeBytes)
        assertEquals(SourcePrefetchPhase.READY, store.signals.value?.phase)
    }
}
