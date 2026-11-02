package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.SeekingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SeekStripLayoutTest {

    private val base = 30_000L

    private fun screenStrides(anchorMs: Double, i: Long, glideMs: Double, spacingMs: Double) =
        (anchorMs + i * spacingMs - glideMs) / spacingMs

    @Test
    fun multipleForSpeed_keepsTilesReadable() {
        assertEquals(1L, SeekStripLayout.multipleForSpeed(100f, base))      // 100 s/s over 30 s tiles
        assertEquals(3L, SeekStripLayout.multipleForSpeed(400f, base))
        assertEquals(6L, SeekStripLayout.multipleForSpeed(800f, base))
        assertEquals(3L, SeekStripLayout.multipleForSpeed(100f, 10_000L))   // 10 s tiles
        assertEquals(30L, SeekStripLayout.multipleForSpeed(800f, 10_000L))
    }

    @Test
    fun multipleForSpeed_neverPassesMoreThanFiveTilesASecond() {
        var speed = 1f
        while (speed <= 800f) {
            val m = SeekStripLayout.multipleForSpeed(speed, 10_000L)
            assertTrue("speed $speed", speed * 1000.0 / (10_000.0 * m) <= 5.0)
            speed += 1f
        }
    }

    @Test
    fun multipleForSpeed_standingStillAndBadBase() {
        assertEquals(1L, SeekStripLayout.multipleForSpeed(0f, base))
        assertEquals(1L, SeekStripLayout.multipleForSpeed(500f, 0L))
    }

    @Test
    fun respacedAnchor_keepsEveryTileWhereItWas() {
        val glide = 612_345.0
        for ((old, new) in listOf(30_000.0 to 90_000.0, 90_000.0 to 180_000.0, 180_000.0 to 30_000.0)) {
            val anchor = 0.0
            val respaced = SeekStripLayout.respacedAnchor(anchor, glide, old, new)
            val oldPhase = screenStrides(anchor, 0, glide, old).let { it - Math.floor(it) }
            val newPhase = screenStrides(respaced, 0, glide, new).let { it - Math.floor(it) }
            assertEquals(oldPhase, newPhase, 1e-9)
        }
    }

    @Test
    fun respacedAnchor_noTileMovesTowardTheCentre() {
        val glide = 612_345.0
        val anchor = 0.0
        val old = 30_000.0
        val new = 90_000.0
        val respaced = SeekStripLayout.respacedAnchor(anchor, glide, old, new)
        val before = SeekStripLayout.tileIndices(anchor, glide, 5 * old, old)
            .map { screenStrides(anchor, it, glide, old) }.sorted()
        val after = SeekStripLayout.tileIndices(respaced, glide, 5 * new, new)
            .map { screenStrides(respaced, it, glide, new) }.sorted()
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) -> assertEquals(b, a, 1e-9) }
    }

    @Test
    fun respacedAnchor_staysNearTheGlide() {
        val glide = 6_000_000.0
        val respaced = SeekStripLayout.respacedAnchor(0.0, glide, 30_000.0, 180_000.0)
        assertTrue(abs(respaced - glide) <= 90_000.0)
    }

    @Test
    fun respacedAnchor_sameSpacingKeepsTheTiles() {
        val respaced = SeekStripLayout.respacedAnchor(15_000.0, 600_000.0, 30_000.0, 30_000.0)
        assertEquals(0.0, Math.IEEEremainder(respaced - 15_000.0, 30_000.0), 1e-9)
    }

    @Test
    fun tileIndices_coverTheReach() {
        val range = SeekStripLayout.tileIndices(0.0, 600_000.0, 100_000.0, 30_000.0)
        assertEquals(17L, range.first)
        assertEquals(23L, range.last)
        assertTrue(SeekStripLayout.tileIndices(0.0, 600_000.0, 100_000.0, 0.0).isEmpty())
    }

    @Test
    fun pictureMs_roundsToTheStoredGrid() {
        assertEquals(600_000L, SeekStripLayout.pictureMs(600_000.0, base))
        assertEquals(600_000L, SeekStripLayout.pictureMs(614_999.0, base))
        assertEquals(630_000L, SeekStripLayout.pictureMs(615_001.0, base))
        assertEquals(0L, SeekStripLayout.pictureMs(-14_000.0, base))
    }

    private val film = 7_200_000L
    private val modes = SeekingMode.entries

    /**
     * A held seek on the real speed curve of [mode], frame by frame, as the strip sees it: (time, glide position,
     * spacing multiple before, after). Fast follows its steps on a remote repeating every [everyMs]; its strip
     * spacing follows the fastest speed of the hold, as in the player.
     */
    private fun walkHold(
        mode: SeekingMode,
        baseSpacingMs: Long,
        tvRemote: Boolean,
        everyMs: Long = if (tvRemote) 150L else 50L,
        check: (Long, Double, Long, Long, Double) -> Unit
    ) {
        var glide = 600_000.0
        var multiple = 1L
        var t = 400L
        val chase = if (mode == SeekingMode.FAST) PlayerScrubRates.StepChase(glide, film.toDouble()) else null
        var nextRepeat = 400L
        var fastest = 0.0
        while (t <= 20_000L) {
            val speed = if (chase != null) {
                while (nextRepeat <= t) {
                    chase.repeat(nextRepeat, true)
                    nextRepeat += everyMs
                }
                val v = chase.advance(16)
                glide = chase.cursorMs
                fastest = maxOf(fastest, v)
                fastest
            } else {
                PlayerScrubRates.glideSpeed(t, mode, film, tvRemote).also { glide += it * 16 }
            }
            val next = SeekStripLayout.multipleForSpeed(speed.toFloat(), baseSpacingMs)
            check(t, glide, multiple, next, speed)
            multiple = next
            t += 16
        }
    }

    @Test
    fun heldSeek_spacingOnlyGrowsAndStaysReadableOnEverySpeedCurve() {
        for (mode in modes) for (spacing in listOf(10_000L, 30_000L)) for (tv in listOf(false, true)) {
            walkHold(mode, spacing, tv) { t, _, before, after, speed ->
                assertTrue("$mode $spacing $tv at $t: $before -> $after", after >= before)
                assertTrue("$mode $spacing $tv at $t", speed * 1000.0 / (spacing * after) <= 5.0)
            }
        }
    }

    @Test
    fun heldSeek_tilesKeepTheirPlaceAtEverySpacingStep() {
        for (mode in modes) {
            var steps = 0
            walkHold(mode, 10_000L, false) { _, glide, before, after, _ ->
                if (after == before) return@walkHold
                steps++
                val old = 10_000.0 * before
                val new = 10_000.0 * after
                val anchor = 3_000.0
                val respaced = SeekStripLayout.respacedAnchor(anchor, glide, old, new)
                val oldPhase = screenStrides(anchor, 0, glide, old).let { it - Math.floor(it) }
                val newPhase = screenStrides(respaced, 0, glide, new).let { it - Math.floor(it) }
                assertEquals(oldPhase, newPhase, 1e-6)
            }
            assertTrue("$mode steps $steps", steps >= 2)
        }
    }

    @Test
    fun heldSeek_cursorMovesWithoutJumpsAtTheStartAndAtEveryDoubling() {
        for (mode in listOf(SeekingMode.GENTLE, SeekingMode.BALANCED)) {
            var lastStep = PlayerScrubRates.glideSpeed(384L, mode, film) * 16
            var t = 400L
            while (t <= 20_000L) {
                val step = PlayerScrubRates.glideSpeed(t, mode, film) * 16
                assertTrue("$mode at $t: $lastStep -> $step", step >= lastStep && step <= lastStep * 1.03)
                lastStep = step
                t += 16
            }
        }
    }

    /** Tiles on screen move by at most this share of a tile stride per 16 ms frame, at any speed of any choice. */
    @Test
    fun heldSeek_tilesNeverSkipAcrossTheScreen() {
        for (mode in modes) for (tv in listOf(false, true)) {
            var last = Double.NaN
            walkHold(mode, 10_000L, tv) { t, glide, _, after, _ ->
                if (!last.isNaN()) {
                    val strides = (glide - last) / (10_000.0 * after)
                    assertTrue("$mode $tv at $t: $strides strides in a frame", strides in 0.0..0.12)
                }
                last = glide
            }
        }
    }

    @Test
    fun tileAlpha_hiddenBehindTheCentreAndFadesInAsItComesOut() {
        val centre = 226f
        val tile = 169f
        assertEquals(0f, SeekStripLayout.tileAlpha(0f, centre, tile), 1e-6f)
        assertEquals(0f, SeekStripLayout.tileAlpha((centre - tile) / 2f, centre, tile), 1e-6f)
        assertEquals(0.5f, SeekStripLayout.tileAlpha(centre / 2f, centre, tile), 1e-6f)
        assertEquals(1f, SeekStripLayout.tileAlpha((centre + tile) / 2f, centre, tile), 1e-6f)
        assertEquals(1f, SeekStripLayout.tileAlpha(1_000f, centre, tile), 1e-6f)
        var last = 0f
        var dx = 0f
        while (dx < 400f) {
            val a = SeekStripLayout.tileAlpha(dx, centre, tile)
            assertTrue("at $dx", a >= last && a - last <= 1f / tile + 1e-6f)
            last = a
            dx += 1f
        }
    }
}
