package com.ravijol1.ibuprofen.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

internal data class WeeklyActivity(
    val key: String, val date: LocalDate, val entry: PersonalScheduleEntry,
    val startMinute: Int?, val endMinute: Int?, val allDay: Boolean = false
)
internal data class WeeklyBlock(val startMinute: Int, val endMinute: Int, val activities: List<WeeklyActivity>)
internal data class WeeklyColumn(val date: LocalDate, val untimed: List<WeeklyActivity>, val blocks: List<WeeklyBlock>)
internal data class WeeklyScheduleLayout(val columns: List<WeeklyColumn>, val startMinute: Int, val endMinute: Int) {
    val hasTimedActivities: Boolean get() = columns.any { it.blocks.isNotEmpty() }
}

/** Shared time axis; minimum visual durations are grouped so small touch targets never overlap. */
internal fun weeklyScheduleLayout(schedule: PersonalSchedule, minimumMinutes: Int = 40): WeeklyScheduleLayout {
    require(minimumMinutes in 1..1440)
    val monday = schedule.week.weekStart.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun minute(value: String) = runCatching { LocalTime.parse(value).toSecondOfDay() / 60 }.getOrNull()
    val dates = (0L..4L).map { monday.plusDays(it) }
    val entries = mutableListOf<WeeklyActivity>()
    schedule.week.days.filter { it.date in dates }.forEach { day ->
        day.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>().filter { it.lessons.isNotEmpty() }.forEachIndexed { index, cell ->
            val start = cell.timeRange.start.toSecondOfDay() / 60
            val end = cell.timeRange.endInclusive.toSecondOfDay() / 60
            entries += WeeklyActivity("lesson:${day.date}:$index:${cell.periodNumber}", day.date, PersonalScheduleEntry.Lesson(cell),
                start, if (end > start) end else (start + 45).coerceAtMost(1440))
        }
    }
    schedule.events.distinctBy { it.date to it.slug }.forEach { event ->
        val date = runCatching { LocalDate.parse(event.date) }.getOrNull() ?: return@forEach
        if (date !in dates) return@forEach
        val start = if (event.isAllDay) null else minute(event.from)
        val end = start?.let { from -> minute(event.to)?.let { if (it == 0 && from > 0) 1440 else it }?.takeIf { it > from } ?: (from + 30).coerceAtMost(1440) }
        entries += WeeklyActivity("event:$date:${event.slug}", date, PersonalScheduleEntry.Event(event), start, end, event.isAllDay)
    }
    val columns = dates.map { date ->
        val items = entries.filter { it.date == date }
        val blocks = mutableListOf<WeeklyBlock>()
        items.filter { it.startMinute != null }.sortedWith(compareBy({ it.startMinute }, { it.key })).forEach { entry ->
            val start = requireNotNull(entry.startMinute)
            val end = maxOf(requireNotNull(entry.endMinute), start + minimumMinutes).coerceAtMost(1440)
            val previous = blocks.lastOrNull()
            if (previous != null && start < previous.endMinute) {
                blocks[blocks.lastIndex] = previous.copy(endMinute = maxOf(previous.endMinute, end), activities = previous.activities + entry)
            } else blocks += WeeklyBlock(start, end, listOf(entry))
        }
        WeeklyColumn(date, items.filter { it.startMinute == null }, blocks)
    }
    val blocks = columns.flatMap { it.blocks }
    val first = blocks.minOfOrNull { it.startMinute } ?: 8 * 60
    val last = blocks.maxOfOrNull { it.endMinute } ?: 9 * 60
    return WeeklyScheduleLayout(columns, first / 60 * 60, ((last + 59) / 60 * 60).coerceAtMost(1440))
}

internal data class WeeklyNowIndicator(val date: LocalDate, val minute: Int)

/** Only mark the actual school-local day, inside the displayed time range. */
internal fun weeklyNowIndicator(layout: WeeklyScheduleLayout, now: java.time.ZonedDateTime): WeeklyNowIndicator? {
    val local = now.withZoneSameInstant(SCHOOL_ZONE)
    val minute = local.hour * 60 + local.minute
    return if (layout.hasTimedActivities && layout.columns.any { it.date == local.toLocalDate() } &&
        minute in layout.startMinute..layout.endMinute) WeeklyNowIndicator(local.toLocalDate(), minute) else null
}
