package com.ravijol1.ibuprofen.data

import android.content.Context
import androidx.core.content.edit

class SettingsStore(context: Context) { 
    private val prefs = SecurePreferences.open(context, PREF_NAME)

    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDERS_ENABLED, false)
        set(value) { prefs.edit { putBoolean(KEY_REMINDERS_ENABLED, value) } }

    var selectedChildUuid: String?
        get() = prefs.getString(KEY_CHILD_UUID, null)
        set(value) { prefs.edit { putString(KEY_CHILD_UUID, value) } }

    var selectedChildStudentId: Int?
        get() = prefs.getInt(KEY_CHILD_STUDENT_ID, -1).takeIf { it >= 0 }
        set(value) { prefs.edit { putInt(KEY_CHILD_STUDENT_ID, value ?: -1) } }

    var selectedChildClassId: Int?
        get() = prefs.getInt(KEY_CHILD_CLASS_ID, -1).takeIf { it >= 0 }
        set(value) { prefs.edit { putInt(KEY_CHILD_CLASS_ID, value ?: -1) } }

    var lastReminderStartEpoch: Long?
        get() = prefs.getLong(KEY_LAST_REMINDER_EPOCH, -1L).takeIf { it >= 0 }
        set(value) { prefs.edit { putLong(KEY_LAST_REMINDER_EPOCH, value ?: -1L) } }

    var selectedPublicClassId: Int?
        get() = prefs.getInt("public_class_id", -1).takeIf { it > 0 }
        set(value) { prefs.edit { putInt("public_class_id", value ?: -1) } }

    var proxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(value) { prefs.edit { putBoolean(KEY_PROXY_ENABLED, value) } }

    var proxyUrl: String
        get() = prefs.getString(KEY_PROXY_URL, "") ?: ""
        set(value) { prefs.edit { putString(KEY_PROXY_URL, value) } }

    fun adminToken(server: String): String? = if (prefs.getString("admin_server", null) == server)
        prefs.getString("admin_token", null) else null

    fun saveAdminSession(server: String, token: String) {
        prefs.edit { putString("admin_server", server); putString("admin_token", token) }
    }

    fun clearAdminSession(server: String) {
        if (prefs.getString("admin_server", null) == server) prefs.edit { remove("admin_token"); remove("admin_server") }
    }

    fun clearChild() {
        selectedChildUuid = null
        selectedChildStudentId = null
        selectedChildClassId = null
        lastReminderStartEpoch = null
    }

    companion object {
        private const val PREF_NAME = "app_settings"
        private const val KEY_REMINDERS_ENABLED = "reminders_enabled"
        private const val KEY_CHILD_UUID = "selected_child_uuid"
        private const val KEY_CHILD_STUDENT_ID = "selected_child_student_id"
        private const val KEY_CHILD_CLASS_ID = "selected_child_class_id"
        private const val KEY_LAST_REMINDER_EPOCH = "last_reminder_start_epoch"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_URL = "proxy_url"
    }
}
