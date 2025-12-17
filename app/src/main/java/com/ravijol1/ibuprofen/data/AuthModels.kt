package com.ravijol1.ibuprofen.data

import com.google.gson.annotations.SerializedName

// Requests

data class LoginRequest(
    val username: String,
    val password: String,
    @SerializedName("supported_user_types")
    val supportedUserTypes: List<String> = listOf("parent", "child")
)

data class RefreshRequest(
    @SerializedName("refresh_token") val refreshToken: String
)

// Responses

data class LoginResponse(
    @SerializedName("access_token") val accessToken: AccessToken,
    @SerializedName("refresh_token") val refreshToken: String,
    val user: AuthUser?,
    val redirect: String?
)

data class RefreshResponse(
    @SerializedName("access_token") val accessToken: AccessToken,
    @SerializedName("refresh_token") val refreshToken: String,
    val redirect: String?
)

data class AccessToken(
    val token: String,
    @SerializedName("expiration_date") val expirationDate: String
)

data class AuthUser(
    val id: Long,
    val language: String?,
    val username: String?,
    val name: String?,
    val type: String?,
    @SerializedName("freshPassword") val freshPassword: String?,
    val gender: String?
)

// Children (m/v2/children)

data class ChildrenResponse(
    val items: List<ChildItem>
)

data class ChildItem(
    val uuid: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("class_name") val className: String?,
    @SerializedName("school_name") val schoolName: String?,
    val gender: String?,
    val age: Int?,
    @SerializedName("short_name") val shortName: String?,
    @SerializedName("subscription_status") val subscriptionStatus: String?
)

// Parsed JWT payload subset (no verification; informational)

data class JwtPayload(
    @SerializedName("consumerKey") val consumerKey: String?,
    @SerializedName("userId") val userId: String?,
    @SerializedName("userType") val userType: String?,
    @SerializedName("schoolId") val schoolId: String?,
    @SerializedName("sessionId") val sessionId: String?,
    @SerializedName("issuedAt") val issuedAt: String?,
    @SerializedName("appName") val appName: String?,
    @SerializedName("exp") val exp: String?,
    @SerializedName("ttl") val ttl: Long?
)

// App-level simplified child profile used by UI

data class ChildProfile(
    val uuid: String,
    val studentId: Int,
    val classId: Int?,
    val displayName: String?,
    val className: String?,
    val schoolName: String?,
    val subscriptionStatus: String?
)

// --- Grades (paid) ---

data class GradesResponse(
    val items: List<SubjectGrades>
)

data class SubjectGrades(
    val name: String,
    @SerializedName("short_name") val shortName: String?,
    val id: Long,
    @SerializedName("grade_type") val gradeType: String?,
    @SerializedName("is_excused") val isExcused: Boolean?,
    @SerializedName("final_grade") val finalGrade: String?,
    @SerializedName("average_grade") val averageGrade: String?,
    @SerializedName("grade_rank") val gradeRank: String?,
    val semesters: List<SemesterGrades>
)

data class SemesterGrades(
    val id: Int,
    @SerializedName("final_grade") val finalGrade: String?,
    val grades: List<GradeItem>
)

data class GradeItem(
    @SerializedName("type_name") val typeName: String?,
    val comment: String?,
    val id: Long,
    val type: String?,
    @SerializedName("overrides_ids") val overridesIds: List<Long>?,
    val value: String?,
    val color: String?,
    val date: String?, // yyyy-MM-dd
    @SerializedName("inserted_at") val insertedAt: String?
)


// --- Notifications (free grades source) ---

data class NotificationsResponse(
    val items: List<NotificationItem>
)

data class NotificationItem(
    val title: String?,
    val message: String?,
    @SerializedName("meta_data") val metaData: NotificationMeta?,
    val id: Long,
    @SerializedName("created_at") val createdAt: String?, // "yyyy-MM-dd HH:mm:ss"
    val seen: Boolean?,
    val type: String?
)

data class NotificationMeta(
    val channelId: String?,
    val messageId: Long?,
    val userId: Long?,
    val channelType: String?,
    val gradeId: Long?,
    val subjectId: Long?,
    val date: String?,
    @SerializedName("schedule_id") val scheduleId: Long?,
    @SerializedName("event_slug") val eventSlug: String?
)
