package com.ravijol1.ibuprofen.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Lightweight foreground service that pre-schedules exact alarms for all remaining
 * lessons today (5 minutes before start). It starts, computes, schedules alarms, then stops.
 *
 * This improves reliability on OEMs that throttle background workers.
 */
class ReminderPlannerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        planAlarmsOnce(applicationContext) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "planner_status"
        if (nm.getNotificationChannel(channelId) == null) {
            val ch = NotificationChannel(channelId, "Planner status", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Schedules lesson reminders"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.ravijol1.ibuprofen.R.drawable.ic_launcher)
            .setContentTitle("Lesson planner")
            .setContentText("Scheduling next lesson reminders…")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(212121, notif)
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/Ljubljana")

        fun enqueue(context: Context) {
            val i = Intent(context.applicationContext, ReminderPlannerService::class.java)
            context.applicationContext.startForegroundService(i)
        }

        /**
         * Compute remaining lessons "today" for the currently selected child and
         * schedule exact alarms 5 minutes before each start.
         */
        fun planAlarmsOnce(ctx: Context, onDone: () -> Unit = {}) {
            val appCtx = ctx.applicationContext
            val settings = SettingsStore(appCtx)
            try {
                val week = kotlinx.coroutines.runBlocking { mobileReminderWeek(appCtx) } ?: run {
                    onDone()
                    return
                }
                val now = LocalDateTime.now(ZONE)
                val today = now.toLocalDate()
                val todayDay = week.days.find { it.date == today }
                if (todayDay != null) {
                    todayDay.lessonsByPeriod.filterIsInstance<PeriodCell.WithLessons>()
                        .filter { it.lessons.any { lesson -> !lesson.isCancelled } && LocalDateTime.of(today, it.timeRange.start).isAfter(now) }
                        .sortedBy { it.timeRange.start }.take(1).forEach { cell ->
                        if (cell is PeriodCell.WithLessons) {
                            val activeLessons = cell.lessons.filterNot { it.isCancelled }
                            if (activeLessons.isEmpty()) return@forEach
                            val s = cell.timeRange.start
                            val e = cell.timeRange.endInclusive
                            val valid = !(s == LocalTime.MIDNIGHT && e == LocalTime.MIDNIGHT)
                            if (!valid) return@forEach
                            val startDT = LocalDateTime.of(today, s)
                            if (startDT.isAfter(now)) {
                                val startEpoch = startDT.atZone(ZONE).toEpochSecond()
                                val fireAt = startDT.minusMinutes(5).atZone(ZONE).toEpochSecond()
                                ReminderWorker.ReminderScheduler.run {
                                    val title = "Čez 5 min: " + activeLessons.mapNotNull { it.subjectCode ?: it.subjectTitle }.filter { it.isNotBlank() }.joinToString(", ")
                                    val message = activeLessons.mapNotNull { it.room }.distinct().joinToString(", ").let { if (it.isNotBlank()) "Učilnica: $it" else "" }
                                    try {
                                        ReminderWorker.ReminderScheduler.scheduleExactAlarm(
                                            ctx = appCtx,
                                            epochSeconds = startEpoch,
                                            fireAtEpochSeconds = fireAt,
                                            title = title,
                                            message = message,
                                            dateIso = today.toString(),
                                            timeHm = s.toString().substring(0,5)
                                        )
                                    } catch (_: Throwable) { /* ignore */ }
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // ignore; we'll try again later (e.g., via worker)
            } finally {
                onDone()
            }
        }
    }
}
