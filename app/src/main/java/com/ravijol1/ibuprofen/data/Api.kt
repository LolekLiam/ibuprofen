package com.ravijol1.ibuprofen.data

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path

interface EAsistentApi {
    @GET("urniki/{schoolKey}/")
    suspend fun getSchoolPage(@Path("schoolKey") schoolKey: String): Response<String>

    @GET("urniki/izpis/{schoolId}/{classId}/{professorId}/{classroomId}/{extracurriculars}/{weekId}/{studentId}")
    suspend fun getTimetable(
        @Path("schoolId") schoolId: String,
        @Path("classId") classId: Int,
        @Path("professorId") professorId: Int = 0,
        @Path("studentId") studentId: Int = 0,
        @Path("classroomId") classroomId: Int = 0,
        @Path("weekId") weekId: Int,
        @Path("extracurriculars") extracurriculars: Int = 0
    ): Response<String>

}
