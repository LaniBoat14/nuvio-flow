package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.data.local.*
import com.nuvio.tv.ui.screens.player.PlayerControlDeckSize
import com.nuvio.tv.ui.screens.player.playerControlPrimaryDeckPlan
import com.nuvio.tv.ui.screens.player.playerControlPreviewFaded
import org.junit.Assert.*
import org.junit.Test

class PlayerControlVisualEditorStateTest {
    private val play = PlayerControlAction.PLAY_PAUSE
    private val next = PlayerControlAction.NEXT_EPISODE
    private val info = PlayerControlAction.INFO
    private fun original() = PlayerControlVisualEditorState(PlayerControlLayoutDraft(PlayerControlLayoutSnapshot(1, null, null)),
        offered = PlayerControlAction.offered(reportsAvailable = true))
    private fun group(s: PlayerControlVisualEditorState, a: PlayerControlAction) = s.draft.preview.entries.single { it.action == a }.group
    @Test fun `short selection toggles hidden ghost back on without changing location`() {
        val s = original().select(info)
        assertFalse(s.draft.preview.entries.single { it.action == info }.visible)
        val shown = s.toggle(); assertTrue(shown.draft.preview.entries.single { it.action == info }.visible)
        assertEquals(group(s, info), group(shown, info)); assertEquals(s.draft.preview, shown.toggle().draft.preview)
    }
    @Test fun `begin and cancel without motion preserve original null and future raw revision`() {
        val snapshot = PlayerControlLayoutSnapshot(7, "v9|future", null)
        val s = PlayerControlVisualEditorState(PlayerControlLayoutDraft(snapshot))
        assertSame(s.draft, s.beginMove().cancelMove().draft)
        assertNull(s.beginMove().place().draft.layout); assertEquals("v9|future", s.beginMove().place().draft.snapshot.serialized)
    }
    @Test fun `moving past a report button the build does not draw takes one press and keeps its saved slot`() {
        val engine = PlayerControlAction.ENGINE; val report = PlayerControlAction.REPORT
        fun right(s: PlayerControlVisualEditorState) = s.draft.preview.entries.filter { it.group == PlayerControlGroup.RIGHT }.map { it.action }
        val draft = PlayerControlLayoutDraft(PlayerControlLayoutSnapshot(1, null, null))
        val hidden = PlayerControlVisualEditorState(draft, offered = PlayerControlAction.offered(reportsAvailable = false)).select(engine).beginMove()
        val before = right(hidden)
        assertEquals(listOf(engine, report, info), before.takeLast(3))
        val moved = hidden.step(1)
        assertEquals(listOf(info, report, engine), right(moved).takeLast(3))
        assertEquals(before.indexOf(report), right(moved).indexOf(report))
        assertEquals(before, right(moved.step(-1)))
        assertTrue(moved.draft.layout!!.encode().contains("report,right,1"))
        val shown = PlayerControlVisualEditorState(draft, offered = PlayerControlAction.offered(reportsAvailable = true)).select(engine).beginMove()
        assertEquals(listOf(report, engine, info), right(shown.step(1)).takeLast(3))
    }
    @Test fun `leftmost boundary is a no op preserving original defaults`() {
        val s = original().beginMove(); assertSame(s, s.step(-1)); assertNull(s.step(-1).draft.layout)
    }
    @Test fun `rightmost boundary is a no op`() {
        val s = original().select(info).beginMove(); assertSame(s, s.step(1)); assertNull(s.step(1).draft.layout)
    }
    @Test fun `neighbour swap and place retain only provisional order until explicit save`() {
        val s = original().beginMove().step(1); assertEquals(play, s.draft.preview.entries[1].action)
        val placed = s.place(); assertFalse(placed.moving); assertEquals(s.draft, placed.draft); assertNull(s.draft.snapshot.layout)
    }
    @Test fun `right crossing enters empty centre rather than jumping over it`() {
        val s = original().select(next).beginMove().step(1)
        assertEquals(PlayerControlGroup.CENTRE, group(s, next)); assertTrue(s.moving)
    }
    @Test fun `right crossing inserts at beginning of next populated group`() {
        val s = original().select(next).beginMove().step(1).step(1)
        assertEquals(PlayerControlGroup.RIGHT, group(s, next))
        assertEquals(next, s.draft.preview.entries.first { it.group == PlayerControlGroup.RIGHT }.action)
    }
    @Test fun `left crossing inserts at end of previous group and reverse returns original order`() {
        val s = original().select(next).beginMove().step(1).step(1).step(-1).step(-1)
        assertEquals(original().draft.preview, s.draft.preview); assertEquals(next, s.selected)
    }
    @Test fun `cancel move restores earlier unsaved changes and exact snapshot`() {
        val prior = original().select(info).toggle(); val moved = prior.beginMove().step(-1).step(-1)
        val restored = moved.cancelMove(); assertSame(prior.draft, restored.draft); assertSame(prior.draft.snapshot, restored.draft.snapshot)
        assertFalse(restored.moving)
    }
    @Test fun `hidden controls move without becoming visible and all sixteen remain editable`() {
        var s = original().select(info).beginMove()
        repeat(12) { s = s.step(-1) }
        assertEquals(PlayerControlGroup.CENTRE, group(s, info)); assertFalse(s.draft.preview.entries.single { it.action == info }.visible)
        assertEquals(PlayerControlAction.entries.toSet(), s.draft.preview.entries.map { it.action }.toSet())
    }
    @Test fun `switching an unavailable action off and on moves it between the hidden row and its real place`() {
        val offered = PlayerControlAction.offered(reportsAvailable = true)
        val movie = offered - setOf(PlayerControlAction.EPISODES, next)
        fun cell(s: PlayerControlVisualEditorState) = playerControlPrimaryDeckPlan(s.draft.preview, movie,
            offered.associateWith { PlayerControlDeckSize(40, 20) }, 1280, 10, true, 15).cells.single { it.action == next }
        val on = original().select(next)
        assertTrue(playerControlPreviewFaded(on.draft.preview.entries.single { it.action == next }, movie))
        assertFalse(cell(on).hidden)
        val off = on.toggle(); assertTrue(cell(off).hidden)
        assertEquals(cell(on), cell(off.toggle()))
    }
    @Test fun `moving locks selected action and visibility`() {
        val s = original().beginMove(); assertSame(s, s.select(info)); assertSame(s, s.toggle())
    }
    @Test fun `reset exits move and restores original defaults keeping captured ownership`() {
        val s = original().toggle().beginMove().step(1).reset()
        assertFalse(s.moving); assertNull(s.draft.layout); assertEquals(1, s.draft.snapshot.profileId)
    }
    @Test fun `rejection exits move and blocks mutation`() {
        val s = original().beginMove().step(1).reject()
        assertFalse(s.moving); assertTrue(s.draft.rejected); assertSame(s, s.beginMove()); assertSame(s, s.toggle()); assertSame(s, s.reset())
    }
    @Test fun `step outside move or invalid delta cannot edit layout`() {
        val s = original(); assertSame(s, s.step(1)); val moving = s.beginMove(); assertSame(moving, moving.step(0)); assertSame(moving, moving.step(2))
    }
    @Test fun `all actions in one region remain complete during repeated cross area moves`() {
        var draft = original().draft
        PlayerControlAction.entries.forEach { draft = draft.group(it, PlayerControlGroup.LEFT) }
        var s = PlayerControlVisualEditorState(draft, info).beginMove()
        repeat(35) { s = s.step(1) }; repeat(35) { s = s.step(-1) }
        assertEquals(PlayerControlAction.entries.size, s.draft.preview.entries.size); assertEquals(PlayerControlAction.entries.size, s.draft.preview.entries.map { it.action }.distinct().size)
        assertSame(draft, s.cancelMove().draft)
    }
}

class PlayerLayoutConfirmGestureTest {
    @Test fun `short press fires once on release`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100); assertEquals(PlayerLayoutConfirmResult.CLICK, g.up(200)); assertEquals(PlayerLayoutConfirmResult.NONE, g.up(210))
    }
    @Test fun `hold without remote repeat fires once and release cannot toggle or place`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100)
        assertEquals(PlayerLayoutConfirmResult.NONE, g.timeout(649)); assertEquals(PlayerLayoutConfirmResult.HOLD, g.timeout(650))
        assertEquals(PlayerLayoutConfirmResult.NONE, g.timeout(900)); assertEquals(PlayerLayoutConfirmResult.NONE, g.up(901))
    }
    @Test fun `repeat downs do not postpone original hold time`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100); g.down(500); assertEquals(PlayerLayoutConfirmResult.HOLD, g.timeout(650))
    }
    @Test fun `release can recognize hold if timer was delayed`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100); assertEquals(PlayerLayoutConfirmResult.HOLD, g.up(1000)); assertEquals(PlayerLayoutConfirmResult.NONE, g.timeout(1001))
    }
    @Test fun `orphan release opening dialog is ignored`() {
        assertEquals(PlayerLayoutConfirmResult.NONE, PlayerLayoutConfirmGesture().up(100))
    }
    @Test fun `focus loss cancels hold and ignores its eventual release`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100); g.cancel(); assertFalse(g.pressed)
        assertEquals(PlayerLayoutConfirmResult.NONE, g.timeout(1000)); assertEquals(PlayerLayoutConfirmResult.NONE, g.up(1100))
    }
    @Test fun `next press after completed hold can place without a second hold`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100); g.timeout(700); g.up(800); g.down(900)
        assertEquals(PlayerLayoutConfirmResult.CLICK, g.up(1000))
    }
    @Test fun `cancelled timer cannot affect later shorter press`() {
        val g = PlayerLayoutConfirmGesture(); g.down(100); g.cancel(); g.down(1000)
        assertEquals(PlayerLayoutConfirmResult.NONE, g.timeout(1100)); assertEquals(PlayerLayoutConfirmResult.CLICK, g.up(1200))
    }
}
