package com.ravijol1.ibuprofen.data

import retrofit2.Response
import retrofit2.http.*

data class ApiEnvelope<T>(val data: T, val meta: ProxyCoverage? = null)
data class ProxyCoverage(val complete: Boolean = false, val missing_class_ids: List<Int> = emptyList(), val classes_total: Int = 0)

interface IbuprofenProxyApi {
    @GET("v1/health") suspend fun checkHealth(): Response<ApiEnvelope<HealthStatus>>
    @GET("v1/schools/{key}") suspend fun getSchoolMeta(@Path("key") key: String): Response<ApiEnvelope<SchoolMeta>>
    @GET("v1/schools/{key}/timetable") suspend fun getClassTimetable(@Path("key") key: String,
        @Query("class_id") classId: Int, @Query("week") week: Int): Response<ApiEnvelope<TimetableWeekProxy>>
    @GET("v1/schools/{key}/timetables") suspend fun getSchoolTimetables(@Path("key") key: String,
        @Query("week") week: Int): Response<ApiEnvelope<List<TimetableWeekProxy>>>
}

data class AdminUser(val username: String, val role: String)
data class AdminLogin(val username: String, val password: String)
data class AdminSession(val token: String, val expires_at: Long, val user: AdminUser)
data class AdminStats(val schools: Int, val cache: Int, val lessons: Int, val jobs: Int, val active_jobs: Int)
data class AdminSchool(val key: String, val name: String?)
data class SyncJob(val id: String, val school_key: String, val scope: String, val week: Int?, val status: String,
    val total: Int, val completed: Int, val failed: Int, val created_at: Long, val updated_at: Long, val error: String?)
data class SyncJobRequest(val scope: String = "week", val week: Int? = null)
data class AddSchoolRequest(val key: String)

interface ProxyAdminApi {
    @POST("v1/admin/sessions") suspend fun login(@Body request: AdminLogin): Response<ApiEnvelope<AdminSession>>
    @GET("v1/admin/me") suspend fun me(@Header("Authorization") bearer: String): Response<ApiEnvelope<AdminUser>>
    @DELETE("v1/admin/sessions/current") suspend fun logout(@Header("Authorization") bearer: String): Response<ApiEnvelope<Map<String, Boolean>>>
    @GET("v1/admin/stats") suspend fun stats(@Header("Authorization") bearer: String): Response<ApiEnvelope<AdminStats>>
    @GET("v1/admin/schools") suspend fun schools(@Header("Authorization") bearer: String): Response<ApiEnvelope<List<AdminSchool>>>
    @POST("v1/admin/schools") suspend fun addSchool(@Header("Authorization") bearer: String,
        @Body request: AddSchoolRequest): Response<ApiEnvelope<SchoolMeta>>
    @GET("v1/admin/jobs") suspend fun jobs(@Header("Authorization") bearer: String): Response<ApiEnvelope<List<SyncJob>>>
    @POST("v1/admin/schools/{key}/sync-jobs") suspend fun sync(@Header("Authorization") bearer: String,
        @Path("key") key: String, @Body request: SyncJobRequest): Response<ApiEnvelope<SyncJob>>
}
