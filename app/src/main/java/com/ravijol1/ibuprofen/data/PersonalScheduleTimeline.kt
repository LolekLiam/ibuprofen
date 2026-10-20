package com.ravijol1.ibuprofen.data

import java.time.LocalTime

internal val MobileEvent.isAllDay: Boolean
    get() = from == "00:00" && to == "23:59"

internal sealed interface PersonalScheduleEntry {
    val startTime: LocalTime

    data class Lesson(val cell: PeriodCell.WithLessons) : PersonalScheduleEntry {
        override val startTime: LocalTime get() = cell.timeRange.start
    }
    data class Event(val event: MobileEvent) : PersonalScheduleEntry {
        // Missing/unrecognised event times should not crash the entire schedule.
        override val startTime: LocalTime get() = runCatching { LocalTime.parse(event.from) }.getOrDefault(LocalTime.MAX)
    }
}

/** All-day entries precede the day's timeline; timed events and lessons share start-time order. */
internal fun personalScheduleTimeline(day: TimetableDay, events: List<MobileEvent>): List<PersonalScheduleEntry> {
    val entries = day.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>().map { PersonalScheduleEntry.Lesson(it) } +
        events.filter { it.date == day.date.toString() }.map { PersonalScheduleEntry.Event(it) }
    return entries.sortedWith(compareBy<PersonalScheduleEntry>(
        { if (it is PersonalScheduleEntry.Event && it.event.isAllDay) 0 else 1 },
        { it.startTime },
        { if (it is PersonalScheduleEntry.Event) 0 else 1 }
    ))
}
