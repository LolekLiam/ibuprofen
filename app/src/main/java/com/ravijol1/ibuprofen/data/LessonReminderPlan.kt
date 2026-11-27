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
    val timeHm: String,
    val groupOptions: List<ReminderGroupOption>? = null,
    val entryGroupsKnown: Boolean = false
) {
    fun title(nowEpoch: Long): String {
        val minutes = ((startEpoch - nowEpoch + 59) / 60).coerceIn(1, 5)
        return "Čez $minutes min: ${subjects.joinToString(", ")}"
    }
    val message: String get() = if (rooms.isEmpty()) "" else "Učilnica: ${rooms.joinToString(", ")}"
}

/** One alarm per start, including parallel groups; cancelled lessons never produce alarms. */
internal fun lessonReminderPlan(week: TimetableWeek, now: Instant): List<LessonReminder> {
    data class Slot(val start: java.time.ZonedDateTime, val lessons: List<Lesson>, val allLessons: List<Lesson>)
    return week.days.flatMap { day ->
        day.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>().mapNotNull { cell ->
            val active = cell.lessons.filterNot { it.isCancelled }
            if (active.isEmpty() || cell.timeRange.start == cell.timeRange.endInclusive) return@mapNotNull null
            val start = day.date.atTime(cell.timeRange.start).atZone(SCHOOL_ZONE)
            if (!start.toInstant().isAfter(now)) null else Slot(start, active, cell.lessons)
        }
    }.groupBy { it.start.toEpochSecond() }.map { (epoch, slots) ->
        val start = slots.first().start
        val lessons = slots.flatMap { it.lessons }
        LessonReminder(epoch, (epoch - 300).coerceAtLeast(now.epochSecond),
            lessons.mapNotNull { it.subjectCode ?: it.subjectTitle }.filter { it.isNotBlank() }.distinct().ifEmpty { listOf("Ura") },
            lessons.mapNotNull { it.room }.filter { it.isNotBlank() }.distinct(),
            start.toLocalDate().toString(), start.toLocalTime().toString().take(5),
            slots.flatMap { slot -> slot.lessons.map { lesson ->
                ReminderGroupOption(lesson.groupSubjectKey(), lesson.groupVariantKey(), lesson.isSubstitution,
                    lesson.subjectCode ?: lesson.subjectTitle ?: "Ura", lesson.room,
                    timetableEntryChoiceKey(slot.allLessons, start.toLocalDate()), lesson.entryOptionKey())
            } }.distinct(), true)
    }.sortedBy { it.startEpoch }
}

internal fun shouldDeliverReminder(startEpoch: Long, nowEpoch: Long, lastDelivered: Long?): Boolean =
    nowEpoch in (startEpoch - 300) until startEpoch && lastDelivered != startEpoch

/** Retain the unfiltered options so group changes can rebuild saved alarms while offline. */
internal data class ReminderGroupOption(val subject: String?, val variant: String?, val substitution: Boolean, val title: String, val room: String?, val entry: String? = null, val option: String? = null)
internal fun visibleGroupReminder(reminder: LessonReminder, groups: PersonalGroups): LessonReminder? {
    val week = runCatching { groupWeekKey(java.time.LocalDate.parse(reminder.dateIso)) }.getOrNull()
    if (!reminder.entryGroupsKnown && week != null && groups.entryChoices.orEmpty().keys.any { it.endsWith("@$week") }) return null
    val options = reminder.groupOptions ?: return reminder.takeUnless {
        // Old alarm records have no group identity. Refresh them rather than send an incorrect alert.
        groups.choices.any { (key, choice) -> (choice.hidden && !key.contains('@')) ||
            (week != null && key.endsWith("@$week") && (choice.hidden || choice.variant != null)) }
    }
    val parallel = options.groupBy { it.subject }.mapValues { (_, entries) -> entries.mapNotNull { it.variant }.distinct().size > 1 }
    val visible = options.filter { option ->
        val selected = groups.entryChoices.orEmpty()[option.entry]?.takeIf { it.isNotEmpty() }
        if (groups.choices[option.subject]?.hidden == true) false
        else if (selected != null) option.option in selected
        else groupLessonVisible(option.subject, option.variant, option.substitution, groups, week, parallel[option.subject] == true)
    }
    if (visible.isEmpty()) return null
    return reminder.copy(subjects = visible.map { it.title }.distinct(), rooms = visible.mapNotNull { it.room }.filter(String::isNotBlank).distinct())
}
