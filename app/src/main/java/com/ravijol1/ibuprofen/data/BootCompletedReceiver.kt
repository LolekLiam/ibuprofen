package com.ravijol1.ibuprofen.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            val appCtx = context.applicationContext
            val settings = SettingsStore(appCtx)
            if (settings.remindersEnabled) {
                ReminderWorker.rescheduleOnBoot(appCtx)
                ReminderPlannerService.enqueue(appCtx)
            }
        }
    }
}
