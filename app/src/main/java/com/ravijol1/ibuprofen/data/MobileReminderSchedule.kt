package com.ravijol1.ibuprofen.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

internal suspend fun mobileReminderWeek(context: Context): TimetableWeek? {
    val settings = SettingsStore(context)
    if (!settings.remindersEnabled) return null
    val uuid = settings.selectedChildUuid ?: return null
    if (uuid.startsWith("web:")) return null
    val studentId = settings.selectedChildStudentId ?: return null
    val auth = AuthRepository(NetworkProvider.authApi, TokenStore(context))
    if (auth.currentSession() == null) return null
    val child = ChildProfile(uuid, studentId, settings.selectedChildClassId, null, null, null, null)
    val start = LocalDate.now(ZoneId.of("Europe/Ljubljana"))
    return auth.getPersonalSchedule(child, start, start.plusDays(6)).week
}
