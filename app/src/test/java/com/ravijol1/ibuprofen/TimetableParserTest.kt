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
        assertEquals("school-key", meta.schoolKey)
        assertEquals(7, meta.currentWeekId)
        assertTrue(meta.classes.any { it.id == 101 && it.label == "TEST-A" })
        assertFalse(meta.classes.any { it.label.all(Char::isDigit) })
        assertTrue(meta.allWeekIds!!.contains(7))
    }

    @Test fun schoolMetadataUsesStringKeyWithoutAnIdScript() {
        val key = "synthetic-school-hash-a1b2"
        val html = fixture("school.html").replace(Regex("<script>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        val meta = SchoolPageParser.parse(html, key)
        assertEquals(key, meta.schoolKey)
        assertTrue(meta.classes.isNotEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun invalidSchoolPageIsNotAcceptedAsEmptyMetadata() {
        SchoolPageParser.parse("<html>Service unavailable</html>", "school-key")
    }

    @Test fun printableHtmlContainsDatesLessonsAndTimes() {
        val week = TimetableParser.parse(101, fixture("timetable-print.html"))
        assertEquals(LocalDate.of(2026, 10, 12), week.weekStart)
        assertEquals(LocalDate.of(2026, 10, 18), week.weekEnd)
        assertEquals(5, week.days.size)
        val first = week.days.first().lessonsByPeriod.first() as PeriodCell.WithLessons
        assertEquals(LocalTime.of(7, 10), first.timeRange.start)
        assertEquals("SYN", first.lessons.first().subjectCode)
        assertEquals("TEST-R1", first.lessons.first().room)
        assertEquals(3, week.days.first().lessonsByPeriod.size)
        assertEquals(1, first.lessons.size) // Visible/hidden copies are deduplicated.
        assertEquals("Morgan Example", first.lessons.single().teacherFullName)
        val split = week.days.first().lessonsByPeriod[1] as PeriodCell.WithLessons
        assertEquals(listOf("Group One", "Group Two"), split.lessons.map { it.groupLabel })
        val cancelled = week.days.first().lessonsByPeriod[2] as PeriodCell.WithLessons
        assertTrue(cancelled.lessons.single().isCancelled)
        val tagged = week.days[1].lessonsByPeriod.first() as PeriodCell.WithLessons
        assertTrue(tagged.lessons.single().isCancelled)
        assertTrue(week.days.last().lessonsByPeriod.all { it is PeriodCell.Empty })
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
