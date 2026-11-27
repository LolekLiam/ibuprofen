package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PersonalGroupsTest {
    private val date = LocalDate.of(2026, 11, 23)
    private fun lesson(teacher: String, room: String = "101", id: Long = 1) = Lesson("LAB", "Example lab", teacher, room,
        isCancelled = false, teacherFullName = teacher, subjectId = id)
    private val first = lesson("Example teacher A")
    private val second = lesson("Example teacher B", "102")
    private fun schedule(vararg lessons: Lesson): PersonalSchedule = PersonalSchedule(TimetableWeek(1, date, date,
        listOf(TimetableDay(date, listOf(PeriodCell.WithLessons(1, LocalTime.of(8, 0)..LocalTime.of(8, 45), lessons.toList()))))), emptyList())
    private fun lessons(value: PersonalSchedule) = value.week.days.flatMap { it.lessonsByPeriod }
        .filterIsInstance<PeriodCell.WithLessons>().flatMap { it.lessons }
    private fun chosen(): PersonalGroups {
        val catalog = discoverPersonalGroups(schedule(first, second), PersonalGroups())
        return catalog.copy(choices = mapOf(groupWeekChoiceKey(first.groupSubjectKey()!!, groupWeekKey(date)) to ScheduleGroupChoice(first.groupVariantKey(), catalog.subjects.single().variants.map { it.key }.toSet())))
    }
    @Test fun defaultDoesNotHideAnything() {
        assertEquals(2, lessons(filterPersonalGroups(schedule(first, second), PersonalGroups())).size)
    }
    @Test fun concurrentGroupsAreDiscoveredAndSelectedGroupFiltersBothViewsData() {
        val preferences = chosen()
        assertTrue(preferences.subjects.single().split)
        assertEquals(2, preferences.subjects.single().variants.size)
        val result = filterPersonalGroups(schedule(first, second), preferences)
        assertEquals(listOf(first), lessons(result))
        assertEquals(1, weeklyScheduleLayout(result).columns.first().blocks.single().activities.size)
    }
    @Test fun roomChangesDoNotCreateGroupsOrLoseSelection() {
        val catalog = discoverPersonalGroups(schedule(first), PersonalGroups())
        val moved = first.copy(room = "999")
        val merged = discoverPersonalGroups(schedule(moved), catalog)
        assertFalse(merged.subjects.single().split)
        assertEquals(1, merged.subjects.single().variants.size)
        assertEquals(listOf("101", "999"), merged.subjects.single().variants.single().rooms)
        assertEquals(listOf(moved), lessons(filterPersonalGroups(schedule(moved, second), chosen())))
    }
    @Test fun unknownTeachersAndSubstitutionsRemainVisible() {
        val replacement = second.copy(isSubstitution = true)
        val newTeacher = lesson("New teacher", "103")
        val result = filterPersonalGroups(schedule(first, replacement, newTeacher), chosen())
        assertEquals(3, lessons(result).size)
        val expanded = discoverPersonalGroups(schedule(first, second, newTeacher), chosen())
        assertEquals(2, lessons(filterPersonalGroups(schedule(first, second, newTeacher), expanded)).size)
    }
    @Test fun hiddenSubjectsAreRemovedButOtherSubjectsAndEventsRemain() {
        val event = Gson().fromJson("""{"slug":"event","type":"event","date":"2026-11-23","from":"09:00","to":"10:00","title":"School event"}""", MobileEvent::class.java)
        val preferences = chosen().copy(choices = mapOf(first.groupSubjectKey()!! to ScheduleGroupChoice(hidden = true)))
        val other = lesson("Other teacher", id = 2)
        val result = filterPersonalGroups(schedule(first, second, other).copy(events = listOf(event)), preferences)
        assertEquals(listOf(other), lessons(result)); assertEquals(listOf(event), result.events)
        assertTrue(filterPersonalGroups(schedule(first, second), preferences).week.days.single().lessonsByPeriod.isEmpty())
    }
    @Test fun choicesPersistAcrossWeeksAndJsonRoundTripAndCanBeReset() {
        val preferences = Gson().fromJson(Gson().toJson(chosen()), PersonalGroups::class.java)
        assertEquals(1, lessons(filterPersonalGroups(schedule(first, second), preferences)).size)
        val merged = discoverPersonalGroups(schedule(second), preferences)
        assertEquals(preferences.choices, merged.choices)
        assertEquals(2, merged.subjects.single().variants.size)
        assertEquals(2, lessons(filterPersonalGroups(schedule(first, second), preferences.copy(choices = emptyMap()))).size)
    }
    @Test fun sameTeacherAcrossDifferentSubjectsIsNotAGroupMembership() {
        val other = first.copy(subjectId = 2)
        val result = filterPersonalGroups(schedule(second, other), chosen())
        assertEquals(listOf(second, other), lessons(result))
    }
    @Test fun preferencesAreScopedToStudentAndSchoolYearWithWorkerFallback() {
        val uuid = "child_uuid_15_2026_777_88_99"
        assertEquals(groupPreferencesKey(uuid, "2026/27"), groupPreferencesKey(uuid, null))
        assertEquals(groupPreferencesKey(uuid, "2026/27"), groupPreferencesKey(uuid, "2026/2027"))
        assertNotEquals(groupPreferencesKey(uuid, "2026/27"), groupPreferencesKey(uuid, "2027/28"))
        assertNotEquals(groupPreferencesKey(uuid, null), groupPreferencesKey("child_uuid_16_2026_777_88_99", null))
    }
    @Test fun remindersCanBeRefilteredOfflineWithoutLosingOtherGroupOptions() {
        val raw = lessonReminderPlan(schedule(first, second).week, date.atStartOfDay(SCHOOL_ZONE).toInstant()).single()
        val visible = visibleGroupReminder(raw, chosen())!!
        assertEquals(listOf("101"), visible.rooms)
        assertEquals(2, visible.groupOptions!!.size)
        val restored = Gson().fromJson(Gson().toJson(raw), LessonReminder::class.java)
        assertEquals(listOf("101"), visibleGroupReminder(restored, chosen())!!.rooms)
        assertEquals(listOf("101", "102"), visibleGroupReminder(restored, PersonalGroups())!!.rooms)
        val hidden = chosen().copy(choices = mapOf(first.groupSubjectKey()!! to ScheduleGroupChoice(hidden = true)))
        assertNull(visibleGroupReminder(restored, hidden))
    }
    @Test fun rotatingGroupsAreSelectedIndependentlyForEachWeek() {
        val nextDate = date.plusWeeks(1)
        val next = schedule(first, second).copy(week = schedule(first, second).week.copy(weekStart = nextDate, weekEnd = nextDate,
            days = listOf(schedule(first, second).week.days.single().copy(date = nextDate))))
        assertEquals(2, lessons(filterPersonalGroups(next, chosen())).size)
        val nextChoice = groupWeekChoiceKey(first.groupSubjectKey()!!, groupWeekKey(nextDate))
        val preferences = chosen().copy(choices = chosen().choices + (nextChoice to ScheduleGroupChoice(second.groupVariantKey(), setOf(first.groupVariantKey()!!, second.groupVariantKey()!!))))
        assertEquals(listOf(second), lessons(filterPersonalGroups(next, preferences)))
        assertEquals(listOf(first), lessons(filterPersonalGroups(schedule(first, second), preferences)))
        val reminder = lessonReminderPlan(next.week, date.atStartOfDay(SCHOOL_ZONE).toInstant()).single()
        assertEquals(listOf("102"), visibleGroupReminder(reminder, preferences)!!.rooms)
    }
    @Test fun pastWeeksAndSharedSingleGroupSessionsAreNotHiddenByTeacherChoice() {
        assertEquals(listOf(second), lessons(filterPersonalGroups(schedule(second), chosen())))
        val pastDate = date.minusWeeks(1)
        val past = schedule(first, second).copy(week = schedule(first, second).week.copy(weekStart = pastDate,
            days = listOf(schedule(first, second).week.days.single().copy(date = pastDate))))
        assertEquals(2, lessons(filterPersonalGroups(past, chosen())).size)
    }
    @Test fun legacyAlarmsRequireRefreshOnlyWhenAGroupFilterIsActive() {
        val legacy = lessonReminderPlan(schedule(first).week, date.atStartOfDay(SCHOOL_ZONE).toInstant()).single().copy(groupOptions = null)
        assertEquals(legacy, visibleGroupReminder(legacy, PersonalGroups()))
        assertNull(visibleGroupReminder(legacy, chosen()))
    }
}
