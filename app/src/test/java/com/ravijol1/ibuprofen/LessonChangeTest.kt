package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LessonChangeTest {
    private val gson = Gson()
    private fun fixture(name: String) = javaClass.getResource("/mobile/$name")!!.readText()
    private val base = gson.fromJson(fixture("events.json"), MobileTimetableResponse::class.java)
    private val template = base.events.first { it.type == "school_hour" }
    private val child = ChildProfile("synthetic-child", 99, 88, "Example", "TEST-A", "Example School", null)
    private val date = LocalDate.parse(template.date)
    private fun mapped(vararg events: MobileEvent) = MobileTimetableMapper.map(base.copy(events = events.toList()), child, date, date)
    private fun lessons(schedule: PersonalSchedule) = schedule.week.days.flatMap { it.lessonsByPeriod }
        .filterIsInstance<PeriodCell.WithLessons>().flatMap { it.lessons }

    @Test fun substitutionPreservesOriginalSubjectDescriptionAndNotesForThePopup() {
        val event = template.copy(type = "event", isSubstitution = true, originalSubject = "Example Mathematics",
            description = "Complete the synthetic workbook.", typeLabel = "NADOMEŠČANJE")
        val result = mapped(event)
        val lesson = lessons(result).single()
        assertTrue(result.events.isEmpty())
        assertTrue(lesson.isSubstitution)
        assertEquals(LessonChangeKind.Substitution, lesson.changeKind)
        assertEquals("Example Mathematics", lesson.change!!.originalSubject)
        assertEquals("Complete the synthetic workbook.", lesson.change.description)
        assertEquals(listOf("Bring a workbook", "Example note"), lesson.notes)
    }

    @Test fun zaposlitevUsesLessonCardsEvenWhenTheSubstitutionFlagIsFalse() {
        val result = mapped(template.copy(type = "event", isSubstitution = false, typeLabel = "ZAPOSLITEV",
            scheduleIndices = emptyList()))
        assertTrue(result.events.isEmpty())
        val lesson = lessons(result).single()
        assertEquals(LessonChangeKind.Assignment, lesson.changeKind)
        assertFalse(lesson.isSubstitution)
        val cell = result.week.days.single().lessonsByPeriod.single() as PeriodCell.WithLessons
        assertEquals(2, cell.periodNumber) // Recovered from the event's actual time.
        assertTrue(personalScheduleTimeline(result.week.days.single(), result.events).single() is PersonalScheduleEntry.Lesson)
    }

    @Test fun secondaryTypeLabelsAndSlovenianSubstitutionLabelsAreRecognised() {
        assertEquals(LessonChangeKind.Assignment, mobileLessonChangeKind(template.copy(type = "event", typeLabel = null,
            typeLabels = listOf(MobileEventTypeLabel("Zaposlitev")))))
        assertEquals(LessonChangeKind.Substitution, mobileLessonChangeKind(template.copy(type = "event", isSubstitution = false,
            typeLabel = "NADOMEŠČANJE")))
        assertEquals(LessonChangeKind.Assignment, mobileLessonChangeKind(template.copy(isSubstitution = true, typeLabel = "ZAPOSLITEV")))
    }

    @Test fun ordinaryEventsStayEventsAndMissingOriginalDetailsAreNotInvented() {
        val schoolEvent = template.copy(slug = "ordinary-event", type = "event", typeLabel = "DOGODEK",
            title = "Example zaposlitev careers event", isSubstitution = false)
        val result = mapped(schoolEvent)
        assertEquals(1, result.events.size)
        assertTrue(lessons(result).isEmpty())
        val changed = lessons(mapped(template.copy(isSubstitution = true, description = null))).single()
        assertNull(changed.change!!.originalSubject)
        assertNull(changed.change.description)
    }

    @Test fun duplicateChangesAndOutOfRangeEventsAreNotRenderedTwice() {
        val event = template.copy(type = "event", typeLabel = "ZAPOSLITEV")
        val result = mapped(event, event, event.copy(slug = "outside", date = date.plusDays(1).toString()))
        assertEquals(1, lessons(result).size)
        assertTrue(result.events.isEmpty())
    }

    @Test fun proxyLessonsWithoutNewMetadataKeepSafeDefaults() {
        val proxy = gson.fromJson("""{"subjectCode":"SYN","isSubstitution":true}""", LessonProxy::class.java)
        val lesson = proxy.toDomain()
        assertEquals(LessonChangeKind.Substitution, lesson.changeKind)
        assertNull(lesson.change)
        assertTrue(lesson.notes.isEmpty())
    }

    @Test fun printableSubstitutionMarkerEnablesDetailsWithoutInventingAnOriginalSubject() {
        val html = javaClass.getResource("/timetable-print.html")!!.readText()
        val marked = html.replace("<span title=\"Synthetic Systems\">SYN</span>",
            "<span title=\"Synthetic Systems\">SYN</span><span class=\"wl-tag-substitution\" title=\"Example change note\">N</span>")
        val lesson = TimetableParser.parse(101, marked).days.first().lessonsByPeriod
            .filterIsInstance<PeriodCell.WithLessons>().first().lessons.single()
        assertEquals(LessonChangeKind.Substitution, lesson.changeKind)
        assertNull(lesson.change!!.originalSubject)
        assertEquals("Example change note", lesson.change.description)
    }
}
