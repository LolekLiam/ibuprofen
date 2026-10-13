package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimetableParserTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun publicSchoolProvidesClassesAndCurrentWeek() {
        val meta = SchoolPageParser.parse(fixture("school.html"), "school-key")
        assertEquals(251, meta.schoolId)
        assertEquals(7, meta.currentWeekId)
        assertTrue(meta.classes.any { it.id == 810899 && it.label == "E1A" })
        assertFalse(meta.classes.any { it.label.all(Char::isDigit) })
        assertTrue(meta.allWeekIds!!.contains(7))
    }

    @Test fun printableHtmlContainsDatesLessonsAndTimes() {
        val week = TimetableParser.parse(810899, fixture("timetable-print.html"))
        assertEquals(LocalDate.of(2026, 10, 12), week.weekStart)
        assertEquals(LocalDate.of(2026, 10, 18), week.weekEnd)
        assertEquals(5, week.days.size)
        val first = week.days.first().lessonsByPeriod.first() as PeriodCell.WithLessons
        assertEquals(LocalTime.of(7, 10), first.timeRange.start)
        assertEquals("ITP", first.lessons.first().subjectCode)
        assertEquals("D3.1", first.lessons.first().room)
        assertEquals(first.lessons.distinct(), first.lessons)
    }

    @Test fun legacyAjaxStillParses() {
        val html = fixture("timetable-print.html")
        val week = TimetableParser.parse(1, "ok\u001F12. 10. 2026\u001F18. 10. 2026\u001F$html")
        assertEquals(5, week.days.size)
    }

    @Test(expected = IllegalStateException::class)
    fun serviceErrorIsNotAnEmptySchedule() {
        TimetableParser.parse(1, "<html>Service unavailable</html>")
    }
}
