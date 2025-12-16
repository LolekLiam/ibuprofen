package com.ravijol1.ibuprofen.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZoneId

/**
 * Receives the exact alarm at the planned fire time and displays the lesson reminder.
 * Also re-enqueues the planner worker to compute the subsequent reminder.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderWorker.ACTION_REMINDER) return
        val appCtx = context.applicationContext
        val startEpoch = intent.getLongExtra(ReminderWorker.EXTRA_START_EPOCH, -1L)
        val title = intent.getStringExtra(ReminderWorker.EXTRA_TITLE)
        val message = intent.getStringExtra(ReminderWorker.EXTRA_MESSAGE)
        val dateIso = intent.getStringExtra(ReminderWorker.EXTRA_DATE)
        val timeHm = intent.getStringExtra(ReminderWorker.EXTRA_TIME)

        if (startEpoch > 0 && !title.isNullOrBlank()) {
            // De-dup: store last reminder epoch
            val settings = SettingsStore(appCtx)
            settings.lastReminderStartEpoch = startEpoch

            NotificationHelper.showLessonNotification(
                appCtx,
                notifyId = (startEpoch % Int.MAX_VALUE).toInt(),
                title = title,
                message = message ?: "",
                dateIso = dateIso,
                timeHm = timeHm,
                periodNumber = null
            )
        }
        // Schedule planner soon to compute next reminder
        ReminderWorker.enable(appCtx)
    }
}
