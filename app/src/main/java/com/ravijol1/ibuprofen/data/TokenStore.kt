package com.ravijol1.ibuprofen.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) { prefs.edit { putString(KEY_ACCESS, value) } }

    var accessExpiration: String?
        get() = prefs.getString(KEY_ACCESS_EXP, null)
        set(value) { prefs.edit { putString(KEY_ACCESS_EXP, value) } }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) { prefs.edit { putString(KEY_REFRESH, value) } }

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) { prefs.edit { putString(KEY_USER_NAME, value) } }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) { prefs.edit { putString(KEY_USER_ID, value) } }

    var schoolId: Int?
        get() = prefs.getInt(KEY_SCHOOL_ID, -1).takeIf { it >= 0 }
        set(value) { prefs.edit { putInt(KEY_SCHOOL_ID, value ?: -1) } }

    fun clear() {
        prefs.edit { clear() }
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
