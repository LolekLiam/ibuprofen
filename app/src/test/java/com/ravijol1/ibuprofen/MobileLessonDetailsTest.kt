package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Original synthetic examples of the observed schema; no tester data is embedded. */
class MobileLessonDetailsTest {
    private val date = LocalDate.of(2026, 11, 23)
    private val child = ChildProfile("synthetic-profile", 1, 1, "Example", "A", "School", null)
    private val gson = Gson()
    private val base = gson.fromJson("""{
        "schedule":[{"name":"1. hour","short_name":"1","from":"08:00","to":"08:45"}],
        "events":[{"slug":"lesson-a","type":"school_hour","date":"2026-11-23","from":"08:00","to":"08:45",
        "title":"Mathematics","title_short":"MAT","subject":{"subject_id":1,"title":"Mathematics","title_short":"MAT"},
        "teachers":["Example Teacher A"],"classroom":"101","notes":[],"schedule_index":[0]}]}
        """, MobileTimetableResponse::class.java)
    private val lesson get() = base.events.single()
    private fun marker() = lesson.copy(slug = "cancel-marker", type = "event", subject = null, teachers = emptyList(),
        classroom = null, title = "MAT", shortTitle = "MAT", isCancelled = true, typeLabel = "NI BILO POUKA")
    private fun map(vararg events: MobileEvent) = MobileTimetableMapper.map(base.copy(events = events.toList()), child, date, date)
    private fun lessons(result: PersonalSchedule) = result.week.days.flatMap { it.lessonsByPeriod }
        .filterIsInstance<PeriodCell.WithLessons>().flatMap { it.lessons }

    @Test fun observedCancellationSpellingAndLegacySpellingAreBothSupported() {
        for (key in listOf("is_canceled", "is_cancelled")) {
            val event = gson.fromJson("""{"$key":true}""", MobileEvent::class.java)
            assertTrue(key, event.isCancelled)
        }
    }
    @Test fun cancellationMarksTheOriginalLessonRemovesItsMarkerAndSkipsReminder() {
        val result = map(lesson, marker())
        assertEquals(1, lessons(result).size)
        assertTrue(lessons(result).single().isCancelled)
        assertTrue(result.events.isEmpty())
        assertTrue(lessonReminderPlan(result.week, date.atStartOfDay(SCHOOL_ZONE).toInstant()).isEmpty())
    }
    @Test fun multipleCancellationMarkersForOneLessonAreConsumedWithoutDuplicates() {
        val markers = (1..4).map { marker().copy(slug = "marker-$it") }
        val result = map(lesson, *markers.toTypedArray())
        assertEquals(1, lessons(result).size)
        assertTrue(lessons(result).single().isCancelled)
        assertTrue(result.events.isEmpty())
    }
    @Test fun cancellationDoesNotAffectAnotherSubjectInTheSamePeriod() {
        val other = lesson.copy(slug = "other", subject = MobileSubject(2, "Language", "LAN"), title = "Language", shortTitle = "LAN")
        val result = map(lesson, marker(), other)
        assertTrue(lessons(result).first { it.subjectCode == "MAT" }.isCancelled)
        assertFalse(lessons(result).first { it.subjectCode == "LAN" }.isCancelled)
        assertEquals(listOf("LAN"), lessonReminderPlan(result.week, date.atStartOfDay(SCHOOL_ZONE).toInstant()).single().subjects)
    }
    @Test fun teacherDisambiguatesParallelGroupsOfTheSameSubject() {
        val other = lesson.copy(slug = "parallel", teachers = listOf("Example Teacher B"), classroom = "102")
        val result = map(lesson, other, marker().copy(teachers = lesson.teachers))
        assertTrue(lessons(result).first { it.room == "101" }.isCancelled)
        assertFalse(lessons(result).first { it.room == "102" }.isCancelled)
    }
    @Test fun ambiguousAndUnmatchedCancellationMarkersRemainVisible() {
        val other = lesson.copy(slug = "parallel", teachers = listOf("Example Teacher B"))
        val ambiguous = map(lesson, other, marker())
        assertFalse(lessons(ambiguous).any { it.isCancelled })
        assertEquals(1, ambiguous.events.size)
        val unmatched = map(marker())
        assertTrue(unmatched.events.single().isCancelled)
    }
    @Test fun cancellationOnAnotherDayOrSubjectDoesNotCancelAnActiveLesson() {
        val result = map(lesson, marker().copy(date = date.plusDays(1).toString()), marker().copy(title = "Other", shortTitle = "OTHER"))
        assertFalse(lessons(result).single().isCancelled)
    }
    @Test fun assessmentAndMultipleHomeworkItemsSurviveMappingWithDatesAndDetails() {
        val event = gson.fromJson("""{
          "slug":"work","type":"school_hour","date":"2026-11-23","from":"08:00","to":"08:45",
          "title":"Mathematics","title_short":"MAT","schedule_index":[0],
          "evaluation":{"evaluation_id":42,"title":"Written assessment","summary":"<p>Chapters 1–2</p>","grade_type":"Written"},
          "homework":[{"due_date":"2026-11-25","title":"Exercises","summary":"<b>Page 10</b>"},{"title":"Revision"}]
        }""", MobileEvent::class.java)
        val mapped = lessons(map(event)).single()
        assertEquals(42L, mapped.assessment?.id)
        assertEquals("Written assessment", mapped.assessment?.title)
        assertEquals("Chapters 1–2", schoolText(mapped.assessment?.summary))
        assertEquals(2, mapped.homework.size)
        assertEquals("2026-11-25", mapped.homework.first().dueDate)
        assertEquals("Page 10", schoolText(mapped.homework.first().summary))
        assertEquals("Assessment · Homework (2)", lessonWorkSummary(mapped.assessment, mapped.homework))
    }
    @Test fun workDetailsAlsoSurviveAssignmentsAndGenericEvents() {
        val assessment = LessonAssessment(1, "Assessment")
        val work = listOf(LessonHomework(title = "Exercises"))
        val assignment = map(lesson.copy(type = "event", typeLabel = "ZAPOSLITEV", evaluation = assessment, homework = work))
        assertEquals(assessment, lessons(assignment).single().assessment)
        assertEquals(work, lessons(assignment).single().homework)
        val event = map(lesson.copy(type = "event", typeLabel = "DOGODEK", evaluation = assessment, homework = work))
        assertEquals(work, event.events.single().homework)
    }
    @Test fun missingOptionalWorkFieldsKeepExistingLessonsUsable() {
        val mapped = lessons(map(lesson)).single()
        assertNull(mapped.assessment); assertTrue(mapped.homework.isEmpty())
        assertEquals("", lessonWorkSummary(mapped.assessment, mapped.homework))
    }
}
