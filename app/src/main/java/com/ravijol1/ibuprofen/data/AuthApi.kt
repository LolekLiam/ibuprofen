package com.ravijol1.ibuprofen.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface AuthApi {
    @POST("m/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("m/refresh_token")
    suspend fun refresh(@Body request: RefreshRequest): Response<RefreshResponse>

    @DELETE("m/logout")
    suspend fun logout(
        @Query("device_id") deviceId: String = "child_device",
        @Header("Authorization") authHeader: String? = null
    ): Response<Unit>

    @GET("m/v2/children")
    suspend fun getChildren(
        @Header("Authorization") authHeader: String
    ): Response<ChildrenResponse>
}
