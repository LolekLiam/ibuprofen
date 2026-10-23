package com.ravijol1.ibuprofen.data

import java.time.LocalDate
import java.time.LocalTime

// Metadata for a school and its classes
data class SchoolMeta(
    val schoolKey: String,
    val classes: List<ClassInfo>,
    val teachers: List<TeacherInfo> = emptyList(), // Proxy might provide this
    val schoolName: String? = null,
    val currentWeekId: Int? = null,
    val currentWeekLabel: String? = null,
    val allWeekIds: List<Int>? = emptyList()
)

data class TeacherInfo(
    val id: Int? = 0,
    val full_name: String? = "",
    val short_name: String? = ""
)

data class ClassInfo(
    val id: Int,
    val label: String
)

// One week for one class
data class TimetableWeek(
    val classId: Int,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val days: List<TimetableDay>
)

data class TimetableDay(
    val date: LocalDate,
    val lessonsByPeriod: List<PeriodCell>
)

sealed class PeriodCell {
    data object Empty : PeriodCell()
    data class WithLessons(
        val periodNumber: Int,
        val timeRange: ClosedRange<LocalTime>,
        val lessons: List<Lesson>
    ) : PeriodCell()
}

data class Lesson(
    val subjectCode: String?,
    val subjectTitle: String?,
    val teacher: String?,
    val room: String?,
    val groupLabel: String? = null,
    val isCancelled: Boolean,
    val teacherFullName: String?, // from .ednevnik-subtitle@title
    val sourceClassLabel: String? = null, // originating class label (for teacher mode cross-reference)
    val sourceClassId: String? = null, // proxy provides this
    val isSubstitution: Boolean = false,
    val notes: List<String> = emptyList()
)

// Proxy response models
data class TimetableWeekProxy(
    val classId: Int,
    val weekStart: String, // YYYY-MM-DD
    val weekEnd: String,   // YYYY-MM-DD
    val days: List<TimetableDayProxy>
)

data class TimetableDayProxy(
    val date: String, // YYYY-MM-DD
    val lessonsByPeriod: List<PeriodCellProxy>
)

data class PeriodCellProxy(
    val periodNumber: Int,
    val timeRange: TimeRangeProxy,
    val lessons: List<LessonProxy>
)

// Keep nullable wire fields separate from the non-null UI model: Gson does not
// apply Kotlin defaults when optional JSON fields are absent.
data class LessonProxy(
    val subjectCode: String?,
    val subjectTitle: String?,
    val teacher: String?,
    val room: String?,
    val groupLabel: String?,
    val isCancelled: Boolean = false,
    val teacherFullName: String?,
    val sourceClassLabel: String?,
    val sourceClassId: String?,
    val isSubstitution: Boolean = false,
    val notes: List<String?>? = null
) {
    fun toDomain() = Lesson(
        subjectCode = subjectCode, subjectTitle = subjectTitle, teacher = teacher,
        room = room, groupLabel = groupLabel, isCancelled = isCancelled,
        teacherFullName = teacherFullName, sourceClassLabel = sourceClassLabel,
        sourceClassId = sourceClassId, isSubstitution = isSubstitution,
        notes = notes.orEmpty().filterNotNull()
    )
}

data class TimeRangeProxy(val start: String, val end: String)

data class HealthStatus(
    val status: String,
    val timestamp: Long
)

data class SyncResponse(
    val status: String,
    val school: String?,
    val weeks_total: Int = 0,
    val classes_total: Int = 0,
    val synced_calls: Int = 0,
    val errors: List<String> = emptyList()
)

data class TeacherTimetableResponse(
    val teacher: TeacherInfo?,
    val weekStart: String?,
    val weekEnd: String?,
    val lessons: List<TeacherLessonProxy>?,
    val error: String? = null,
    val suggestion: String? = null
)

data class TeachersResponse(
    val teachers: List<TeacherInfo>
)

data class TeacherLessonProxy(
    val periodNumber: Int,
    val timeRange: TimeRangeProxy,
    val subjectCode: String?,
    val subjectTitle: String?,
    val teacher: String?,
    val room: String?,
    val groupLabel: String? = null,
    val isCancelled: Boolean,
    val teacherFullName: String?,
    val sourceClassLabel: String?,
    val sourceClassId: String?,
    val date: String // YYYY-MM-DD
)

fun TeacherTimetableResponse.toDomain(): TimetableWeek {
    val nonNullLessons = lessons ?: emptyList()
    val lessonsByDate = nonNullLessons.groupBy { it.date }
    val days = lessonsByDate.map { (date, lessons) ->
        val periods = lessons.groupBy { it.periodNumber }.map { (pNum, pLessons) ->
            val first = pLessons.first()
            PeriodCell.WithLessons(
                periodNumber = pNum,
                timeRange = LocalTime.parse(first.timeRange.start)..LocalTime.parse(first.timeRange.end),
                lessons = pLessons.map { it.toLesson() }
            )
        }
        TimetableDay(LocalDate.parse(date), periods)
    }.sortedBy { it.date }

    return TimetableWeek(
        classId = 0, // Not applicable for teacher mode
        weekStart = LocalDate.parse(weekStart ?: LocalDate.now().toString()),
        weekEnd = LocalDate.parse(weekEnd ?: LocalDate.now().toString()),
        days = days
    )
}

fun TeacherLessonProxy.toLesson(): Lesson {
    return Lesson(
        subjectCode = subjectCode,
        subjectTitle = subjectTitle,
        teacher = teacher,
        room = room,
        groupLabel = groupLabel,
        isCancelled = isCancelled,
        teacherFullName = teacherFullName,
        sourceClassLabel = sourceClassLabel,
        sourceClassId = sourceClassId
    )
}

fun TimetableWeekProxy.toDomain(requestedClassId: Int): TimetableWeek {
    return TimetableWeek(
        classId = classId.takeIf { it != 0 } ?: requestedClassId,
        weekStart = LocalDate.parse(weekStart),
        weekEnd = LocalDate.parse(weekEnd),
        days = days.map { it.toDomain() }
    )
}

fun TimetableDayProxy.toDomain(): TimetableDay {
    return TimetableDay(
        date = LocalDate.parse(date),
        lessonsByPeriod = lessonsByPeriod.map { it.toDomain() }
    )
}

fun PeriodCellProxy.toDomain(): PeriodCell {
    if (lessons.isEmpty()) return PeriodCell.Empty
    return PeriodCell.WithLessons(
        periodNumber = periodNumber,
        timeRange = LocalTime.parse(timeRange.start)..LocalTime.parse(timeRange.end),
        lessons = lessons.map { it.toDomain() }
    )
}
