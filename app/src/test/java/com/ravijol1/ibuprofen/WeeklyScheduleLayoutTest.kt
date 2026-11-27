package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class WeeklyScheduleLayoutTest {
    private val monday = LocalDate.of(2026, 11, 23)
    private fun lesson(from: String = "08:00", to: String = "08:45", change: LessonChange? = null, cancelled: Boolean = false) =
        PeriodCell.WithLessons(1, LocalTime.parse(from)..LocalTime.parse(to), listOf(
            Lesson("SYN", "Synthetic subject", "Teacher", "A1", isCancelled = cancelled, teacherFullName = "Synthetic Teacher",
                notes = listOf("Synthetic note"), change = change)))
    private fun event(slug: String, from: String, to: String, date: LocalDate = monday) = MobileEvent(
        slug, "school_event", date.toString(), from, to, "Synthetic event", "EV", subject = null, classroom = "B1", typeLabel = null, description = "Details")
    private fun schedule(cells: List<PeriodCell> = emptyList(), events: List<MobileEvent> = emptyList()) =
        PersonalSchedule(TimetableWeek(1, monday, monday.plusDays(6), listOf(TimetableDay(monday, cells))), events)

    @Test fun alwaysShowsFiveWeekdaysIncludingEmptyDays() {
        val result = weeklyScheduleLayout(schedule())
        assertEquals((0L..4L).map(monday::plusDays), result.columns.map { it.date })
        assertFalse(result.hasTimedActivities)
        assertTrue(result.columns.all { it.blocks.isEmpty() && it.untimed.isEmpty() })
    }
    @Test fun actualTimesDeterminePositionsAndSharedRangeInsteadOfPeriodNumbers() {
        val result = weeklyScheduleLayout(schedule(listOf(lesson("07:10", "07:55"), lesson("10:00", "10:45"))))
        assertEquals(420, result.startMinute)
        assertEquals(660, result.endMinute)
        assertEquals(listOf(430, 600), result.columns.first().blocks.map { it.startMinute })
        assertEquals(listOf(475, 645), result.columns.first().blocks.map { it.endMinute })
    }
    @Test fun transitiveOverlapsGroupButTouchingIntervalsStaySeparate() {
        val result = weeklyScheduleLayout(schedule(events = listOf(event("a", "08:00", "08:45"),
            event("b", "08:30", "09:00"), event("c", "08:55", "09:30"), event("d", "09:35", "10:20"))))
        val blocks = result.columns.first().blocks
        assertEquals(2, blocks.size)
        assertEquals(listOf("event:$monday:a", "event:$monday:b", "event:$monday:c"), blocks.first().activities.map { it.key })
        assertEquals(575, blocks.first().endMinute)
        val touching = weeklyScheduleLayout(schedule(events = listOf(event("a", "08:00", "08:45"), event("b", "08:45", "09:30"))))
        assertEquals(2, touching.columns.first().blocks.size)
    }
    @Test fun shortActivitiesHaveGroupedMinimumTouchBounds() {
        val blocks = weeklyScheduleLayout(schedule(events = listOf(event("a", "08:00", "08:05"), event("b", "08:20", "08:25")))).columns.first().blocks
        assertEquals(1, blocks.size)
        assertEquals(2, blocks.first().activities.size)
        assertEquals(540, blocks.first().endMinute)
    }
    @Test fun allDayAndUnknownTimesUseSeparateBandWithoutStretchingTheAxis() {
        val result = weeklyScheduleLayout(schedule(listOf(lesson()), listOf(event("all", "00:00", "23:59"), event("unknown", "unknown", "unknown"))))
        assertEquals(480, result.startMinute)
        assertEquals(540, result.endMinute)
        assertEquals(2, result.columns.first().untimed.size)
        assertEquals(1, result.columns.first().untimed.count { it.allDay })
    }
    @Test fun weekendOutOfRangeMalformedAndDuplicateEventsAreNotRepeated() {
        val valid = event("valid", "08:00", "08:45")
        val result = weeklyScheduleLayout(schedule(events = listOf(valid, valid, event("sat", "08:00", "09:00", monday.plusDays(5)),
            event("sun", "08:00", "09:00", monday.plusDays(6)), event("next", "08:00", "09:00", monday.plusDays(7)), valid.copy(slug = "bad", date = "bad"))))
        assertEquals(1, result.columns.flatMap { it.blocks }.flatMap { it.activities }.size)
    }
    @Test fun lessonDetailsAndChangesRemainIntactForSheets() {
        val original = lesson(change = LessonChange(LessonChangeKind.Assignment, "Original", "Replacement note"), cancelled = true)
        val result = weeklyScheduleLayout(schedule(listOf(original)))
        val selected = result.columns.first().blocks.first().activities.first().entry as PersonalScheduleEntry.Lesson
        assertSame(original, selected.cell)
        assertEquals(LessonChangeKind.Assignment, selected.cell.lessons.first().changeKind)
        assertTrue(selected.cell.lessons.first().isCancelled)
        assertEquals(listOf("Synthetic note"), selected.cell.lessons.first().notes)
    }
    @Test fun longAndMidnightEventsRetainTheirDurationAndInvalidEndsStayUsable() {
        val result = weeklyScheduleLayout(schedule(events = listOf(event("long", "08:00", "10:30"), event("night", "23:00", "00:00"))))
        assertEquals(150, result.columns.first().blocks.first().let { it.endMinute - it.startMinute })
        assertEquals(1440, result.endMinute)
        assertEquals(1440, result.columns.first().blocks.last().endMinute)
        assertTrue(weeklyScheduleLayout(schedule(events = listOf(event("bad-end", "12:00", "bad")))).hasTimedActivities)
    }
    @Test fun timeMarkerFollowsSchoolLocalTimeAcrossTheDay() {
        val layout = weeklyScheduleLayout(schedule(listOf(lesson())))
        val atStart = java.time.ZonedDateTime.of(monday, LocalTime.of(8, 0), SCHOOL_ZONE)
        assertEquals(480, weeklyNowIndicator(layout, atStart)?.minute)
        assertEquals(510, weeklyNowIndicator(layout, atStart.plusMinutes(30))?.minute)
        assertEquals(monday, weeklyNowIndicator(layout, atStart.plusMinutes(30))?.date)
    }
    @Test fun timeMarkerIsHiddenOutsideDisplayedDatesOrHoursAndOnEmptyWeeks() {
        val layout = weeklyScheduleLayout(schedule(listOf(lesson())))
        val now = java.time.ZonedDateTime.of(monday, LocalTime.of(8, 30), SCHOOL_ZONE)
        assertNull(weeklyNowIndicator(layout, now.minusHours(2)))
        assertNull(weeklyNowIndicator(layout, now.plusHours(2)))
        assertNull(weeklyNowIndicator(layout, now.plusDays(5)))
        assertNull(weeklyNowIndicator(layout, now.plusWeeks(1)))
        assertNull(weeklyNowIndicator(weeklyScheduleLayout(schedule()), now))
    }
    @Test fun timeMarkerConvertsOtherZonesToSchoolTime() {
        val now = java.time.ZonedDateTime.of(monday, LocalTime.of(7, 30), java.time.ZoneOffset.UTC)
        assertEquals(510, weeklyNowIndicator(weeklyScheduleLayout(schedule(listOf(lesson()))), now)?.minute)
    }
    @Test fun publicTeacherAndRoomLayoutsPreserveOriginatingClasses() {
        fun schoolWeek(id: Int, label: String): TimetableWeek {
            val cell = lesson()
            return TimetableWeek(id, monday, monday.plusDays(4), listOf(TimetableDay(monday,
                listOf(cell.copy(lessons = cell.lessons.map { it.copy(sourceClassLabel = label) })))))
        }
        val weeks = listOf(schoolWeek(1, "A"), schoolWeek(2, "B"))
        val aggregates = listOf(requireNotNull(TimetableAggregation.forTeacher(weeks, "Synthetic Teacher")),
            requireNotNull(TimetableAggregation.forRoom(weeks, "A1")))
        aggregates.forEach { week ->
            val layout = weeklyScheduleLayout(PersonalSchedule(week, emptyList()))
            val cell = (layout.columns.first().blocks.first().activities.first().entry as PersonalScheduleEntry.Lesson).cell
            assertEquals(setOf("A", "B"), cell.lessons.map { it.sourceClassLabel }.toSet())
        }
    }

}
