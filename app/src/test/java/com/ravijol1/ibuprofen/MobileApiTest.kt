package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue

class MobileApiTest {
    private val gson = Gson()
    private fun fixture(name: String) = javaClass.getResource("/mobile/$name")!!.readText()
    private class MemoryStore : AuthSessionStore {
        override var accessToken: String? = null
        override var accessExpiration: String? = null
        override var refreshToken: String? = null
        override var userName: String? = null
        override var userId: String? = null
        override var schoolId: Int? = null
        override val deviceId = "test-device"
        override fun clear() { accessToken = null; accessExpiration = null; refreshToken = null; userName = null; userId = null; schoolId = null }
    }
    private fun api(requests: ConcurrentLinkedQueue<Request>, status: (Request) -> Int = { 200 }): AuthApi {
        val client = OkHttpClient.Builder().addInterceptor(MobileApiInterceptor()).addInterceptor { chain ->
            val request = chain.request(); requests.add(request)
            val code = status(request)
            val json = when (request.url.encodedPath) {
                "/m/login" -> fixture("login.json")
                "/m/v3/children" -> fixture("children.json")
                "/m/school/weeks" -> fixture("weeks.json")
                "/m/timetable/events" -> fixture("events.json")
                "/m/overview/basic" -> fixture("overview.json")
                "/m/grades/concealed" -> fixture("concealed.json")
                "/m/refresh_token" -> {
                    val login = gson.fromJson(fixture("login.json"), LoginResponse::class.java)
                    gson.toJson(RefreshResponse(login.accessToken.copy(token = "rotated-access"), "rotated-refresh", null))
                }
                "/m/logout" -> "{}"
                else -> error("Unexpected request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
                .body((if (code >= 400) "{\"message\":\"Example server error\"}" else json).toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(AuthApi::class.java)
    }

    @Test fun syntheticLoginChildrenScheduleAndGradesMatchProtocol() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>(); val store = MemoryStore(); val repo = AuthRepository(api(requests), store)
        val session = repo.login("example", "test-password")
        assertEquals(777, session.schoolId)
        assertEquals("15", session.userId)
        val login = requests.first()
        assertEquals("moj_asistent_parent", login.header("X-App-Name"))
        assertEquals("100000", login.header("X-Client-Version"))
        assertEquals("true", login.url.queryParameter("wl"))
        assertNull(login.header("Authorization"))
        val buffer = Buffer(); login.body!!.writeTo(buffer)
        val body = JsonParser.parseString(buffer.readUtf8()).asJsonObject
        assertEquals(listOf("parent", "child", "parent_of_pre_enrolled"), body["supported_user_types"].asJsonArray.map { it.asString })
        val child = repo.getChildrenProfiles().single()
        assertEquals(99, child.studentId); assertEquals(88, child.classId); assertEquals(777, child.schoolId)
        assertEquals("2026/27", child.schoolYear)
        val weeks = repo.getSchoolWeeks(child.uuid)
        assertEquals(53, weeks.size)
        val current = weeks.single { it.current }
        val schedule = repo.getPersonalSchedule(child, LocalDate.parse(current.from), LocalDate.parse(current.to))
        assertEquals(5, schedule.week.days.size)
        assertEquals(2, schedule.events.size)
        val grades = repo.getMobileGrades(child.uuid)
        assertEquals(1, grades.recent.size)
        assertEquals(14, grades.summary!!.oneToThree!!.subjects.size)
        assertFalse(requests.any { it.url.encodedPath == "/m/refresh_token" })
        val timetable = requests.first { it.url.encodedPath == "/m/timetable/events" }
        assertEquals(child.uuid, timetable.header("X-Child-Id"))
        assertEquals("2026-10-12", timetable.url.queryParameter("from"))
        assertEquals("2026-10-16", timetable.url.queryParameter("to"))
        assertEquals("Bearer ${store.accessToken}", timetable.header("Authorization"))
        assertTrue(requests.all { it.url.queryParameter("wl") == "true" })
        Unit
    }

    @Test fun unauthorizedRequestRefreshesOnceAndUsesRotatedTokens() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>(); val store = MemoryStore()
        val repo = AuthRepository(api(requests) { request ->
            if (request.url.encodedPath == "/m/v3/children" && request.header("Authorization") != "Bearer rotated-access") 401 else 200
        }, store)
        repo.login("example", "test-password")
        assertEquals(1, repo.getChildrenProfiles().size)
        assertEquals("rotated-refresh", store.refreshToken)
        assertEquals(1, requests.count { it.url.encodedPath == "/m/refresh_token" })
        assertEquals(2, requests.count { it.url.encodedPath == "/m/v3/children" })
    }

    @Test fun permissionErrorDoesNotEraseSessionOrTriggerRefresh() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>(); val store = MemoryStore()
        val repo = AuthRepository(api(requests) { if (it.url.encodedPath == "/m/v3/children") 403 else 200 }, store)
        repo.login("example", "test-password")
        try { repo.getChildrenProfiles(); fail("Expected permission failure") } catch (e: MobileApiException) { assertEquals(403, e.status) }
        assertNotNull(repo.currentSession())
        assertFalse(requests.any { it.url.encodedPath == "/m/refresh_token" })
    }

    @Test fun rejectedRefreshClearsSessionAndRequiresSignIn() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>(); val store = MemoryStore()
        val repo = AuthRepository(api(requests) { if (it.url.encodedPath == "/m/login") 200 else 401 }, store)
        repo.login("example", "test-password")
        try { repo.getChildrenProfiles(); fail("Expected expired session") } catch (_: SessionExpiredException) { }
        assertNull(repo.currentSession())
    }

    @Test fun logoutClearsLocalTokensAndUsesOwnDeviceId() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>(); val store = MemoryStore(); val repo = AuthRepository(api(requests), store)
        repo.login("example", "test-password"); repo.logout()
        assertNull(repo.currentSession())
        assertEquals("test-device", requests.last().url.queryParameter("device_id"))
    }

    @Test fun expirationSupportsTimezoneFormatAndSafetyWindow() {
        val now = Instant.parse("2026-10-13T12:00:00Z")
        assertFalse(TokenExpiry.needsRefresh("2026-10-13T14:02:00+0200", now))
        assertTrue(TokenExpiry.needsRefresh("2026-10-13T14:00:30+0200", now))
        assertTrue(TokenExpiry.needsRefresh("invalid", now))
    }
}
