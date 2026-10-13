package com.ravijol1.ibuprofen.data

import com.google.gson.annotations.SerializedName

// Requests

data class LoginRequest(
    val username: String,
    val password: String,
    @SerializedName("supported_user_types")
    val supportedUserTypes: List<String> = listOf("parent", "child", "parent_of_pre_enrolled")
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

// Children (m/v3/children)

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
    @SerializedName("subscription_status") val subscriptionStatus: String?,
    @SerializedName("plus_enabled") val plusEnabled: Boolean = false,
    @SerializedName("school_year") val schoolYear: String? = null,
    val paywall: ChildPaywall? = null
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

data class ChildPaywall(val status: String?, val content: String?)

data class ChildProfile(
    val uuid: String,
    val studentId: Int,
    val classId: Int?,
    val displayName: String?,
    val className: String?,
    val schoolName: String?,
    val subscriptionStatus: String?,
    val schoolId: Int? = null,
    val schoolYear: String? = null,
    val plusEnabled: Boolean = false,
    val paywallMessage: String? = null
)

fun ChildItem.toProfile(): ChildProfile {
    // Parse the five numeric components from the end; "child_uuid" is part of the prefix.
    val ids = Regex("_(\\d+)_(\\d+)_(\\d+)_(\\d+)_(\\d+)$").find(uuid)?.groupValues
        ?: error("The server returned an unsupported student profile ID")
    return ChildProfile(uuid, ids[5].toInt(), ids[4].toInt(), displayName, className, schoolName,
        subscriptionStatus ?: paywall?.status, ids[3].toInt(), schoolYear ?: ids[2], plusEnabled, paywall?.content)
}

