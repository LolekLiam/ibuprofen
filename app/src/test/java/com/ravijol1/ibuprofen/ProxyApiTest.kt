package com.ravijol1.ibuprofen

import com.google.gson.Gson
import com.ravijol1.ibuprofen.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ProxyApiTest {
    @Test fun publicAndAdminRequestsUseV1RoutesAndSeparateBearerTokens() = runBlocking {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val r = chain.request(); synchronized(seen) { seen.add(r) }
            val data = when (r.url.encodedPath) {
                "/v1/health" -> "{\"status\":\"ok\",\"timestamp\":1}"
                "/v1/schools/test-school/timetable" -> "{\"classId\":42,\"weekStart\":\"2026-10-12\",\"weekEnd\":\"2026-10-18\",\"days\":[]}"
                "/v1/admin/me" -> "{\"username\":\"owner\",\"role\":\"admin\"}"
                "/v1/admin/schools/test-school/sync-jobs" -> "{\"id\":\"job\",\"school_key\":\"test-school\",\"scope\":\"week\",\"week\":7,\"status\":\"queued\",\"total\":0,\"completed\":0,\"failed\":0,\"created_at\":1,\"updated_at\":1}"
                else -> error("Unexpected route")
            }
            Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("{\"data\":$data,\"meta\":{}}".toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client).addConverterFactory(GsonConverterFactory.create()).build()
        val public = retrofit.create(IbuprofenProxyApi::class.java)
        assertEquals("ok", public.checkHealth().body()!!.data.status)
        assertEquals(42, public.getClassTimetable("test-school",42,7).body()!!.data.classId)
        val admin = retrofit.create(ProxyAdminApi::class.java)
        assertEquals("admin", admin.me("Bearer admin-test-token").body()!!.data.role)
        assertEquals("queued", admin.sync("Bearer admin-test-token","test-school",SyncJobRequest("week",7)).body()!!.data.status)
        assertNull(seen.first().header("Authorization"))
        assertNull(seen[1].header("Authorization"))
        assertEquals("42", seen[1].url.queryParameter("class_id"))
        assertEquals("7", seen[1].url.queryParameter("week"))
        assertEquals("Bearer admin-test-token", seen[2].header("Authorization"))
        assertEquals("POST", seen[3].method)
        val buffer = Buffer(); seen[3].body!!.writeTo(buffer)
        assertEquals("week", Gson().fromJson(buffer.readUtf8(),SyncJobRequest::class.java).scope)
        Unit
    }

    @Test fun proxyLessonsNormalizeMissingAndNullNotesBeforeRendering() {
        // Matches the server's lesson shape, which omits notes and isSubstitution.
        val json = """{"classId":42,"weekStart":"2026-10-12","weekEnd":"2026-10-18",
            "days":[{"date":"2026-10-12","lessonsByPeriod":[{"periodNumber":1,
            "timeRange":{"start":"08:00","end":"08:45"},"lessons":[
            {"subjectCode":"MAT","isCancelled":false},
            {"subjectCode":"ENG","notes":null},
            {"subjectCode":"BIO","notes":["Bring a workbook",null,"Bring a workbook"],"isSubstitution":true}
            ]}]}]}"""
        val week = Gson().fromJson(json, TimetableWeekProxy::class.java).toDomain(42)
        val lessons = (week.days.single().lessonsByPeriod.single() as PeriodCell.WithLessons).lessons
        assertEquals(emptyList<String>(), lessons[0].notes.distinct())
        assertEquals(emptyList<String>(), lessons[1].notes.distinct())
        assertFalse(lessons[0].isSubstitution)
        assertEquals(listOf("Bring a workbook"), lessons[2].notes.distinct())
        assertTrue(lessons[2].isSubstitution)
    }

    @Test fun adminUrlAcceptsLanHttpAndRejectsCredentialBearingUrls() {
        assertEquals("https://example.com/",NetworkProvider.adminServerUrl("example.com"))
        assertEquals("http://10.0.2.2:8080/",NetworkProvider.adminServerUrl("http://10.0.2.2:8080"))
        assertEquals("http://192.168.1.27:8080/", NetworkProvider.adminServerUrl("http://192.168.1.27:8080"))
        listOf("ftp://example.com", "https://user:password@example.com", "https://example.com?token=secret", "https://example.com#fragment").forEach { url ->
            try { NetworkProvider.adminServerUrl(url); fail("Unsafe URL accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
