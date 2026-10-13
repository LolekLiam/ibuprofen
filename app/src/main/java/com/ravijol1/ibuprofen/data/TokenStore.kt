package com.ravijol1.ibuprofen.data

import android.content.Context
import androidx.core.content.edit

class TokenStore(context: Context) : AuthSessionStore {
    private val prefs = SecurePreferences.open(context, PREF_NAME)

    override var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) { prefs.edit { putString(KEY_ACCESS, value) } }

    override var accessExpiration: String?
        get() = prefs.getString(KEY_ACCESS_EXP, null)
        set(value) { prefs.edit { putString(KEY_ACCESS_EXP, value) } }

    override var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) { prefs.edit { putString(KEY_REFRESH, value) } }

    override var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) { prefs.edit { putString(KEY_USER_NAME, value) } }

    override var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) { prefs.edit { putString(KEY_USER_ID, value) } }

    override var schoolId: Int?
        get() = prefs.getInt(KEY_SCHOOL_ID, -1).takeIf { it >= 0 }
        set(value) { prefs.edit { putInt(KEY_SCHOOL_ID, value ?: -1) } }

    override val deviceId: String
        get() = prefs.getString("device_id", null) ?: ("ibuprofen-" + java.util.UUID.randomUUID()).also {
            prefs.edit { putString("device_id", it) }
        }

    override fun clear() {
        val id = deviceId
        prefs.edit { clear(); putString("device_id", id) }
    }

    companion object {
        private const val PREF_NAME = "auth_tokens"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_ACCESS_EXP = "access_exp"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SCHOOL_ID = "school_id"
    }
}
