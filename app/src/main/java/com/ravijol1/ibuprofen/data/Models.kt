package com.ravijol1.ibuprofen.data

import java.time.LocalDate
import java.time.LocalTime

// Metadata for a school and its classes
data class SchoolMeta(
    val schoolKey: String,
    val schoolId: Int,
    val classes: List<ClassInfo>
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
    val groupLabel: String?,
    val isCancelled: Boolean,
    val teacherFullName: String?, // from .ednevnik-subtitle@title
    val sourceClassLabel: String? // originating class label (for teacher mode cross-reference)
)