package com.ravijol1.ibuprofen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ravijol1.ibuprofen.data.*
import com.ravijol1.ibuprofen.ui.theme.IbuprofenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class WeeklyScheduleGridTest {
    @get:Rule val compose = createComposeRule()
    private val monday = LocalDate.of(2026, 11, 23)
    private val schedule = PersonalSchedule(TimetableWeek(1, monday, monday.plusDays(6), listOf(
        TimetableDay(monday, listOf(PeriodCell.WithLessons(1, LocalTime.of(8, 0)..LocalTime.of(8, 45), listOf(
            Lesson("SYN", "Synthetic subject", "Example", "A1", isCancelled = false, teacherFullName = "Synthetic Teacher", notes = listOf("Example note")))))))), emptyList())

    @Test fun compactLessonOpensFullDetailsOnNarrowScreen() {
        compose.setContent { IbuprofenTheme {
            Box(Modifier.width(320.dp).height(560.dp)) { WeeklyScheduleGrid(schedule, Modifier.fillMaxSize()) {} }
        } }
        compose.onNodeWithContentDescription("Synthetic subject", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("Synthetic subject").assertIsDisplayed()
        compose.onNodeWithText("Teacher: Synthetic Teacher").assertIsDisplayed()
        compose.onNodeWithText("Room: A1").assertIsDisplayed()
        compose.onNodeWithText("Example note").assertExists()
    }

    @Test fun horizontalGridSwipeDoesNotNavigateParentPagerWithLargeFonts() {
        var selectedPage = -1
        compose.setContent { IbuprofenTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                val pager = rememberPagerState(pageCount = { 2 })
                selectedPage = pager.currentPage
                Box(Modifier.width(320.dp).height(560.dp)) {
                    HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                        if (page == 0) WeeklyScheduleGrid(schedule, Modifier.fillMaxSize().testTag("grid")) {}
                        else Text("Other tab")
                    }
                }
            }
        } }
        compose.onNodeWithTag("grid").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, selectedPage) }
    }
    @Test fun currentTimeMarkerAppearsOnlyOnTheDisplayedWeek() {
        val now = mutableStateOf(java.time.ZonedDateTime.of(monday, LocalTime.of(8, 30), SCHOOL_ZONE))
        compose.setContent { IbuprofenTheme {
            Box(Modifier.width(320.dp).height(560.dp)) {
                WeeklyScheduleGrid(schedule, Modifier.fillMaxSize(), currentTime = now.value) {}
            }
        } }
        compose.onNodeWithContentDescription("Current time 08:30").assertIsDisplayed()
        compose.runOnIdle { now.value = now.value.plusWeeks(1) }
        compose.onNodeWithContentDescription("Current time 08:30").assertDoesNotExist()
    }
    @Test fun publicLessonDetailsIncludeTheSourceClass() {
        val day = schedule.week.days.first()
        val cell = day.lessonsByPeriod.first() as PeriodCell.WithLessons
        val public = schedule.copy(week = schedule.week.copy(days = listOf(day.copy(lessonsByPeriod = listOf(
            cell.copy(lessons = cell.lessons.map { it.copy(sourceClassLabel = "TEST-A") }))))))
        compose.setContent { IbuprofenTheme {
            Box(Modifier.width(320.dp).height(560.dp)) {
                WeeklyScheduleGrid(public, Modifier.fillMaxSize(), showClasses = true) {}
            }
        } }
        compose.onNodeWithText("TEST-A").assertIsDisplayed()
        compose.onNodeWithContentDescription("Synthetic subject", substring = true).performClick()
        compose.onNodeWithText("Class: TEST-A").assertIsDisplayed()
    }

    private fun cellWithWork(): PeriodCell.WithLessons {
        val cell = schedule.week.days.first().lessonsByPeriod.first() as PeriodCell.WithLessons
        return cell.copy(lessons = cell.lessons.map { it.copy(
            assessment = LessonAssessment(42, "Written assessment", "Chapters 1–2", "Written"),
            homework = listOf(LessonHomework("2026-11-25", "Practice exercises", "Page 10"))) })
    }

    @Test fun dailyLessonOpensAssessmentAndHomeworkDetails() {
        compose.setContent { IbuprofenTheme { LessonPeriodCard(cellWithWork()) } }
        compose.onNodeWithText("Assessment · Homework ⓘ").assertExists()
        compose.onNodeWithText("Synthetic subject").performClick()
        compose.onNodeWithText("Written assessment").assertExists()
        compose.onNodeWithText("Practice exercises").assertExists()
        compose.onNodeWithText("Due ", substring = true).assertExists()
    }

    @Test fun weeklyLessonOpensAssessmentAndHomeworkDetails() {
        val work = schedule.copy(week = schedule.week.copy(days = listOf(TimetableDay(monday, listOf(cellWithWork())))))
        compose.setContent { IbuprofenTheme {
            Box(Modifier.width(320.dp).height(560.dp)) { WeeklyScheduleGrid(work, Modifier.fillMaxSize()) {} }
        } }
        compose.onNodeWithContentDescription("Synthetic subject", substring = true).performClick()
        compose.onNodeWithText("Written assessment").assertExists()
        compose.onNodeWithText("Practice exercises").assertExists()
    }

    @Test fun groupPickerSavesALessonFromAMultiSubjectEntry() {
        val preferences = PersonalGroups()
        val entry = TimetableGroupEntry("entry:test@2026-11-23", "MAT / LANG", listOf("Mon · 08:00–08:45"), listOf(
            TimetableGroupOption("a", "Mathematics · Skupina 1 · Example Teacher A"),
            TimetableGroupOption("b", "Language · Skupina 2 · Example Teacher B")))
        var saved: PersonalGroups? = null
        compose.setContent { IbuprofenTheme { PersonalGroupsSheet(preferences, monday, listOf(entry), { saved = it }, {}) } }
        compose.onNodeWithText("Language · Skupina 2 · Example Teacher B").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(setOf("b"), saved?.entryChoices?.get(entry.key)) }
    }

}
