package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MobileTimetableTest {
    private val gson = Gson()
    private fun fixture(name: String) = javaClass.getResource("/mobile/$name")!!.readText()
    private val student = ChildProfile("child_uuid_15_2026_777_88_99", 99, 88, "Example", "1A", "Example School", null)
    private val monday = LocalDate.of(2026, 10, 12)

    @Test fun syntheticScheduleSeparatesSchoolEventsAndUsesLessonTimes() {
        val response = gson.fromJson(fixture("events.json"), MobileTimetableResponse::class.java)
        val result = MobileTimetableMapper.map(response, student, monday, monday.plusDays(4))
        val lessons = result.week.days.flatMap { it.lessonsByPeriod }.filterIsInstance<PeriodCell.WithLessons>()
        assertTrue(lessons.isNotEmpty())
        assertEquals(response.events.count { it.type == "school_hour" || it.isSubstitution }, lessons.sumOf { it.lessons.size })
        assertEquals(2, result.events.size)
        assertEquals(2, result.events.count { it.from == "00:00" && it.to == "23:59" })
        assertTrue(lessons.flatMap { it.lessons }.any { it.isSubstitution })
        assertTrue(lessons.flatMap { it.lessons }.any { it.isCancelled })
        assertEquals(listOf("Bring a workbook", "Example note"), lessons.first().lessons.single().notes)
        val first = lessons.first()
        assertEquals(2, first.periodNumber)
        assertEquals(LocalTime.of(8, 0), first.timeRange.start)
    }

    @Test fun noEventsStillShowsEveryDayInTheRequestedWeek() {
        val mapped = MobileTimetableMapper.map(MobileTimetableResponse(), student, monday, monday.plusDays(4))
        assertEquals(5, mapped.week.days.size)
        assertTrue(mapped.week.days.all { it.lessonsByPeriod.isEmpty() })
    }

    @Test fun multiPeriodEventIsDisplayedOnceAndOutOfRangeEventsAreExcluded() {
        val response = gson.fromJson(fixture("events.json"), MobileTimetableResponse::class.java)
        val lesson = response.events.first { it.type == "school_hour" }.copy(from = "07:10", to = "08:45", scheduleIndices = listOf(1, 2))
        val result = MobileTimetableMapper.map(response.copy(events = listOf(lesson, lesson,
            lesson.copy(slug = "outside", date = monday.minusDays(1).toString()))), student, monday, monday.plusDays(4))
        assertEquals(1, result.week.days.flatMap { it.lessonsByPeriod }.size)
        val cell = result.week.days.flatMap { it.lessonsByPeriod }.single() as PeriodCell.WithLessons
        assertEquals(LocalTime.of(8, 45), cell.timeRange.endInclusive)
        assertEquals(1, cell.lessons.size)
    }

    @Test fun profileIdsAreParsedFromTheNumericSuffix() {
        val child = gson.fromJson(fixture("children.json"), ChildrenResponse::class.java).items.single().toProfile()
        assertEquals(99, child.studentId); assertEquals(88, child.classId); assertEquals(777, child.schoolId)
        assertEquals("2026/27", child.schoolYear)
    }
}
