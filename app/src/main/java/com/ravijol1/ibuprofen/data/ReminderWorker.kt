package com.ravijol1.ibuprofen.data

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class ReminderWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val settings = SettingsStore(applicationContext)
            if (!settings.remindersEnabled) return Result.success()
            val uuid = settings.selectedChildUuid ?: return Result.success()
            val week = mobileReminderWeek(applicationContext) ?: return Result.success()
            // Fetch once, then arm every lesson in the next seven days. Each can fire offline.
            ReminderAlarms.replace(applicationContext, uuid, lessonReminderPlan(week, Instant.now()))
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: SessionExpiredException) {
            ReminderAlarms.cancelAll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            // Preserve alarms from the last successful fetch. WorkManager retries without cancelling itself.
            Result.retry()
        }
    }

    companion object ReminderScheduler {
        const val WORK_NAME = "next_lesson_reminder"
        private const val PERIODIC_WORK_NAME = "lesson_reminder_refresh"
        const val ACTION_REMINDER = "com.ravijol1.ibuprofen.action.LESSON_REMINDER"

        fun canScheduleExactAlarms(ctx: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        fun enable(ctx: Context) {
            if (!SettingsStore(ctx).remindersEnabled) return
            ReminderAlarms.restore(ctx)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(12, TimeUnit.HOURS)
                    .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5)).build())
            refresh(ctx)
        }

        fun refresh(ctx: Context) {
            if (!SettingsStore(ctx).remindersEnabled) return
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5)).build())
        }

        fun disable(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC_WORK_NAME)
            ReminderAlarms.cancelAll(ctx)
        }

        fun rescheduleOnBoot(ctx: Context) = enable(ctx)
    }
}
