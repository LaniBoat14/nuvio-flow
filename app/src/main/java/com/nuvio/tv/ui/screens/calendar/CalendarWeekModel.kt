package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class CalendarFilter { ALL, SHOWS, FILMS;

    fun keeps(row: CalendarRow): Boolean = when (this) {
        ALL -> true
        SHOWS -> !row.isFilm
        FILMS -> row.isFilm
    }
}

/** One card on a day: a release, or several episodes of a show that come out together. */
@Immutable
data class CalendarCard(val rows: List<CalendarRow>) {
    val row: CalendarRow get() = rows.first()
    val key: String get() = row.key
    val count: Int get() = rows.size

    /** The last episode when the card holds a run of one season, for "S2 E1-E3". */
    val lastEpisode: Int?
        get() = if (rows.size > 1 && rows.all { it.season == row.season && it.episode != null }) rows.last().episode else null
}

@Immutable
data class CalendarWeekDay(val date: LocalDate, val cards: List<CalendarCard>) {
    val isEmpty: Boolean get() = cards.isEmpty()

    /** Dots under the day in the strip: one per card, at most four. */
    val dots: Int get() = cards.size.coerceAtMost(4)
}

fun calendarWeekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay))

/** A day's rows as cards; episodes of one show on one day share a card, in the order they were first met. */
fun calendarCards(rows: List<CalendarRow>): List<CalendarCard> {
    val groups = LinkedHashMap<String, MutableList<CalendarRow>>()
    rows.forEach { row ->
        val group = if (row.isFilm || row.kind != CalendarRowKind.EPISODE) row.key else "${row.titleKey}|${row.time}"
        groups.getOrPut(group) { mutableListOf() } += row
    }
    return groups.values.map { group ->
        CalendarCard(group.sortedWith(compareBy<CalendarRow> { it.season ?: 0 }.thenBy { it.episode ?: 0 }))
    }
}

/** The seven days from [weekStart], each with its cards after [filter]. */
fun buildCalendarWeek(
    days: Map<LocalDate, List<CalendarRow>>,
    weekStart: LocalDate,
    filter: CalendarFilter
): List<CalendarWeekDay> = List(7) { offset ->
    val date = weekStart.plusDays(offset.toLong())
    CalendarWeekDay(date, calendarCards(days[date].orEmpty().filter(filter::keeps)))
}

/** The card the week opens on: the first one of the week, at the top. */
fun calendarWeekOpeningKey(week: List<CalendarWeekDay>): String? = week.firstOrNull { !it.isEmpty }?.cards?.first()?.key

/** Every dated release of each title in time order, for "Next from". */
fun calendarRowsByTitle(days: Map<LocalDate, List<CalendarRow>>): Map<String, List<CalendarRow>> =
    days.entries.sortedBy { it.key }
        .flatMap { (_, rows) -> rows }
        .groupBy { it.titleKey }

/** The next [max] releases of [row]'s title after it. */
fun calendarNextFrom(byTitle: Map<String, List<CalendarRow>>, row: CalendarRow, max: Int = 2): List<CalendarRow> {
    val rows = byTitle[row.titleKey].orEmpty()
    val date = row.date ?: return emptyList()
    val index = rows.indexOfFirst { it.key == row.key }
    val later = if (index >= 0) rows.drop(index + 1) else rows.filter { it.date != null && it.date > date }
    return later.filter { it.date != null && it.date >= date && it.key != row.key }.take(max)
}

enum class CalendarMosaicLayout { NONE, FULL, SPLIT, GRID }

/** How a month day shows its titles: one picture, two halves, or a 2 x 2 grid whose last tile may be "+N". */
@Immutable
data class CalendarMosaic(
    val layout: CalendarMosaicLayout,
    val tiles: List<CalendarRow>,
    val more: Int = 0
)

fun calendarMosaic(rows: List<CalendarRow>?, filter: CalendarFilter = CalendarFilter.ALL): CalendarMosaic {
    val titles = rows.orEmpty().filter(filter::keeps).distinctBy { it.titleKey }
    return when (titles.size) {
        0 -> CalendarMosaic(CalendarMosaicLayout.NONE, emptyList())
        1 -> CalendarMosaic(CalendarMosaicLayout.FULL, titles)
        2 -> CalendarMosaic(CalendarMosaicLayout.SPLIT, titles)
        3, 4 -> CalendarMosaic(CalendarMosaicLayout.GRID, titles)
        else -> CalendarMosaic(CalendarMosaicLayout.GRID, titles.take(3), more = titles.size - 3)
    }
}
