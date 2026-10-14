package com.ravijol1.ibuprofen.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import java.io.File
import java.time.Instant

/** Persist alarm payloads so delivery and reboot recovery do not require a network request. */
internal object ReminderAlarms {
    private data class StoredPlan(val childUuid: String, val reminders: List<LessonReminder>)
    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "lesson-reminder-plan.json")
    private fun read(ctx: Context): StoredPlan? = runCatching {
        file(ctx).takeIf { it.exists() }?.readText()?.let { Gson().fromJson(it, StoredPlan::class.java) }
    }.getOrNull()
    private fun pending(ctx: Context, epoch: Long, flags: Int): PendingIntent? = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ReminderWorker.ACTION_REMINDER
            data = Uri.parse("ibuprofen://lesson-reminder/$epoch")
        }, flags or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized fun replace(ctx: Context, childUuid: String, reminders: List<LessonReminder>) {
        val settings = SettingsStore(ctx)
        if (!settings.remindersEnabled || settings.selectedChildUuid != childUuid) return
        val previous = read(ctx)
        // Save atomically before registering alarms, so the receiver always has a valid payload.
        val target = file(ctx)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(Gson().toJson(StoredPlan(childUuid, reminders)))
        check(temporary.renameTo(target)) { "Could not persist lesson reminders" }
        cancelLegacyAlarm(ctx)
        previous?.reminders?.filter { old -> reminders.none { it.startEpoch == old.startEpoch } }?.forEach { cancel(ctx, it.startEpoch) }
        schedule(ctx, reminders)
    }

    @Synchronized fun restore(ctx: Context) {
        val stored = read(ctx) ?: return
        val settings = SettingsStore(ctx)
        if (!settings.remindersEnabled || stored.childUuid != settings.selectedChildUuid) {
            cancelAll(ctx)
            return
        }
        schedule(ctx, stored.reminders)
    }

    @Synchronized fun lesson(ctx: Context, epoch: Long): LessonReminder? {
        val stored = read(ctx) ?: return null
        if (stored.childUuid != SettingsStore(ctx).selectedChildUuid) return null
        return stored.reminders.firstOrNull { it.startEpoch == epoch }
    }

    private fun schedule(ctx: Context, reminders: List<LessonReminder>) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val now = Instant.now().epochSecond
        val last = SettingsStore(ctx).lastReminderStartEpoch
        reminders.forEach { reminder ->
            if (reminder.startEpoch <= now || reminder.startEpoch == last) {
                cancel(ctx, reminder.startEpoch)
                return@forEach
            }
            val operation = pending(ctx, reminder.startEpoch, PendingIntent.FLAG_UPDATE_CURRENT)!!
            val triggerMs = maxOf(reminder.fireAtEpoch, now) * 1000
            try {
                if (ReminderWorker.canScheduleExactAlarms(ctx)) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, operation)
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, operation)
            } catch (_: SecurityException) {
                // Access can be revoked between checking and scheduling; the UI explains the timing limitation.
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, operation)
            }
        }
    }

    private fun cancel(ctx: Context, epoch: Long) {
        pending(ctx, epoch, PendingIntent.FLAG_NO_CREATE)?.let {
            ctx.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }
    private fun cancelLegacyAlarm(ctx: Context) {
        val operation = PendingIntent.getBroadcast(ctx, 424242,
            Intent(ctx, ReminderAlarmReceiver::class.java).apply { action = ReminderWorker.ACTION_REMINDER },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(operation)
        operation.cancel()
    }

    @Synchronized fun cancelAll(ctx: Context) {
        read(ctx)?.reminders?.forEach { cancel(ctx, it.startEpoch) }
        file(ctx).delete()
        cancelLegacyAlarm(ctx)
    }
}
