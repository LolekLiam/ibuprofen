package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimetableAggregationTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private fun lesson(teacher: String? = "M. Example", fullName: String? = null,
        room: String? = "TEST-R1", classLabel: String = "TEST-A") = Lesson(
        "SYN", null, teacher, room, null, false, fullName, classLabel)
    private fun day(date: LocalDate = monday, lesson: Lesson = lesson(), period: Int = 1,
        start: String = "07:10", end: String = "07:55") = TimetableDay(date,
        listOf(PeriodCell.WithLessons(period, LocalTime.parse(start)..LocalTime.parse(end), listOf(lesson))))
    private fun week(vararg days: TimetableDay) = TimetableWeek(1, monday, monday.plusDays(6), days.toList())

    @Test fun printableTeachersAreAvailableWithoutFullNames() {
        val html = javaClass.getResource("/timetable-print.html")!!.readText()
        val schedule = TimetableParser.parse(101, html)
        assertTrue(TimetableAggregation.teachers(listOf(schedule)).contains("R. Sample"))
        assertTrue(TimetableAggregation.forTeacher(listOf(schedule), "R. Sample")!!.days
            .flatMap { it.lessonsByPeriod }.filterIsInstance<PeriodCell.WithLessons>().isNotEmpty())
    }

    @Test fun teacherFilterKeepsDifferentClassesAndDeduplicatesIdenticalLessons() {
        val weeks = listOf(week(day()), week(day(lesson = lesson(classLabel = "TEST-B"))), week(day()),
            week(day(lesson = lesson(teacher = "Other"))))
        val result = TimetableAggregation.forTeacher(weeks, " M. Example ")!!
        val cell = result.days.single().lessonsByPeriod.single() as PeriodCell.WithLessons
        assertEquals(setOf("TEST-A", "TEST-B"), cell.lessons.map { it.sourceClassLabel }.toSet())
        assertEquals(2, cell.lessons.size)
    }

    @Test fun roomsShowClassesAndTeachersOnlyInThatRoom() {
        val weeks = listOf(week(day()), week(day(lesson = lesson(teacher = "Other", classLabel = "TEST-B"))),
            week(day(lesson = lesson(room = "TEST-R2"))))
        assertEquals(listOf("TEST-R1", "TEST-R2"), TimetableAggregation.rooms(weeks))
        val result = TimetableAggregation.forRoom(weeks, "test-r1")!!
        val cell = result.days.single().lessonsByPeriod.single() as PeriodCell.WithLessons
        assertEquals(setOf("M. Example", "Other"), cell.lessons.map { it.teacher }.toSet())
        assertEquals(2, cell.lessons.size)
    }

    @Test fun matchesDatesRatherThanDayPositionsAndUsesRealTimes() {
        val tuesday = monday.plusDays(1)
        val result = TimetableAggregation.forTeacher(listOf(
            week(day(), day(tuesday)),
            week(day(tuesday, lesson(classLabel = "TEST-B"), period = 2)),
            week(day(start = "08:00", end = "08:45"))
        ), "M. Example")!!
        assertEquals(listOf(monday, tuesday), result.days.map { it.date })
        assertEquals(2, result.days.first().lessonsByPeriod.size)
        val tuesdayCell = result.days.last().lessonsByPeriod.single() as PeriodCell.WithLessons
        assertEquals(2, tuesdayCell.lessons.size)
    }

    @Test fun emptyTeacherScheduleRetainsTheWeekDates() {
        val result = TimetableAggregation.forTeacher(listOf(week(day())), "Unknown")!!
        assertEquals(monday, result.days.single().date)
        assertTrue(result.days.single().lessonsByPeriod.isEmpty())
    }
}
