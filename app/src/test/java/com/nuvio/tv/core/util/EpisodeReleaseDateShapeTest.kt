package com.nuvio.tv.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeReleaseDateShapeTest {
    private val zone = ZoneId.of("Australia/Sydney")

    private val samples = listOf(
        "2019", "2019-2023", "2019-", "Live", "TBA", "24/7", "Ongoing", "Season 2 2024-03-05",
        "2026-07-16", "2026-07-16T00:00:00.000Z", "2026-07-16T01:00:00+02:00",
        "2026-07-16T01:00:00+10:00[Australia/Sydney]", "2026-07-16T20:30", "2026-07-16T20:30:15",
        "+12026-07-16", "-0001-01-01", "2026-13-45", "2026-02-30", "2026-07-16 20:30",
        "2026-7-16", "released 2026-07-16", "20260716", " 2026-07-16 ", "2026-07-16x"
    )

    @Test
    fun `release instants are the same as trying every parser`() {
        samples.forEach { raw ->
            assertEquals(raw, everyParserInstant(raw), parseEpisodeReleaseInstant(raw, zone))
        }
    }

    @Test
    fun `release dates are the same as trying every parser`() {
        samples.forEach { raw ->
            assertEquals(raw, everyParserLocalDate(raw), parseEpisodeReleaseLocalDate(raw, zone))
        }
    }

    private fun explicit(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(value).toInstant() }.getOrNull()

    private fun embedded(value: String): LocalDate? =
        Regex("(?<!\\d)\\d{4}-\\d{2}-\\d{2}(?!\\d)").find(value)?.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun everyParserInstant(raw: String): Instant? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        return explicit(value)
            ?: runCatching { LocalDateTime.parse(value).atZone(zone).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
            ?: embedded(value)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
    }

    private fun everyParserLocalDate(raw: String): LocalDate? {
        val value = raw.trim().takeIf { it.isNotEmpty() } ?: return null
        return runCatching { LocalDate.parse(value) }.getOrNull()
            ?: explicit(value)?.atZone(zone)?.toLocalDate()
            ?: runCatching { LocalDateTime.parse(value).toLocalDate() }.getOrNull()
            ?: embedded(value)
    }
}
