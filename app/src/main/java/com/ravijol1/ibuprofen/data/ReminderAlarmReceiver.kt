package com.ravijol1.ibuprofen.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderWorker.ACTION_REMINDER) return
        val ctx = context.applicationContext
        val settings = SettingsStore(ctx)
        if (!settings.remindersEnabled || AuthRepository(NetworkProvider.authApi, TokenStore(ctx)).currentSession() == null) return
        val epoch = intent.data?.lastPathSegment?.toLongOrNull() ?: return // Ignore old single-alarm payloads.
        val reminder = ReminderAlarms.lesson(ctx, epoch) ?: return
        val now = Instant.now().epochSecond
        if (shouldDeliverReminder(epoch, now, settings.lastReminderStartEpoch)) {
            if (NotificationHelper.showLessonNotification(ctx, (epoch % Int.MAX_VALUE).toInt(),
                reminder.title(now), reminder.message, reminder.dateIso, reminder.timeHm)) {
                settings.lastReminderStartEpoch = epoch
            }
        }
        // Later lessons are already armed. This refresh only updates the cached schedule.
        ReminderWorker.refresh(ctx)
    }
}
