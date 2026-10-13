package com.ravijol1.ibuprofen.data

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class SessionExpiredException : IllegalStateException("Your session expired. Please sign in again.")
class MobileApiException(val status: Int, message: String) : IllegalStateException(message)

object TokenExpiry {
    fun needsRefresh(expiration: String?, now: Instant = Instant.now()): Boolean {
        if (expiration.isNullOrBlank()) return true
        val date = sequenceOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"))
            .mapNotNull { runCatching { OffsetDateTime.parse(expiration, it).toInstant() }.getOrNull() }.firstOrNull()
            ?: return true
        return !date.isAfter(now.plusSeconds(60))
    }
}

class AuthRepository(private val api: AuthApi, private val store: AuthSessionStore) {
    data class Session(val accessToken: String, val refreshToken: String, val userName: String?,
        val userId: String?, val schoolId: Int?)
    companion object { private val refreshMutex = Mutex() }

    suspend fun login(username: String, password: String): Session = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter your username and password." }
        val body = successful(api.login(LoginRequest(username.trim(), password)))
        require(!body.accessToken?.token.isNullOrBlank() && !body.refreshToken.isNullOrBlank()) {
            "The server did not return a mobile session. Additional account verification may be required."
        }
        refreshMutex.withLock {
            store.clear()
            saveTokens(body.accessToken, body.refreshToken)
            store.userName = body.user?.name ?: username.trim()
            store.userId = body.user?.id?.toString() ?: JwtUtils.parsePayload(body.accessToken.token)?.userId
        }
        currentSession() ?: error("Could not save the mobile session.")
    }

    private fun saveTokens(access: AccessToken, refresh: String) {
        store.accessToken = access.token
        store.accessExpiration = access.expirationDate
        store.refreshToken = refresh
        JwtUtils.parsePayload(access.token)?.schoolId?.toIntOrNull()?.let { store.schoolId = it }
    }

    fun currentSession(): Session? {
        val access = store.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val refresh = store.refreshToken?.takeIf { it.isNotBlank() && it != "web" } ?: return null
        return Session(access, refresh, store.userName, store.userId, store.schoolId)
    }

    suspend fun refreshIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (currentSession() == null) return@withContext false
        if (!TokenExpiry.needsRefresh(store.accessExpiration)) return@withContext true
        refreshAccess(store.accessToken)
    }

    private suspend fun refreshAccess(failedAccess: String?): Boolean = refreshMutex.withLock {
        if (store.accessToken != failedAccess && currentSession() != null) return@withLock true
        val refresh = currentSession()?.refreshToken ?: return@withLock false
        val response = api.refresh(RefreshRequest(refresh))
        if (response.code() == 401 || response.code() == 403) {
            store.clear()
            return@withLock false
        }
        val body = successful(response)
        check(body.accessToken.token.isNotBlank() && body.refreshToken.isNotBlank()) { "Invalid session refresh response." }
        saveTokens(body.accessToken, body.refreshToken)
        true
    }

    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            try {
                store.accessToken?.let { api.logout(store.deviceId, "Bearer $it") }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                // Local logout also works when the device is offline.
            } finally { store.clear() }
        }
        true
    }

    private suspend fun <T> authorized(call: suspend (String) -> Response<T>): T = withContext(Dispatchers.IO) {
        val session = currentSession() ?: throw SessionExpiredException()
        if (TokenExpiry.needsRefresh(store.accessExpiration) && !refreshAccess(session.accessToken)) throw SessionExpiredException()
        var access = store.accessToken ?: throw SessionExpiredException()
        var response = call("Bearer $access")
        if (response.code() == 401) {
            if (!refreshAccess(access)) throw SessionExpiredException()
            access = store.accessToken ?: throw SessionExpiredException()
            response = call("Bearer $access")
            if (response.code() == 401) { store.clear(); throw SessionExpiredException() }
        }
        successful(response)
    }

    suspend fun getChildrenProfiles(): List<ChildProfile> = authorized { api.getChildren(it) }.items.map { it.toProfile() }
    suspend fun getSchoolWeeks(childUuid: String): List<MobileSchoolWeek> =
        authorized { api.getSchoolWeeks(it, childUuid) }.items.sortedBy { it.from }

    suspend fun getPersonalSchedule(child: ChildProfile, start: LocalDate, end: LocalDate): PersonalSchedule {
        val response = authorized { api.getTimetableEvents(it, child.uuid, start.toString(), end.toString()) }
        return withContext(Dispatchers.Default) { MobileTimetableMapper.map(response, child, start, end) }
    }

    suspend fun getMobileGrades(childUuid: String): MobileGrades {
        val overview = authorized { api.getOverview(it, childUuid) }
        var unavailable = false
        val summary = try { authorized { api.getConcealedGrades(it, childUuid) } }
            catch (e: MobileApiException) {
                if (e.status != 403 && e.status != 404) throw e
                unavailable = true
                null
            }
        return MobileGrades(overview.latestGrades.distinctBy { it.id }.sortedByDescending { it.date }, summary, unavailable)
    }

    private fun <T> successful(response: Response<T>): T {
        if (!response.isSuccessful) {
            val text = response.errorBody()?.string()
            val message = runCatching {
                val json = JsonParser.parseString(text).asJsonObject
                listOf("message", "user_message", "developer_message").firstNotNullOfOrNull { key ->
                    json.get(key)?.takeIf { it.isJsonPrimitive }?.asString
                }
            }.getOrNull()
            throw MobileApiException(response.code(), message ?: when (response.code()) {
                401 -> "Sign-in failed. Check your username and password."
                403 -> "This information is unavailable for your account."
                429 -> "Too many requests. Please try again in a moment."
                else -> "eAsistent returned HTTP ${response.code()}. Please try again."
            })
        }
        return response.body() ?: error("eAsistent returned an empty response.")
    }
}
