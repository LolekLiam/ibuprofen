package com.ravijol1.ibuprofen.data

import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal data class TimetableGroupOption(val key: String, val label: String)
internal data class TimetableGroupEntry(val key: String, val title: String, val occurrences: List<String>, val options: List<TimetableGroupOption>)
private fun entryDigest(values: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun Lesson.entryOptionKey(): String = entryDigest(listOf(groupSubjectKey().orEmpty(), groupVariantKey().orEmpty(),
    // Explicit group labels are better identities than rooms; otherwise preserve distinct parallel rooms.
    if (groupLabel.isNullOrBlank()) room.orEmpty().trim().lowercase(java.util.Locale.ROOT) else ""))
internal fun timetableEntryChoiceKey(lessons: List<Lesson>, date: LocalDate): String? {
    val keys = lessons.map { it.entryOptionKey() }.distinct().sorted()
    return if (keys.size < 2) null else "entry:${entryDigest(keys)}@${groupWeekKey(date)}"
}
internal fun discoverTimetableEntries(schedule: PersonalSchedule): List<TimetableGroupEntry> {
    val entries = linkedMapOf<String, TimetableGroupEntry>()
    schedule.week.days.forEach { day -> day.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>().forEach entryLoop@ { cell ->
        val key = timetableEntryChoiceKey(cell.lessons, day.date) ?: return@entryLoop
        val options = cell.lessons.distinctBy { it.entryOptionKey() }.map { lesson ->
            TimetableGroupOption(lesson.entryOptionKey(), listOfNotNull(lesson.subjectTitle ?: lesson.subjectCode ?: "Lesson",
                lesson.groupLabel, lesson.teacherFullName ?: lesson.teacher, lesson.room?.takeIf(String::isNotBlank)?.let { "Room $it" })
                .filter(String::isNotBlank).distinct().joinToString(" · "))
        }
        val label = "${day.date.format(DateTimeFormatter.ofPattern("EEE d MMM"))} · ${cell.timeRange.start}–${cell.timeRange.endInclusive}"
        val previous = entries[key]
        entries[key] = TimetableGroupEntry(key, cell.lessons.map { it.subjectCode ?: it.subjectTitle ?: "Lesson" }.distinct().joinToString(" / "),
            (previous?.occurrences.orEmpty() + label).distinct(), options)
    } }
    return entries.values.toList()
}
internal fun mobileGroupLabel(event: MobileEvent): String? {
    schoolText(event.groupLabel)?.let { return it }
    event.group?.let { group ->
        val label = when {
            group.isJsonPrimitive && !group.asJsonPrimitive.isBoolean -> group.asString
            group.isJsonObject -> listOf("name", "label", "title").firstNotNullOfOrNull { key ->
                group.asJsonObject.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            }
            else -> null
        }
        schoolText(label)?.let { return it }
    }
    val number = "(?:\\d+[\\p{L}]?|[\\p{L}]\\d*)"
    val label = Regex("(?iu)\\b(?:(?:skupina|skup\\.?|group)\\s*[:#-]?\\s*$number|$number\\.?\\s+(?:skupina|group))\\b")
    return listOfNotNull(event.title, event.shortTitle, event.subject?.title, event.subject?.shortTitle)
        .firstNotNullOfOrNull { label.find(schoolText(it).orEmpty())?.value }
}
