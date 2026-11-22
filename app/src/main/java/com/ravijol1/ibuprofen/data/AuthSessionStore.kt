package com.ravijol1.ibuprofen.data

/** The implementation stores tokens in encrypted preferences; passwords are never stored. */
interface AuthSessionStore {
    var accessToken: String?
    var accessExpiration: String?
    var refreshToken: String?
    var userName: String?
    var userEmail: String?
        get() = null
        set(value) {}
    var userId: String?
    var schoolId: Int?
    val deviceId: String
    fun readGrades(key: String): String? = null
    fun writeGrades(key: String, value: String) {}
    fun clear()
}
