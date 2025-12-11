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
        suspend fun call(bearer: String): retrofit2.Response<ChildrenResponse> {
            return api.getChildren(authHeader = bearer)
        }
        var bearer = "Bearer $token"
        var res = call(bearer)
        if (res.code() == 401 || res.code() == 403) {
            if (refreshIfNeeded()) {
                bearer = "Bearer ${store.accessToken}"
                res = call(bearer)
            }
        }
        if (!res.isSuccessful) {
            val code = res.code()
            if (code == 401 || code == 403) error("Unauthorized")
            return@withContext emptyList()
        }
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
        if (res.code() == 401 || res.code() == 403) {
            if (refreshIfNeeded()) {
                bearer = "Bearer ${store.accessToken}"
                res = call(bearer)
            }
        }
        if (!res.isSuccessful) {
            val code = res.code()
            if (code == 401 || code == 403) error("Unauthorized")
            return@withContext emptyList()
        }
        res.body()?.items ?: emptyList()
    }

    // --- Free grades via notifications ---
    suspend fun getFreeGrades(childUuid: String): List<SubjectGrades> = withContext(Dispatchers.IO) {
        val token = store.accessToken ?: return@withContext emptyList()
        val bearer0 = "Bearer $token"

        // Date helpers
        fun schoolYearStart(today: java.time.LocalDate = java.time.LocalDate.now()): java.time.LocalDate {
            val septFirst = java.time.LocalDate.of(today.year, 9, 1)
            return if (today.isBefore(septFirst)) septFirst.minusYears(1) else septFirst
        }
        val cutoff = schoolYearStart()
        val dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        suspend fun fetchPage(bearer: String, lastId: Long?): retrofit2.Response<NotificationsResponse> {
            return if (lastId == null) api.getNotifications(authHeader = bearer, childUuid = childUuid)
            else api.getNotificationsAfter(authHeader = bearer, childUuid = childUuid, lastId = lastId)
        }

        // Try once, then refresh on 401 and retry the same page
        var bearer = bearer0
        val all = mutableListOf<NotificationItem>()
        var lastId: Long? = null
        var keepGoing = true
        var refreshed = false
        while (keepGoing) {
            var res = fetchPage(bearer, lastId)
            if ((res.code() == 401 || res.code() == 403) && !refreshed) {
                if (refreshIfNeeded()) {
                    bearer = "Bearer ${store.accessToken}"
                    refreshed = true
                    res = fetchPage(bearer, lastId)
                }
            }
            if (!res.isSuccessful) {
                val code = res.code()
                if (code == 401 || code == 403) error("Unauthorized")
                break
            }
            val items = res.body()?.items.orEmpty()
            if (items.isEmpty()) break
            all += items
            // Stop condition: last item's created_at earlier than cutoff
            val last = items.last()
            lastId = last.id
            val lastDate = try { java.time.LocalDate.parse(last.createdAt?.substring(0,10)) } catch (_: Throwable) { null }
            if (lastDate != null && lastDate.isBefore(cutoff)) {
                keepGoing = false
            }
        }

        // Filter only grade notifications
        val gradeItems = all.asSequence()
            .filter { it.type?.equals("ocena", ignoreCase = true) == true || (it.title?.startsWith("Nova ocena") == true) }
            .toList()

        // Parse message: "<value> - <subjectShort>, <typeName>"
        val regex = Regex("^\\s*([^\\s]+)\\s*-\\s*([^,]+)\\s*,\\s*(.+)")
        data class Parsed(val subject: String, val grade: GradeItem)
        val parsed = gradeItems.mapNotNull { item ->
            val msg = item.message ?: return@mapNotNull null
            val m = regex.find(msg) ?: return@mapNotNull null
            val value = m.groupValues.getOrNull(1)
            val subjectShort = m.groupValues.getOrNull(2)?.trim()
            val typeName = m.groupValues.getOrNull(3)?.trim()
            val dateStr = item.createdAt?.substring(0,10)
            if (subjectShort.isNullOrBlank() || value.isNullOrBlank()) return@mapNotNull null
            Parsed(
                subject = subjectShort,
                grade = GradeItem(
                    typeName = typeName,
                    comment = null,
                    id = item.metaData?.gradeId ?: item.id,
                    type = "list",
                    overridesIds = null,
                    value = value,
                    color = null,
                    date = dateStr,
                    insertedAt = item.createdAt
                )
            )
        }

        // Group by subject and build SubjectGrades with one semester (1)
        val grouped = parsed.groupBy { it.subject }
        val result = grouped.map { (subject, list) ->
            val grades = list.map { it.grade }.sortedByDescending { it.insertedAt }
            SubjectGrades(
                name = subject,
                shortName = subject,
                id = subject.hashCode().toLong(),
                gradeType = "grade",
                isExcused = null,
                finalGrade = null,
                averageGrade = null,
                gradeRank = null,
                semesters = listOf(
                    SemesterGrades(
                        id = 1,
                        finalGrade = null,
                        grades = grades
                    ),
                    SemesterGrades(
                        id = 2,
                        finalGrade = null,
                        grades = emptyList()
                    )
                )
            )
        }.sortedBy { it.name }
        result
    }
}
