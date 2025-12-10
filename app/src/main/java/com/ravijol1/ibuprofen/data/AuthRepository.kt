package com.ravijol1.ibuprofen.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val api: AuthApi,
    private val store: TokenStore
) {
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val userName: String?,
        val userId: String?,
        val schoolId: Int?
    )

    private fun extractDevMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val regex = Regex("\"developer_message\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(raw)?.groupValues?.getOrNull(1)
    }

    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        val res = api.login(LoginRequest(username, password))
        if (!res.isSuccessful) {
            val errBody = try { res.errorBody()?.string() } catch (_: Throwable) { null }
            val dev = extractDevMessage(errBody)
            error(dev ?: "Login failed: HTTP ${res.code()}")
        }
        val body = res.body() ?: error("Empty login response")
        val access = body.accessToken.token
        val accessExp = body.accessToken.expirationDate
        val refresh = body.refreshToken
        val payload = JwtUtils.parsePayload(access)
        val schoolId = payload?.schoolId?.toIntOrNull()
        val uid = payload?.userId
        val name = body.user?.name ?: username
        // persist
        store.accessToken = access
        store.accessExpiration = accessExp
        store.refreshToken = refresh
        store.userName = name
        store.userId = uid
        if (schoolId != null) store.schoolId = schoolId
        Session(access, refresh, name, uid, schoolId)
    }

    suspend fun refreshIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val refresh = store.refreshToken ?: return@withContext false
        val res = api.refresh(RefreshRequest(refresh))
        if (!res.isSuccessful) return@withContext false
        val body = res.body() ?: return@withContext false
        val access = body.accessToken.token
        val accessExp = body.accessToken.expirationDate
        val newRefresh = body.refreshToken
        val payload = JwtUtils.parsePayload(access)
        val schoolId = payload?.schoolId?.toIntOrNull()
        // persist
        store.accessToken = access
        store.accessExpiration = accessExp
        store.refreshToken = newRefresh
        if (schoolId != null) store.schoolId = schoolId
        true
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val token = store.accessToken
        val header = token?.let { "Bearer $it" }
        try {
            val res = api.logout(authHeader = header)
            // ignore HTTP result; clear locally regardless
        } catch (_: Throwable) {
        }
        store.clear()
        true
    }

    fun currentSession(): Session? {
        val at = store.accessToken ?: return null
        return Session(
            accessToken = at,
            refreshToken = store.refreshToken ?: return null,
            userName = store.userName,
            userId = store.userId,
            schoolId = store.schoolId
        )
    }

    // --- Children ---
    suspend fun getChildrenProfiles(): List<ChildProfile> = withContext(Dispatchers.IO) {
        val token = store.accessToken ?: return@withContext emptyList()
        val header = "Bearer $token"
        val res = api.getChildren(authHeader = header)
        if (!res.isSuccessful) return@withContext emptyList()
        val body = res.body() ?: return@withContext emptyList()
        body.items.mapNotNull { item ->
            val uuid = item.uuid
            // Format: prefix$<userId>.<year>.<schoolId>.<classId>.<studentId>
            val parts = uuid.substringAfter('$', uuid).split('.')
            val classId = parts.getOrNull(3)?.toIntOrNull()
            val studentId = parts.getOrNull(4)?.toIntOrNull() ?: return@mapNotNull null
            ChildProfile(
                uuid = uuid,
                studentId = studentId,
                classId = classId,
                displayName = item.displayName,
                className = item.className,
                schoolName = item.schoolName,
                subscriptionStatus = item.subscriptionStatus
            )
        }
    }

    // Paid grades fetch; returns empty list on error. Attempts one refresh on 401.
    suspend fun getGrades(childUuid: String): List<SubjectGrades> = withContext(Dispatchers.IO) {
        val token = store.accessToken ?: return@withContext emptyList()
        suspend fun call(bearer: String): retrofit2.Response<GradesResponse> {
            return api.getGrades(authHeader = bearer, childUuid = childUuid)
        }
        var bearer = "Bearer $token"
        var res = call(bearer)
        if (res.code() == 401) {
            if (refreshIfNeeded()) {
                bearer = "Bearer ${store.accessToken}"
                res = call(bearer)
            }
        }
        if (!res.isSuccessful) return@withContext emptyList()
        res.body()?.items ?: emptyList()
    }
}
