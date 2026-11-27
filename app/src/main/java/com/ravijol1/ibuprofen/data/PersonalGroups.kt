package com.ravijol1.ibuprofen.data

import java.util.Locale
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

internal data class ScheduleGroupVariant(val key: String, val label: String, val rooms: List<String> = emptyList())
internal data class ScheduleGroupSubject(val key: String, val name: String, val variants: List<ScheduleGroupVariant>, val split: Boolean = false)
internal data class ScheduleGroupChoice(val variant: String? = null, val knownVariants: Set<String> = emptySet(), val hidden: Boolean = false)
internal data class PersonalGroups(val subjects: List<ScheduleGroupSubject> = emptyList(), val choices: Map<String, ScheduleGroupChoice> = emptyMap(),
    val entryChoices: Map<String, Set<String>> = emptyMap())

private fun groupText(value: String?) = schoolText(value)?.lowercase(Locale.ROOT)?.replace(Regex("\\s+"), " ")
internal fun Lesson.groupSubjectKey(): String? = subjectId?.let { "id:$it" }
    ?: groupText(subjectCode ?: subjectTitle)?.let { "name:$it" }
internal fun Lesson.groupVariantKey(): String? = groupText(groupLabel)?.let { "group:$it" }
    ?: groupText(teacherFullName ?: teacher)?.let { "teacher:$it" }
    ?: groupText(room)?.let { "room:$it" }

/** Teacher identity survives room changes. A changed/substitute teacher is never guessed to be another group. */
internal fun groupWeekKey(date: LocalDate): String = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
internal fun groupWeekChoiceKey(subject: String, week: String): String = "$subject@$week"
internal fun groupLessonVisible(subject: String?, variant: String?, substitution: Boolean, preferences: PersonalGroups,
    week: String?, parallel: Boolean): Boolean {
    if (preferences.choices[subject]?.hidden == true) return false
    // The server may already have resolved old weeks or shared sessions to a single group.
    if (!parallel || subject == null || week == null) return true
    val choice = preferences.choices[groupWeekChoiceKey(subject, week)] ?: return true
    if (choice.hidden) return false
    if (choice.variant == null || variant == null || substitution) return true
    return variant == choice.variant || variant !in choice.knownVariants
}
internal fun filterPersonalGroups(schedule: PersonalSchedule, preferences: PersonalGroups): PersonalSchedule = schedule.copy(
    week = schedule.week.copy(days = schedule.week.days.map { day -> day.copy(lessonsByPeriod = day.lessonsByPeriod.mapNotNull { cell ->
        if (cell !is PeriodCell.WithLessons) cell else {
            val entryKey = timetableEntryChoiceKey(cell.lessons, day.date)
            val selected = preferences.entryChoices.orEmpty()[entryKey]?.takeIf { it.isNotEmpty() }
            val parallel = cell.lessons.groupBy { it.groupSubjectKey() }.mapValues { (_, lessons) -> lessons.mapNotNull { it.groupVariantKey() }.distinct().size > 1 }
            cell.lessons.filter {
                if (preferences.choices[it.groupSubjectKey()]?.hidden == true) false
                else if (selected != null) it.entryOptionKey() in selected
                else groupLessonVisible(it.groupSubjectKey(), it.groupVariantKey(), it.isSubstitution, preferences,
                    groupWeekKey(day.date), parallel[it.groupSubjectKey()] == true)
            }.takeIf { it.isNotEmpty() }?.let { cell.copy(lessons = it) }
        }
    }) })
)
internal fun discoverPersonalGroups(schedule: PersonalSchedule, previous: PersonalGroups): PersonalGroups {
    val subjects = previous.subjects.associateBy { it.key }.toMutableMap()
    schedule.week.days.flatMap { it.lessonsByPeriod }.filterIsInstance<PeriodCell.WithLessons>().forEach { cell ->
        cell.lessons.filterNot { it.isSubstitution }.groupBy { it.groupSubjectKey() }.forEach subjectLoop@ { (key, lessons) ->
            if (key == null) return@subjectLoop
            val old = subjects[key]
            val variants = old?.variants.orEmpty().associateBy { it.key }.toMutableMap()
            lessons.forEach lessonLoop@ { lesson ->
                val variant = lesson.groupVariantKey() ?: return@lessonLoop
                val existing = variants[variant]
                val label = lesson.groupLabel ?: lesson.teacherFullName ?: lesson.teacher ?: lesson.room ?: "Group"
                variants[variant] = ScheduleGroupVariant(variant, label,
                    (existing?.rooms.orEmpty() + listOfNotNull(lesson.room?.takeIf(String::isNotBlank))).distinct().sorted())
            }
            subjects[key] = ScheduleGroupSubject(key, old?.name ?: lessons.first().let { it.subjectTitle ?: it.subjectCode ?: "Subject" },
                variants.values.sortedBy { it.label.lowercase(Locale.ROOT) },
                old?.split == true || lessons.mapNotNull { it.groupVariantKey() }.distinct().size > 1)
        }
    }
    return previous.copy(subjects = subjects.values.sortedBy { it.name.lowercase(Locale.ROOT) })
}
internal fun groupPreferencesKey(uuid: String, schoolYear: String?): String {
    val year = Regex("^\\d{4}").find(schoolYear.orEmpty())?.value
        ?: Regex("_(\\d+)_(\\d+)_(\\d+)_(\\d+)_(\\d+)$").find(uuid)?.groupValues?.get(2).orEmpty()
    return "personal_groups:$uuid:$year"
}
