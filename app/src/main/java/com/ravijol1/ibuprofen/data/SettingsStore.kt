package com.ravijol1.ibuprofen.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) { 
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

    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDERS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_REMINDERS_ENABLED, value).apply() }

    var selectedChildUuid: String?
        get() = prefs.getString(KEY_CHILD_UUID, null)
        set(value) { prefs.edit().putString(KEY_CHILD_UUID, value).apply() }

    var selectedChildStudentId: Int?
        get() = prefs.getInt(KEY_CHILD_STUDENT_ID, -1).takeIf { it >= 0 }
        set(value) { prefs.edit().putInt(KEY_CHILD_STUDENT_ID, value ?: -1).apply() }

    var selectedChildClassId: Int?
        get() = prefs.getInt(KEY_CHILD_CLASS_ID, -1).takeIf { it >= 0 }
        set(value) { prefs.edit().putInt(KEY_CHILD_CLASS_ID, value ?: -1).apply() }

    var lastReminderStartEpoch: Long?
        get() = prefs.getLong(KEY_LAST_REMINDER_EPOCH, -1L).takeIf { it >= 0 }
        set(value) { prefs.edit().putLong(KEY_LAST_REMINDER_EPOCH, value ?: -1L).apply() }

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
    }
}
