package com.nuvio.tv.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AddonHealthMetaOutcomeTest {

    @Test
    fun metaFoundIsSuccess() {
        assertEquals(HealthOutcome.SUCCESS, AddonHealthModel.metaOutcome(200, hasMeta = true))
    }

    @Test
    fun notFoundIsEmpty() {
        assertEquals(HealthOutcome.EMPTY, AddonHealthModel.metaOutcome(404, hasMeta = false))
    }

    @Test
    fun okWithoutMetaIsEmpty() {
        assertEquals(HealthOutcome.EMPTY, AddonHealthModel.metaOutcome(200, hasMeta = false))
    }

    @Test
    fun serverErrorsStayFailures() {
        listOf(500, 502, 503, 504, 429, 403, 401, 400).forEach { code ->
            assertEquals("HTTP $code", HealthOutcome.FAILURE, AddonHealthModel.metaOutcome(code, hasMeta = false))
        }
    }

    @Test
    fun noAnswerIsFailure() {
        assertEquals(HealthOutcome.FAILURE, AddonHealthModel.metaOutcome(null, hasMeta = false))
    }

    @Test
    fun repeatedNotFoundNeverTurnsAnAddonDown() {
        var record = HealthRecord()
        repeat(5) { i ->
            record = AddonHealthModel.applySample(
                record,
                HealthSample(1_000L + i, AddonHealthModel.metaOutcome(404, hasMeta = false), 300L)
            )
        }
        assertEquals(0, record.consecutiveFailures)
        assertEquals(0L, record.breakerOpenUntilMs)
        assertNotEquals(AddonHealthLevel.DOWN, AddonHealthModel.deriveLevel(record, 2_000L))
    }

    @Test
    fun twoTimeoutsStillTurnAnAddonDown() {
        var record = HealthRecord()
        repeat(2) { i ->
            record = AddonHealthModel.applySample(
                record,
                HealthSample(1_000L + i, AddonHealthModel.metaOutcome(null, hasMeta = false), 20_000L)
            )
        }
        assertEquals(AddonHealthLevel.DOWN, AddonHealthModel.deriveLevel(record, 2_000L))
    }
}
