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
        if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
            val settings = SettingsStore(context.applicationContext)
            if (settings.remindersEnabled) {
                ReminderWorker.rescheduleOnBoot(context.applicationContext)
            }
        }
    }
}
