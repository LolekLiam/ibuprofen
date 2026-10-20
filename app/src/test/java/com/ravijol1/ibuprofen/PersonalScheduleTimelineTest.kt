package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PersonalScheduleTimelineTest {
    private val date = LocalDate.of(2026, 10, 12)
    private val template = Gson().fromJson(javaClass.getResource("/mobile/events.json")!!.readText(),
        MobileTimetableResponse::class.java).events.first { it.type == "school_event" }
    private fun event(slug: String, from: String, to: String) = template.copy(slug = slug, date = date.toString(), from = from, to = to)
    private fun lesson(start: String, period: Int) = PeriodCell.WithLessons(period,
        LocalTime.parse(start)..LocalTime.parse(start).plusMinutes(45), emptyList())

    @Test fun timedEventsAppearBeforeBetweenAndAfterLessonsByTheirStartTime() {
        val day = TimetableDay(date, listOf(lesson("10:00", 3), lesson("08:00", 1), PeriodCell.Empty))
        val timeline = personalScheduleTimeline(day, listOf(event("after", "11:00", "12:00"),
            event("between", "09:00", "09:30"), event("before", "07:30", "07:50")))
        assertEquals(listOf("07:30", "08:00", "09:00", "10:00", "11:00"), timeline.map { it.startTime.toString() })
    }

    @Test fun allDayEventsRemainAboveTimedEntriesAndOtherDatesAreExcluded() {
        val day = TimetableDay(date, listOf(lesson("08:00", 1)))
        val timeline = personalScheduleTimeline(day, listOf(event("timed", "09:00", "10:00"),
            event("all-day", "00:00", "23:59"), event("other-day", "07:00", "08:00").copy(date = date.plusDays(1).toString())))
        assertEquals(3, timeline.size)
        assertEquals("all-day", (timeline.first() as PersonalScheduleEntry.Event).event.slug)
        assertTrue(timeline[1] is PersonalScheduleEntry.Lesson)
    }

    @Test fun overlappingEventIsShownOnceAtItsStartAndEqualStartsAreDeterministic() {
        val day = TimetableDay(date, listOf(lesson("08:00", 1), lesson("09:00", 2)))
        val timeline = personalScheduleTimeline(day, listOf(event("long", "08:00", "10:30")))
        assertEquals(3, timeline.size)
        assertTrue(timeline.first() is PersonalScheduleEntry.Event)
        assertTrue(timeline[1] is PersonalScheduleEntry.Lesson)
        assertEquals(1, timeline.filterIsInstance<PersonalScheduleEntry.Event>().size)
    }

    @Test fun eventOnlyDaysRemainVisibleAndInvalidTimesDoNotCrashTheTimeline() {
        val day = TimetableDay(date, emptyList())
        val timeline = personalScheduleTimeline(day, listOf(event("unknown", "unknown", "unknown"), event("timed", "09:00", "10:00")))
        assertEquals("timed", (timeline.first() as PersonalScheduleEntry.Event).event.slug)
        assertEquals("unknown", (timeline.last() as PersonalScheduleEntry.Event).event.slug)
        assertTrue(personalScheduleTimeline(day, emptyList()).isEmpty())
    }
}
