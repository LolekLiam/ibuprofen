package com.ravijol1.ibuprofen.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val appCtx = applicationContext
        val tokenStore = TokenStore(appCtx)
        val settings = SettingsStore(appCtx)
        val session = tokenStore.accessToken
        val schoolId = tokenStore.schoolId
        val studentId = settings.selectedChildStudentId
        val classId = settings.selectedChildClassId ?: 0
        if (session.isNullOrBlank() || schoolId == null || studentId == null) {
            // Nothing to do; cancel chain
            return Result.success()
        }

        val repo = NetworkProvider.repository
        val access = session
        // Always use current week (0) to remain aligned without extra logic
        val weekId = 0
        val week = try {
            repo.loadChildTimetableWeek(
                accessToken = access,
                schoolId = schoolId,
                studentId = studentId,
                weekId = weekId,
                classId = classId
            )
        } catch (t: Throwable) {
            // Soft-fail and retry in 30 minutes
            schedulePlannerWork(appCtx, Duration.ofMinutes(30))
            return Result.success()
        }

        val now = LocalDateTime.now(ZONE)
        // Find the next lesson start strictly after now
        data class Slot(val dateTime: LocalDateTime, val subjects: List<String>, val rooms: List<String>)
        val upcoming = mutableListOf<Slot>()
        week.days.forEach { day ->
            val d: LocalDate = day.date
            day.lessonsByPeriod.forEach { cell ->
                if (cell is PeriodCell.WithLessons) {
                    val s: LocalTime = cell.timeRange.start
                    val e: LocalTime = cell.timeRange.endInclusive
                    val valid = !(s == LocalTime.MIDNIGHT && e == LocalTime.MIDNIGHT)
                    if (!valid) return@forEach
                    val startDT = LocalDateTime.of(d, s)
                    if (startDT.isAfter(now)) {
                        // Collect lesson info
                        val subjects = cell.lessons.mapNotNull { it.subjectCode ?: it.subjectTitle }.filter { it.isNotBlank() }
                        val rooms = cell.lessons.mapNotNull { it.room }.filter { it.isNotBlank() }
                        upcoming += Slot(startDT, subjects.ifEmpty { listOf("Ura") }, rooms)
                    }
                }
            }
        }
        val next = upcoming.minByOrNull { it.dateTime } ?: run {
            // No more lessons this week; schedule a check next weekday 06:00
            val delay = nextSchoolCheckDelay(now)
            schedulePlannerWork(appCtx, delay)
            return Result.success()
        }

        val fireAt = next.dateTime.minusMinutes(5)
        val delay = Duration.between(now, fireAt)
        val settingsStore = settings
        val startEpoch = next.dateTime.atZone(ZONE).toEpochSecond()

        if (delay.isNegative || delay.isZero) {
            // We are within the 5-minute window (or past). Deduplicate: notify only once per lesson start.
            if (settingsStore.lastReminderStartEpoch == startEpoch) {
                // Already notified for this lesson; schedule next planning just after lesson start to find the following one
                val afterStart = Duration.between(now, next.dateTime.plusMinutes(1)).coerceAtLeast(Duration.ofMinutes(1))
                schedulePlannerWork(appCtx, afterStart)
                return Result.success()
            }
            // Fire now
            val title = "Čez 5 min: " + next.subjects.joinToString(", ")
            val message = if (next.rooms.isNotEmpty()) {
                "Učilnica: " + next.rooms.joinToString(", ")
            } else ""
            // Persist last fired lesson start to avoid duplicates
            settingsStore.lastReminderStartEpoch = startEpoch
            NotificationHelper.showLessonNotification(
                appCtx,
                notifyId = startEpoch.toInt(),
                title = title,
                message = message,
                dateIso = next.dateTime.toLocalDate().toString(),
                timeHm = next.dateTime.toLocalTime().toString().substring(0,5),
                periodNumber = null
            )
            // Plan next run just after lesson start to look for the following one
            val afterStart = Duration.between(now, next.dateTime.plusMinutes(1)).coerceAtLeast(Duration.ofMinutes(1))
            schedulePlannerWork(appCtx, afterStart)
            return Result.success()
        } else {
            // Schedule an exact alarm to fire at the right moment; receiver will show notification and re-plan
            scheduleExactAlarm(
                ctx = appCtx,
                epochSeconds = startEpoch,
                fireAtEpochSeconds = fireAt.atZone(ZONE).toEpochSecond(),
                title = "Čez 5 min: " + next.subjects.joinToString(", "),
                message = if (next.rooms.isNotEmpty()) "Učilnica: " + next.rooms.joinToString(", ") else "",
                dateIso = next.dateTime.toLocalDate().toString(),
                timeHm = next.dateTime.toLocalTime().toString().substring(0,5)
            )
            return Result.success()
        }
    }

    private fun schedulePlannerWork(ctx: Context, delay: Duration) {
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
    }

    private fun scheduleExactAlarm(
        ctx: Context,
        epochSeconds: Long,
        fireAtEpochSeconds: Long,
        title: String,
        message: String,
        dateIso: String,
        timeHm: String
    ) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_START_EPOCH, epochSeconds)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_DATE, dateIso)
            putExtra(EXTRA_TIME, timeHm)
        }
        val pi = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerMs = fireAtEpochSeconds * 1000L
        try {
            // API 31+: apps may be restricted from exact alarms; attempt and fallback on failure
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } catch (se: SecurityException) {
            // Fallback: schedule planner with approximate delay
            val approx = Duration.ofMillis(triggerMs - System.currentTimeMillis())
            schedulePlannerWork(ctx, approx.coerceAtLeast(Duration.ofMinutes(1)))
        }
    }

    companion object ReminderScheduler {
        private val ZONE: ZoneId = ZoneId.of("Europe/Ljubljana")
        const val WORK_NAME = "next_lesson_reminder"
        const val ACTION_REMINDER = "com.ravijol1.ibuprofen.action.LESSON_REMINDER"
        const val EXTRA_START_EPOCH = "start_epoch"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DATE = "date_iso"
        const val EXTRA_TIME = "time_hm"
        private const val REQUEST_CODE = 424242

        fun enable(ctx: Context) {
            // Start soon to compute next
            val req = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(Duration.ofSeconds(5))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }

        fun disable(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
            // Also cancel any pending alarm
            val intent = Intent(ctx, ReminderAlarmReceiver::class.java).apply { action = ACTION_REMINDER }
            val pi = PendingIntent.getBroadcast(ctx, REQUEST_CODE, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pi != null) {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pi)
                pi.cancel()
            }
        }

        fun rescheduleOnBoot(ctx: Context) {
            enable(ctx)
        }

        private fun nextSchoolCheckDelay(now: LocalDateTime): Duration {
            // Next weekday 06:00
            var dt = now
            do {
                dt = dt.plusDays(1)
            } while (dt.dayOfWeek.value >= 6) // skip Sat(6) and Sun(7)
            val target = LocalDateTime.of(dt.toLocalDate(), LocalTime.of(6, 0))
            return Duration.between(now, target).coerceAtLeast(Duration.ofHours(4))
        }
    }
}
