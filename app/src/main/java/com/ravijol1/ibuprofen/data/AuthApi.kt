package com.ravijol1.ibuprofen.data

import retrofit2.Response
import retrofit2.http.*

interface AuthApi {
    @POST("m/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("m/refresh_token")
    suspend fun refresh(@Body request: RefreshRequest): Response<RefreshResponse>

    @DELETE("m/logout")
    suspend fun logout(@Query("device_id") deviceId: String,
        @Header("Authorization") authHeader: String?): Response<Unit>

    @GET("m/v3/children")
    suspend fun getChildren(@Header("Authorization") authHeader: String): Response<ChildrenResponse>

    @GET("m/school/weeks")
    suspend fun getSchoolWeeks(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String): Response<SchoolWeeksResponse>

    @GET("m/timetable/events")
    suspend fun getTimetableEvents(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String, @Query("from") from: String,
        @Query("to") to: String): Response<MobileTimetableResponse>

    @GET("m/overview/basic")
    suspend fun getOverview(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String): Response<MobileOverview>

    @GET("m/grades/concealed")
    suspend fun getConcealedGrades(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String): Response<ConcealedGrades>

    @GET("m/grades")
    suspend fun getGrades(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String,
        @Query("with[]") include: List<String> = listOf("all_subjects", "color")): Response<GradesResponse>

    @GET("m/me/notifications")
    suspend fun getNotifications(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String,
        @Query("exclude") exclude: String = "komunikacija"): Response<NotificationsResponse>

    @GET("m/me/notifications/{lastId}")
    suspend fun getOlderNotifications(@Header("Authorization") authHeader: String,
        @Header("X-Child-Id") childUuid: String, @Path("lastId") lastId: Long,
        @Query("exclude") exclude: String = "komunikacija"): Response<NotificationsResponse>
}
