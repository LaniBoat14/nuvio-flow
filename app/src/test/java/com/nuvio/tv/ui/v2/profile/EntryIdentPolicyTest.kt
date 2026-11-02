package com.nuvio.tv.ui.v2.profile

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import com.nuvio.tv.ui.brand.ArcIntroStage
import com.nuvio.tv.ui.v2.profile.EntryIdentPolicy.FirstFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntryIdentPolicyTest {
    @Test fun stillMarkExitOpacityRunsTheFullExitTime() {
        assertEquals(1f, EntryIdentPolicy.exitOpacity(0L), 0f)
        val halfWay = EntryIdentPolicy.exitOpacity(EntryIdentPolicy.ExitMs / 2)
        assertTrue(halfWay > 0f && halfWay < 1f)
        assertEquals(0f, EntryIdentPolicy.exitOpacity(EntryIdentPolicy.ExitMs), 0f)
    }

    @Test fun stillMarkFadeEndsOnRealTimeAtTenTimesSlowerAnimations() = runTest {
        val frames = BroadcastFrameClock()
        val slow = object : MotionDurationScale {
            override val scaleFactor = 10f
        }
        var opacity = 1f
        val fade = launch(frames + slow) { fadeOutOnFrameClock { opacity = it } }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        frames.sendFrame((EntryIdentPolicy.ExitMs - 1) * 1_000_000L)
        runCurrent()
        assertTrue(fade.isActive)
        assertTrue(opacity > 0f)
        frames.sendFrame(EntryIdentPolicy.ExitMs * 1_000_000L)
        runCurrent()
        assertTrue(fade.isCompleted)
        assertEquals(0f, opacity, 0f)
    }

    @Test fun stillMarkLeavesAtOnceWithAnimationsOff() = runTest {
        val frames = BroadcastFrameClock()
        val off = object : MotionDurationScale {
            override val scaleFactor = 0f
        }
        var opacity = 1f
        val fade = launch(frames + off) { fadeOutOnFrameClock { opacity = it } }
        runCurrent()
        frames.sendFrame(0L)
        runCurrent()
        frames.sendFrame(16_000_000L)
        runCurrent()
        assertTrue(fade.isCompleted)
        assertEquals(0f, opacity, 0f)
    }

    @Test fun disabledIntroSkipsOverlayWithoutReplayingOnOrdinaryNavigation() {
        val state = ProfileEntryState()
        state.start(enabled = false)
        assertFalse(state.pending)
        assertTrue(state.hasRunThisSession)
        state.start(enabled = true)
        assertTrue(state.pending)
        assertFalse(state.homeReady)
        assertFalse(state.revealing)
    }

    @Test fun remoteRevealWaitsForHomeAndKeepsOverlayUntilFadeCompletes() {
        val state = ProfileEntryState()
        state.start()
        state.requestReveal()
        assertFalse(state.revealRequested)
        state.homeReady = true
        state.requestReveal()
        assertTrue(state.revealRequested)
        assertTrue(state.pending)
        state.finish()
        assertFalse(state.pending)
        state.start()
        assertFalse(state.revealRequested)
    }

    @Test fun cachedHomeCompletesOnlyAfterNaturalReveal() {
        assertFalse(EntryIdentPolicy.shouldFinish(0, true))
        assertFalse(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs - 1, true))
        assertTrue(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs, true))
    }

    @Test fun slowHomeWaitsForReadinessButNeverHidesRecoveryIndefinitely() {
        assertFalse(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs, false))
        assertTrue(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs, true))
        assertFalse(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.RecoveryTimeoutMs - 1, false))
        assertTrue(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.RecoveryTimeoutMs, false))
    }

    @Test fun selectingAnotherProfileInvalidatesPreviousHomeReadiness() {
        val state = ProfileEntryState()
        state.start()
        assertFalse(state.hasRunThisSession)
        state.markShown()
        assertTrue(state.hasRunThisSession)
        state.homeReady = true
        state.finish()
        state.start()
        assertTrue(state.pending)
        assertFalse(state.homeReady)
    }

    @Test fun introCutBeforeItsFirstFrameStartsAgainOnEntry() {
        val state = ProfileEntryState()
        state.start(enabled = true)
        assertFalse(state.shouldStartOnEntry())
        // Home leaves composition and comes back before the intro drew anything.
        state.finish()
        assertFalse(state.hasRunThisSession)
        assertTrue(state.shouldStartOnEntry())
        state.start(enabled = true)
        assertTrue(state.pending)
    }

    @Test fun introThatWasShownIsNotReplayedOnEntry() {
        val state = ProfileEntryState()
        state.start(enabled = true)
        state.markShown()
        state.finish()
        assertTrue(state.hasRunThisSession)
        assertFalse(state.shouldStartOnEntry())
    }

    @Test fun markShownWithoutAPendingIntroChangesNothing() {
        val state = ProfileEntryState()
        state.markShown()
        assertFalse(state.hasRunThisSession)
        assertTrue(state.shouldStartOnEntry())
    }

    @Test fun switchedOffIntroCountsAsRunAtOnce() {
        val state = ProfileEntryState()
        state.start(enabled = false)
        assertFalse(state.shouldStartOnEntry())
    }

    @Test fun introStillBeingPreparedIsWaitedForUntilTheLimit() {
        for (stage in listOf(null, ArcIntroStage.Waiting, ArcIntroStage.Preparing)) {
            assertEquals(FirstFrame.Wait, EntryIdentPolicy.firstFrame(1_500, stage))
            assertEquals(FirstFrame.Wait, EntryIdentPolicy.firstFrame(EntryIdentPolicy.FirstFrameLimitMs - 1, stage))
            assertEquals(FirstFrame.Fallback, EntryIdentPolicy.firstFrame(EntryIdentPolicy.FirstFrameLimitMs, stage))
        }
    }

    @Test fun artReadyAtTheLimitGetsAShortGraceForItsFirstFrame() {
        val limit = EntryIdentPolicy.FirstFrameLimitMs
        assertEquals(FirstFrame.Wait, EntryIdentPolicy.firstFrame(limit, ArcIntroStage.Ready))
        assertEquals(FirstFrame.Wait, EntryIdentPolicy.firstFrame(limit + EntryIdentPolicy.ReadyGraceMs - 1, ArcIntroStage.Ready))
        assertEquals(FirstFrame.Fallback, EntryIdentPolicy.firstFrame(limit + EntryIdentPolicy.ReadyGraceMs, ArcIntroStage.Ready))
        // The art turned ready and drew during the grace: the intro starts after all.
        assertEquals(FirstFrame.Started, EntryIdentPolicy.firstFrame(limit + 100, ArcIntroStage.Drawn))
    }

    @Test fun drawnFrameStartsTheIntroEvenWhenItsMessageIsLate() {
        assertEquals(FirstFrame.Started, EntryIdentPolicy.firstFrame(EntryIdentPolicy.FirstFrameLimitMs, ArcIntroStage.Drawn))
        assertEquals(FirstFrame.Started, EntryIdentPolicy.firstFrame(EntryIdentPolicy.RecoveryTimeoutMs, ArcIntroStage.Drawn))
    }

    @Test fun failedPrepareOrDrawFallsBackAtOnce() {
        assertEquals(FirstFrame.Fallback, EntryIdentPolicy.firstFrame(0, ArcIntroStage.Failed))
    }

    @Test fun limitIsGenerousAndCountsFromTheIdentNotFromARestart() {
        assertTrue(EntryIdentPolicy.FirstFrameLimitMs >= EntryIdentPolicy.MinimumCycleMs)
        assertTrue(EntryIdentPolicy.FirstFrameLimitMs < EntryIdentPolicy.RecoveryTimeoutMs)
        // A theme change restarts the art at 3 s: the new surface is preparing again, the clock is not reset.
        assertEquals(FirstFrame.Wait, EntryIdentPolicy.firstFrame(3_000, ArcIntroStage.Preparing))
        assertEquals(FirstFrame.Fallback, EntryIdentPolicy.firstFrame(EntryIdentPolicy.FirstFrameLimitMs, ArcIntroStage.Preparing))
    }

    @Test fun animatedIntroHoldsItsFullCycle() {
        assertEquals(EntryIdentPolicy.MinimumCycleMs, EntryIdentPolicy.holdMs(300, fallback = false))
        assertEquals(EntryIdentPolicy.MinimumCycleMs, EntryIdentPolicy.holdMs(4_000, fallback = false))
    }

    @Test fun stillMarkEndsWhenTheIntroWouldHaveButNeverOnlyFlashes() {
        assertEquals(EntryIdentPolicy.MinimumCycleMs - 300, EntryIdentPolicy.holdMs(300, fallback = true))
        assertEquals(EntryIdentPolicy.FallbackHoldMs, EntryIdentPolicy.holdMs(EntryIdentPolicy.FirstFrameLimitMs, fallback = true))
        val lateTotal = EntryIdentPolicy.FirstFrameLimitMs + EntryIdentPolicy.holdMs(EntryIdentPolicy.FirstFrameLimitMs, fallback = true)
        assertTrue(lateTotal < EntryIdentPolicy.RecoveryTimeoutMs)
    }

    @Test fun startWindowMarkWaitsForAnIdentThatIsAboutToStart() {
        fun coming(pending: Boolean = false, v2: Boolean = true, enabled: Boolean = true, starts: Boolean = true, playback: Boolean = false) =
            EntryIdentPolicy.introComing(pending, v2, enabled, starts, playback)
        assertTrue(coming())
        assertTrue(coming(pending = true, starts = false))
        assertFalse(coming(enabled = false))
        assertFalse(coming(v2 = false))
        assertFalse(coming(starts = false))
        assertFalse(coming(pending = true, playback = true))
    }

    @Test fun introOffGivesTheMarkWayInOneCut() {
        val state = ProfileEntryState()
        state.start(enabled = false)
        assertFalse(EntryIdentPolicy.introComing(state.pending, true, false, state.shouldStartOnEntry(), false))
        val again = ProfileEntryState()
        assertTrue(EntryIdentPolicy.introComing(again.pending, true, true, again.shouldStartOnEntry(), false))
        again.start()
        again.markShown()
        again.finish()
        assertFalse(EntryIdentPolicy.introComing(again.pending, true, true, again.shouldStartOnEntry(), false))
    }

    @Test fun fallbackReplacesTheStartWindowMark() {
        assertTrue(EntryIdentPolicy.showsStartPicture(handoff = true, failed = false))
        assertFalse(EntryIdentPolicy.showsStartPicture(handoff = true, failed = true))
        assertFalse(EntryIdentPolicy.showsStartPicture(handoff = false, failed = false))
    }

    @Test fun leavingWaitsOutTheEntranceFadeIn() {
        assertEquals(350L, EntryIdentPolicy.fadeInLeftMs(0))
        assertEquals(150L, EntryIdentPolicy.fadeInLeftMs(200))
        assertEquals(0L, EntryIdentPolicy.fadeInLeftMs(350))
        assertEquals(0L, EntryIdentPolicy.fadeInLeftMs(5_000))
    }

    @Test fun startPictureEndsOnceAndOnlyWhenThereIsOne() {
        var ended = 0
        val picture = StartPicture(android.graphics.drawable.ColorDrawable(0)) { ended++ }
        assertTrue(picture.showing)
        picture.end()
        picture.end()
        assertFalse(picture.showing)
        assertEquals(1, ended)
        val none = StartPicture(null) { ended++ }
        assertFalse(none.showing)
        none.end()
        assertEquals(1, ended)
    }
}
