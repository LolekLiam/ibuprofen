package com.ravijol1.ibuprofen.data

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.time.LocalDate
import java.time.LocalTime

// DTOs follow the supplied mobile capture. Optional fields vary by event type.
data class SchoolWeeksResponse(val items: List<MobileSchoolWeek> = emptyList())
data class MobileSchoolWeek(
    @SerializedName("week_index") val index: Int,
    val current: Boolean,
    val from: String,
    val to: String,
    val days: List<MobileSchoolDay> = emptyList()
)
data class MobileSchoolDay(val id: Int, val date: String, val name: String?,
    @SerializedName("short_name") val shortName: String?, val current: Boolean)

data class MobileTimetableResponse(val schedule: List<MobilePeriod> = emptyList(), val events: List<MobileEvent> = emptyList())
data class MobilePeriod(val name: String?, @SerializedName("short_name") val shortName: String?, val from: String, val to: String)
data class MobileSubject(@SerializedName("subject_id") val id: Long?, val title: String?,
    @SerializedName("title_short") val shortTitle: String?)
data class MobileEvent(
    val slug: String,
    val type: String,
    val date: String,
    val from: String,
    val to: String,
    val title: String?,
    @SerializedName("title_short") val shortTitle: String?,
    val teachers: List<String> = emptyList(),
    val subject: MobileSubject?,
    val classroom: String?,
    val notes: List<JsonElement> = emptyList(),
    @SerializedName("schedule_index") val scheduleIndices: List<Int> = emptyList(),
    @SerializedName("is_substitution") val isSubstitution: Boolean = false,
    @SerializedName("is_cancelled") val isCancelled: Boolean = false,
    @SerializedName("type_label") val typeLabel: String?,
    val description: String?
)
data class PersonalSchedule(val week: TimetableWeek, val events: List<MobileEvent>)

object MobileTimetableMapper {
    fun map(response: MobileTimetableResponse, child: ChildProfile, start: LocalDate, end: LocalDate): PersonalSchedule {
        require(!end.isBefore(start))
        val inRange = response.events.filter { LocalDate.parse(it.date) in start..end }.distinctBy { it.slug }
        val lessons = inRange.filter { it.type == "school_hour" || it.isSubstitution }
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.map { date ->
            val cells = lessons.filter { it.date == date.toString() }.groupBy { it.from to it.to }
                .entries.sortedBy { LocalTime.parse(it.key.first) }.map { (time, events) ->
                    val first = events.first()
                    val index = first.scheduleIndices.firstOrNull()
                    val period = index?.let { response.schedule.getOrNull(it) }
                    val number = period?.shortName?.toIntOrNull()
                        ?: period?.name?.substringBefore('.')?.toIntOrNull() ?: index ?: 0
                    PeriodCell.WithLessons(number, LocalTime.parse(time.first)..LocalTime.parse(time.second), events.map { event ->
                        Lesson(event.subject?.shortTitle ?: event.shortTitle, event.subject?.title ?: event.title,
                            event.teachers.joinToString(", ").ifBlank { null }, event.classroom?.ifBlank { null },
                            null, event.isCancelled, event.teachers.joinToString(", ").ifBlank { null },
                            child.className, child.classId?.toString(), event.isSubstitution,
                            event.notes.mapNotNull { note -> when {
                                note.isJsonPrimitive && note.asJsonPrimitive.isString -> note.asString
                                note.isJsonObject -> listOf("text", "title", "description").firstNotNullOfOrNull { key ->
                                    note.asJsonObject.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                                }
                                else -> null
                            }} + listOfNotNull(event.typeLabel?.takeUnless { event.isSubstitution }))
                    })
                }
            TimetableDay(date, cells)
        }.toList()
        return PersonalSchedule(TimetableWeek(child.classId ?: 0, start, end, days), inRange.filter { it.type != "school_hour" && !it.isSubstitution })
    }
}

data class MobileOverview(@SerializedName("latest_grades") val latestGrades: List<RecentGrade> = emptyList())
data class RecentGrade(
    val id: Long, val value: String?, val date: String?, val comment: String?,
    @SerializedName("type_name") val typeName: String?,
    @SerializedName("subject_id") val subjectId: Long?,
    @SerializedName("subject_name") val subjectName: String?,
    @SerializedName("subject_short_name") val subjectShortName: String?
)
data class ConcealedGrades(
    @SerializedName("four_to_five") val fourToFive: GradeBand? = null,
    @SerializedName("three_to_four") val threeToFour: GradeBand? = null,
    @SerializedName("one_to_three") val oneToThree: GradeBand? = null,
    @SerializedName("subjects_without_grades") val withoutGrades: GradeBand? = null
)
data class GradeBand(val subjects: List<ConcealedSubject> = emptyList())
data class ConcealedSubject(@SerializedName("number_grades") val numberGrades: Int,
    @SerializedName("has_final_grade") val hasFinalGrade: Boolean)
data class MobileGrades(val recent: List<RecentGrade>, val summary: ConcealedGrades?, val summaryUnavailable: Boolean = false)
