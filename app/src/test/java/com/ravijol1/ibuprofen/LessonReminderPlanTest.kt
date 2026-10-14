package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class LessonReminderPlanTest {
    private val monday = LocalDate.of(2026, 10, 12)
    private fun lesson(subject: String = "SYN", room: String = "TEST-R1", cancelled: Boolean = false) =
        Lesson(subject, null, "Example Teacher", room, null, cancelled, null)
    private fun cell(start: String, vararg lessons: Lesson, end: String = "09:45") =
        PeriodCell.WithLessons(1, LocalTime.parse(start)..LocalTime.parse(end), lessons.toList())
    private fun week(vararg days: TimetableDay) = TimetableWeek(88, monday, monday.plusDays(6), days.toList())

    @Test fun schedulesEveryUpcomingLessonWithoutDependingOnPreviousAlarmDelivery() {
        val plan = lessonReminderPlan(week(
            TimetableDay(monday, listOf(cell("08:00", lesson()), cell("09:00", lesson("ART")))),
            TimetableDay(monday.plusDays(1), listOf(cell("08:00", lesson("SCI"))))
        ), Instant.parse("2026-10-12T04:00:00Z"))
        assertEquals(3, plan.size)
        assertEquals(listOf("2026-10-12", "2026-10-12", "2026-10-13"), plan.map { it.dateIso })
        assertEquals(3, plan.map { it.startEpoch }.distinct().size)
        assertTrue(plan.all { it.fireAtEpoch == it.startEpoch - 300 })
        assertEquals(Instant.parse("2026-10-12T05:55:00Z").epochSecond, plan.first().fireAtEpoch)
    }

    @Test fun excludesPastCancelledAndEmptyPeriodsWhileKeepingActiveSplitGroups() {
        val plan = lessonReminderPlan(week(TimetableDay(monday, listOf(
            cell("08:00", lesson()),
            cell("09:00", lesson(cancelled = true)),
            cell("00:00", lesson(), end = "00:00"),
            PeriodCell.Empty,
            cell("11:00", lesson(), lesson("Cancelled", cancelled = true), end = "11:45")
        ))), Instant.parse("2026-10-12T06:01:00Z"))
        assertEquals(1, plan.size)
        assertEquals("11:00", plan.single().timeHm)
        assertEquals(listOf("SYN"), plan.single().subjects)
    }

    @Test fun simultaneousLessonsShareOneAlarmAndKeepDistinctSubjectsAndRooms() {
        val plan = lessonReminderPlan(week(TimetableDay(monday, listOf(
            cell("08:00", lesson(), lesson("ART", "TEST-R2")),
            cell("08:00", lesson())
        ))), Instant.parse("2026-10-12T04:00:00Z"))
        assertEquals(1, plan.size)
        assertEquals(listOf("SYN", "ART"), plan.single().subjects)
        assertEquals(listOf("TEST-R1", "TEST-R2"), plan.single().rooms)
    }

    @Test fun planningInsideFiveMinuteWindowFiresNowWithAccurateRemainingTime() {
        val now = Instant.parse("2026-10-12T05:58:00Z")
        val reminder = lessonReminderPlan(week(TimetableDay(monday, listOf(cell("08:00", lesson())))), now).single()
        assertEquals(now.epochSecond, reminder.fireAtEpoch)
        assertEquals("Čez 2 min: SYN", reminder.title(now.epochSecond))
    }

    @Test fun deliveryRejectsDuplicatesEarlyAndExpiredAlarms() {
        val start = 1000L
        assertFalse(shouldDeliverReminder(start, 699, null))
        assertTrue(shouldDeliverReminder(start, 700, null))
        assertTrue(shouldDeliverReminder(start, 999, null))
        assertFalse(shouldDeliverReminder(start, 999, start))
        assertFalse(shouldDeliverReminder(start, 1000, null))
        assertFalse(shouldDeliverReminder(start, 2000, null))
        assertTrue(shouldDeliverReminder(2000, 1700, start))
    }

    @Test fun schoolTimezoneHandlesWinterOffsetAndFollowingWeek() {
        val winterMonday = LocalDate.of(2026, 10, 26)
        val reminder = lessonReminderPlan(week(TimetableDay(winterMonday, listOf(cell("08:00", lesson())))),
            Instant.parse("2026-10-23T04:00:00Z")).single()
        assertEquals(Instant.parse("2026-10-26T06:55:00Z").epochSecond, reminder.fireAtEpoch)
    }
}
