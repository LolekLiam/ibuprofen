package com.ravijol1.ibuprofen.data

import java.time.Instant
import java.time.ZoneId

internal val SCHOOL_ZONE: ZoneId = ZoneId.of("Europe/Ljubljana")

internal data class LessonReminder(
    val startEpoch: Long,
    val fireAtEpoch: Long,
    val subjects: List<String>,
    val rooms: List<String>,
    val dateIso: String,
    val timeHm: String
) {
    fun title(nowEpoch: Long): String {
        val minutes = ((startEpoch - nowEpoch + 59) / 60).coerceIn(1, 5)
        return "Čez $minutes min: ${subjects.joinToString(", ")}"
    }
    val message: String get() = if (rooms.isEmpty()) "" else "Učilnica: ${rooms.joinToString(", ")}"
}

/** One alarm per start, including parallel groups; cancelled lessons never produce alarms. */
internal fun lessonReminderPlan(week: TimetableWeek, now: Instant): List<LessonReminder> {
    data class Slot(val start: java.time.ZonedDateTime, val lessons: List<Lesson>)
    return week.days.flatMap { day ->
        day.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>().mapNotNull { cell ->
            val active = cell.lessons.filterNot { it.isCancelled }
            if (active.isEmpty() || cell.timeRange.start == cell.timeRange.endInclusive) return@mapNotNull null
            val start = day.date.atTime(cell.timeRange.start).atZone(SCHOOL_ZONE)
            if (!start.toInstant().isAfter(now)) null else Slot(start, active)
        }
    }.groupBy { it.start.toEpochSecond() }.map { (epoch, slots) ->
        val start = slots.first().start
        val lessons = slots.flatMap { it.lessons }
        LessonReminder(epoch, (epoch - 300).coerceAtLeast(now.epochSecond),
            lessons.mapNotNull { it.subjectCode ?: it.subjectTitle }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("Ura") },
            lessons.mapNotNull { it.room }.filter { it.isNotBlank() }.distinct(),
            start.toLocalDate().toString(), start.toLocalTime().toString().take(5))
    }.sortedBy { it.startEpoch }
}

internal fun shouldDeliverReminder(startEpoch: Long, nowEpoch: Long, lastDelivered: Long?): Boolean =
    nowEpoch in (startEpoch - 300) until startEpoch && lastDelivered != startEpoch
