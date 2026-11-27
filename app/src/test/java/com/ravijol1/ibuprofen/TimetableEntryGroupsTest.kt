package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimetableEntryGroupsTest {
    private val day = LocalDate.of(2026, 11, 23)
    private val math = Lesson("MAT", "Mathematics", "Example Teacher", "101", isCancelled = false, teacherFullName = "Example Teacher", subjectId = 1)
    private val language = math.copy(subjectCode = "LANG", subjectTitle = "Language", subjectId = 2, room = "102")
    private fun schedule(vararg lessons: Lesson, date: LocalDate = day) = PersonalSchedule(TimetableWeek(1, date, date,
        listOf(TimetableDay(date, listOf(PeriodCell.WithLessons(1, LocalTime.of(8, 0)..LocalTime.of(8, 45), lessons.toList()))))), emptyList())
    private fun choose(raw: PersonalSchedule, vararg selected: Lesson): PersonalGroups {
        val entry = discoverTimetableEntries(raw).single()
        return PersonalGroups(entryChoices = mapOf(entry.key to selected.map { it.entryOptionKey() }.toSet()))
    }
    private fun lessons(raw: PersonalSchedule) = raw.week.days.flatMap { it.lessonsByPeriod }.filterIsInstance<PeriodCell.WithLessons>().flatMap { it.lessons }

    @Test fun differentSubjectsInOneEntryCanBeSelectedEvenWithTheSameTeacher() {
        val raw = schedule(math, language)
        assertEquals(2, discoverTimetableEntries(raw).single().options.size)
        val preferences = choose(raw, language)
        assertEquals(listOf(language), lessons(filterPersonalGroups(raw, preferences)))
        val reminder = lessonReminderPlan(raw.week, day.atStartOfDay(SCHOOL_ZONE).toInstant()).single()
        assertEquals(listOf("LANG"), visibleGroupReminder(reminder, preferences)!!.subjects)
    }
    @Test fun explicitGroupsOfTheSameSubjectRemainDistinctWithTheSameTeacherAndRoom() {
        val first = math.copy(groupLabel = "Skupina 1")
        val second = math.copy(groupLabel = "Skupina 2")
        val raw = schedule(first, second)
        val entry = discoverTimetableEntries(raw).single()
        assertTrue(entry.options[0].label.contains("Skupina 1"))
        assertTrue(entry.options[1].label.contains("Skupina 2"))
        assertEquals(listOf(second), lessons(filterPersonalGroups(raw, choose(raw, second))))
    }
    @Test fun multipleChoicesAndShowAllAreSupported() {
        val third = math.copy(subjectId = 3, subjectCode = "SCI", subjectTitle = "Science")
        val raw = schedule(math, language, third)
        assertEquals(listOf(math, third), lessons(filterPersonalGroups(raw, choose(raw, math, third))))
        assertEquals(3, lessons(filterPersonalGroups(raw, PersonalGroups())).size)
        val empty = choose(raw, math).copy(entryChoices = mapOf(discoverTimetableEntries(raw).single().key to emptySet()))
        assertEquals(3, lessons(filterPersonalGroups(raw, empty)).size)
    }
    @Test fun entryOrderDoesNotMatterAndChoicesDoNotCarryIntoAnotherRotation() {
        val raw = schedule(math, language)
        val preferences = choose(raw, math)
        assertEquals(listOf(math), lessons(filterPersonalGroups(schedule(language, math), preferences)))
        assertEquals(2, lessons(filterPersonalGroups(schedule(math, language, date = day.plusWeeks(1)), preferences)).size)
        assertEquals(listOf(language), lessons(filterPersonalGroups(schedule(language), preferences)))
    }
    @Test fun changedAlternativesRemainVisibleInsteadOfGuessingMembership() {
        val raw = schedule(math, language)
        val replacement = language.copy(teacher = "Replacement", teacherFullName = "Replacement", isSubstitution = true)
        assertEquals(2, lessons(filterPersonalGroups(schedule(math, replacement), choose(raw, math))).size)
    }
    @Test fun repeatedEntriesShareTheirSelectionButOnlyWithinTheSameWeek() {
        val raw = schedule(math, language)
        val nextDay = raw.week.days.single().copy(date = day.plusDays(1))
        val repeated = raw.copy(week = raw.week.copy(days = raw.week.days + nextDay))
        assertEquals(1, discoverTimetableEntries(repeated).size)
        assertEquals(2, discoverTimetableEntries(repeated).single().occurrences.size)
        assertEquals(2, lessons(filterPersonalGroups(repeated, choose(raw, math))).size)
    }
    @Test fun cancelledAlternativeDoesNotAccidentallyLeaveTheOtherGroupReminderEnabled() {
        val cancelled = math.copy(isCancelled = true)
        val raw = schedule(cancelled, language)
        val preferences = choose(raw, cancelled)
        val reminder = lessonReminderPlan(raw.week, day.atStartOfDay(SCHOOL_ZONE).toInstant()).single()
        assertNull(visibleGroupReminder(reminder, preferences))
    }
    @Test fun entryChoicesAndReminderContextSurviveJsonRoundTrips() {
        val raw = schedule(math, language)
        val gson = Gson()
        val preferences = gson.fromJson(gson.toJson(choose(raw, language)), PersonalGroups::class.java)
        val reminder = lessonReminderPlan(raw.week, day.atStartOfDay(SCHOOL_ZONE).toInstant()).single()
        val restored = gson.fromJson(gson.toJson(reminder), LessonReminder::class.java)
        assertEquals(listOf("LANG"), visibleGroupReminder(restored, preferences)!!.subjects)
        assertNull(visibleGroupReminder(restored.copy(entryGroupsKnown = false), preferences))
    }
    @Test fun apiGroupLabelsAreUsedWithoutInventingNumberedGroups() {
        val gson = Gson()
        for (key in listOf("group_name", "group_label", "groupLabel")) {
            assertEquals("Skupina 2", mobileGroupLabel(gson.fromJson("""{"$key":"Skupina 2"}""", MobileEvent::class.java)))
        }
        assertEquals("Skupina 1", mobileGroupLabel(gson.fromJson("""{"group":{"name":"Skupina 1"}}""", MobileEvent::class.java)))
        assertEquals("skupina 2", mobileGroupLabel(gson.fromJson("""{"title":"Math – skupina 2"}""", MobileEvent::class.java)))
        assertNull(mobileGroupLabel(gson.fromJson("""{"title":"Group projects"}""", MobileEvent::class.java)))
    }
}
