package com.ravijol1.ibuprofen.data

import java.time.LocalTime

/** Uses printable-page teacher labels when full names are not available. */
object TimetableAggregation {
    fun teacherName(lesson: Lesson): String? = lesson.teacherFullName?.trim()?.takeIf(String::isNotEmpty)
        ?: lesson.teacher?.trim()?.takeIf(String::isNotEmpty)

    private fun lessons(weeks: List<TimetableWeek>) = weeks.asSequence()
        .flatMap { it.days.asSequence() }.flatMap { it.lessonsByPeriod.asSequence() }
        .filterIsInstance<PeriodCell.WithLessons>().flatMap { it.lessons.asSequence() }

    fun teachers(weeks: List<TimetableWeek>): List<String> = lessons(weeks)
        .mapNotNull(::teacherName).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).toList()

    fun rooms(weeks: List<TimetableWeek>): List<String> = lessons(weeks)
        .mapNotNull { it.room?.trim()?.takeIf(String::isNotEmpty) }
        .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).toList()

    fun forTeacher(weeks: List<TimetableWeek>, teacher: String): TimetableWeek? = filter(weeks) {
        teacherName(it).equals(teacher.trim(), ignoreCase = true)
    }

    fun forRoom(weeks: List<TimetableWeek>, room: String): TimetableWeek? = filter(weeks) {
        it.room?.trim().equals(room.trim(), ignoreCase = true)
    }

    private data class Slot(val start: LocalTime, val end: LocalTime, val fallbackPeriod: Int?)

    private fun filter(weeks: List<TimetableWeek>, matches: (Lesson) -> Boolean): TimetableWeek? {
        if (weeks.isEmpty()) return null
        val days = weeks.flatMap { it.days }.groupBy { it.date }.toSortedMap().map { (date, sourceDays) ->
            val cells = sourceDays.flatMap { it.lessonsByPeriod }.filterIsInstance<PeriodCell.WithLessons>()
                .mapNotNull { cell -> cell.lessons.filter(matches).takeIf { it.isNotEmpty() }
                    ?.let { cell.copy(lessons = it) } }
                .groupBy { cell ->
                    val unknownTime = cell.timeRange.start == cell.timeRange.endInclusive
                    Slot(cell.timeRange.start, cell.timeRange.endInclusive, cell.periodNumber.takeIf { unknownTime })
                }
                .entries.sortedWith(compareBy({ it.key.fallbackPeriod != null }, { it.key.start },
                    { it.key.end }, { it.key.fallbackPeriod ?: 0 }))
                .map { (_, group) ->
                    val first = group.first()
                    first.copy(periodNumber = group.minOf { it.periodNumber },
                        lessons = group.flatMap { it.lessons }.distinct())
                }
            TimetableDay(date, cells)
        }
        return TimetableWeek(-1, weeks.minOf { it.weekStart }, weeks.maxOf { it.weekEnd }, days)
    }
}

data class SchoolTimetables(val weeks: List<TimetableWeek>, val failedClasses: List<ClassInfo>)
