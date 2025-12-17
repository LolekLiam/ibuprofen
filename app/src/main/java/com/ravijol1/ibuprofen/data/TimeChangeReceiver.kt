package com.ravijol1.ibuprofen.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reschedules lesson reminders when the user changes time or timezone.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appCtx = context.applicationContext
        val settings = SettingsStore(appCtx)
        if (!settings.remindersEnabled) return
        when (action) {
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_USER_PRESENT,
            "android.os.action.DEVICE_IDLE_MODE_CHANGED" -> {
                // Re-enqueue planner worker (fallback) and foreground planner service
                ReminderWorker.rescheduleOnBoot(appCtx)
                ReminderPlannerService.enqueue(appCtx)
            }
        }
    }
}
