package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate

class GradesTest {
    private val gson = Gson()
    private fun fixture(name: String) = javaClass.getResource("/mobile/$name")!!.readText()
    private class Store : AuthSessionStore {
        override var accessToken: String? = "synthetic-access"
        override var accessExpiration: String? = "2099-01-01T00:00:00Z"
        override var refreshToken: String? = "synthetic-refresh"
        override var userName: String? = "Example"
        override var userId: String? = "15"
        override var schoolId: Int? = 777
        override val deviceId = "synthetic-device"
        override fun clear() { accessToken = null; refreshToken = null }
    }
    private fun repository(seen: MutableList<Request>, reply: (Request) -> Pair<Int, String>): AuthRepository {
        val client = OkHttpClient.Builder().addInterceptor(MobileApiInterceptor()).addInterceptor { chain ->
            val r = chain.request(); synchronized(seen) { seen.add(r) }
            val (status, body) = reply(r)
            Response.Builder().request(r).code(status).protocol(Protocol.HTTP_1_1).message("Synthetic fixture").body(body.toResponseBody()).build()
        }.build()
        return AuthRepository(Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(AuthApi::class.java), Store())
    }
    private fun freeReply(request: Request): Pair<Int, String> = 200 to when (request.url.encodedPath) {
        "/m/me/notifications" -> fixture("notifications-first.json")
        "/m/me/notifications/899" -> fixture("notifications-older.json")
        "/m/overview/basic" -> fixture("overview.json")
        "/m/grades/concealed" -> fixture("concealed.json")
        else -> error("Unexpected route ${request.url.encodedPath}")
    }

    @Test fun plusUsesFullGradebookWithCorrectQueryAndProfileHeaders() = runBlocking {
        val seen = mutableListOf<Request>()
        val repo = repository(seen) { 200 to fixture("grades-plus.json") }
        val result = repo.getMobileGrades("example-child", true, "2026/27")
        assertEquals(GradeSource.Plus, result.source)
        assertEquals(3, result.subjects.size)
        val subject = result.subjects.single { it.id == 501L }
        assertEquals("5", subject.finalGrade.gradeValue())
        assertEquals("4.50", subject.averageGrade)
        assertEquals("4", subject.semesters!![0].finalGrade.gradeValue())
        assertEquals("5", subject.semesters[1].finalGrade.gradeValue())
        assertEquals(listOf(1001L), subject.semesters[0].grades!![1].overridesIds)
        assertEquals("NPS", subject.semesters[1].grades!!.first().value)
        assertEquals(1, seen.size)
        assertEquals("/m/grades", seen.single().url.encodedPath)
        assertEquals(listOf("all_subjects", "color"), seen.single().url.queryParameterValues("with[]"))
        assertEquals("example-child", seen.single().header("X-Child-Id"))
        assertEquals("Bearer synthetic-access", seen.single().header("Authorization"))
        assertEquals("true", seen.single().url.queryParameter("wl"))
    }

    @Test fun freeReadsOlderPagesDeduplicatesGradesAndExcludesPreviousSchoolYear() = runBlocking {
        val seen = mutableListOf<Request>(); val progress = mutableListOf<Int>()
        val result = repository(seen, ::freeReply).getMobileGrades("example-child", false, "2026/27") { progress.add(it) }
        assertEquals(GradeSource.Notifications, result.source)
        assertTrue(result.historyComplete)
        assertEquals(listOf(1, 2), progress)
        assertEquals(2, result.subjects.size)
        val syn = result.subjects.single { it.shortName == "SYN" }.semesters!!.single().grades!!
        assertEquals(setOf(1001L, 1003L), syn.map { it.id }.toSet())
        assertEquals(2, syn.size)
        assertTrue(syn.any { it.value == "NPS" })
        assertNull(result.subjects.first().averageGrade)
        assertEquals("4", result.subjects.single { it.shortName == "ART" }.semesters!!.single().grades!!.single().value)
        assertFalse(seen.any { it.url.encodedPath == "/m/grades" })
        assertFalse(seen.any { it.url.encodedPath == "/m/overview/basic" || it.url.encodedPath == "/m/grades/concealed" })
        val requests = seen.filter { it.url.encodedPath.contains("notifications") }
        assertTrue(requests.all { it.header("X-Child-Id") == "example-child" && it.url.queryParameter("exclude") == "komunikacija" })
    }

    @Test fun plusPermissionDenialFallsBackWithoutRefreshingValidSession() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = repository(seen) { if (it.url.encodedPath == "/m/grades") 403 to "{}" else freeReply(it) }
            .getMobileGrades("example-child", true, "2026/27")
        assertEquals(GradeSource.Notifications, result.source)
        assertNotNull(result.notice)
        assertTrue(result.subjects.isNotEmpty())
        assertFalse(seen.any { it.url.encodedPath == "/m/refresh_token" })
    }

    @Test fun repeatedPaginationCursorStopsAndMarksHistoryIncomplete() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = repository(seen) { if (it.url.encodedPath.startsWith("/m/me/notifications"))
            200 to fixture("notifications-first.json") else freeReply(it) }.getMobileGrades("example-child", false, "2026/27")
        assertFalse(result.historyComplete)
        assertEquals(2, seen.count { it.url.encodedPath.startsWith("/m/me/notifications") })
        assertEquals(1, result.subjects.size)
    }

    @Test fun interruptedHistoryRetainsGradesWithoutRequestingAccountOverview() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = repository(seen) { if (it.url.encodedPath == "/m/me/notifications") freeReply(it) else 503 to "{}" }
            .getMobileGrades("example-child", false, "2026/27")
        assertFalse(result.historyComplete)
        assertEquals(1, result.subjects.size)
        assertFalse(seen.any { it.url.encodedPath == "/m/overview/basic" || it.url.encodedPath == "/m/grades/concealed" })
    }

    @Test fun gradeRequestsRefreshOn401AndRetryTheSameProfile() = runBlocking {
        val seen = mutableListOf<Request>()
        val result = repository(seen) { r -> when {
            r.url.encodedPath == "/m/refresh_token" -> 200 to fixture("login.json")
            r.header("Authorization") == "Bearer synthetic-access" -> 401 to "{}"
            else -> 200 to fixture("grades-plus.json")
        } }.getMobileGrades("example-child", true, "2026/27")
        assertEquals(GradeSource.Plus, result.source)
        assertEquals(2, seen.count { it.url.encodedPath == "/m/grades" })
        assertTrue(seen.filter { it.url.encodedPath == "/m/grades" }.all { it.header("X-Child-Id") == "example-child" })
    }

    @Test fun failedNotificationHistoryShowsErrorInsteadOfMisleadingOverview() = runBlocking {
        val seen = mutableListOf<Request>()
        try {
            repository(seen) { 503 to "{}" }.getMobileGrades("example-child", false, "2026/27")
            fail("Expected history failure")
        } catch (error: MobileApiException) { assertEquals(503, error.status) }
        assertEquals(listOf("/m/me/notifications"), seen.map { it.url.encodedPath })
    }

    @Test fun parserRejectsMalformedAnnouncementsAndOutOfYearMessages() {
        val start = LocalDate.of(2026, 9, 1)
        val messages = listOf(
            GradeNotification(1,"future_evaluation","Upcoming assessment","5 - SYN, Fake", "2026-10-10"),
            GradeNotification(2,"ocena","Nova ocena","Malformed message", "2026-10-10"),
            GradeNotification(3,"ocena","Nova ocena","5 - SYN, Written", "2025-10-10"),
            GradeNotification(4,"ocena","Nova ocena","5 - SYN, Written", "2027-09-01"),
            GradeNotification(5,"ocena","Nova ocena","5 - SYN, Written", "not a date"))
        assertTrue(NotificationGradeParser.parse(messages,start).isEmpty())
        assertEquals(LocalDate.of(2025,9,1), gradeSchoolYearStart(null,LocalDate.of(2026,8,31)))
        assertEquals(start, gradeSchoolYearStart("2026/27",LocalDate.of(2027,1,1)))
    }
}
