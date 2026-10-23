package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class SchoolTimetablesTest {
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun api(cancel: Boolean = false) = object : EAsistentApi {
        override suspend fun getSchoolPage(schoolKey: String): Response<String> = Response.success(fixture("school.html"))
        override suspend fun getTimetable(schoolKey: String, classId: Int, professorId: Int, studentId: Int,
            classroomId: Int, weekId: Int, extracurriculars: Int): Response<String> {
            assertEquals("school-key", schoolKey)
            if (cancel) throw CancellationException("Cancelled")
            return if (classId == 101) Response.success(fixture("timetable-print.html"))
                else Response.error(503, "Service unavailable".toResponseBody())
        }

    }

    @Test fun reportsFailedClassesRatherThanSilentlyShowingAnIncompleteSchedule() = runBlocking {
        val repo = TimetableRepository(api())
        val school = repo.loadSchoolMeta("school-key", useProxy = false)
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = repo.loadSchoolTimetables(school.schoolKey,
            listOf(ClassInfo(101, "TEST-A"), ClassInfo(102, "TEST-B")), 7) { done, total -> progress.add(done to total) }
        assertEquals(1, result.weeks.size)
        assertEquals(listOf(ClassInfo(102, "TEST-B")), result.failedClasses)
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        val lessons = result.weeks.single().days.flatMap { it.lessonsByPeriod }
            .filterIsInstance<PeriodCell.WithLessons>().flatMap { it.lessons }
        assertTrue(lessons.isNotEmpty())
        assertTrue(lessons.all { it.sourceClassLabel == "TEST-A" })
    }

    @Test fun stringSchoolKeysWorkWithoutMetadataAndKeepCachesSeparate() = runBlocking {
        val calls = mutableListOf<String>()
        val upstream = object : EAsistentApi {
            override suspend fun getSchoolPage(schoolKey: String): Response<String> = error("Metadata is not needed for a known key")
            override suspend fun getTimetable(schoolKey: String, classId: Int, professorId: Int, studentId: Int,
                classroomId: Int, weekId: Int, extracurriculars: Int): Response<String> {
                calls.add(schoolKey)
                return Response.success(fixture("timetable-print.html"))
            }
        }
        val repo = TimetableRepository(upstream)
        repo.loadTimetableWeek("school-hash-a1b2", 101, 7, useProxy = false)
        repo.loadTimetableWeek("school-hash-c3d4", 101, 7, useProxy = false)
        repo.loadTimetableWeek("school-hash-a1b2", 101, 7, useProxy = false)
        assertEquals(listOf("school-hash-a1b2", "school-hash-c3d4"), calls)
    }

    @Test(expected = CancellationException::class)
    fun cancelledLoadingDoesNotBecomeAMissingClass() = runBlocking {
        val repo = TimetableRepository(api(cancel = true))
        val school = repo.loadSchoolMeta("school-key", useProxy = false)
        repo.loadSchoolTimetables(school.schoolKey, listOf(ClassInfo(101, "TEST-A")), 7)
        Unit
    }
}
