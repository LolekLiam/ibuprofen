package com.ravijol1.ibuprofen.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ravijol1.ibuprofen.MainActivity
import com.ravijol1.ibuprofen.R

object NotificationHelper {
    // Bump channel ID to ensure we can raise importance on devices where the old channel already existed.
    const val CHANNEL_ID = "lesson_reminders_v2"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.app_name) + " – Lessons",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders 5 minutes before lessons start"
                enableLights(true)
                lightColor = Color.BLUE
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun showLessonNotification(
        context: Context,
        notifyId: Int,
        title: String,
        message: String,
        dateIso: String? = null, // yyyy-MM-dd
        timeHm: String? = null,  // HH:mm
        periodNumber: Int? = null
    ) {
        ensureChannel(context)
        // Open app to Child tab and jump to specific day/period
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "Child")
            dateIso?.let { putExtra("jump_to_child_date", it) }
            timeHm?.let { putExtra("jump_to_child_time", it) }
            periodNumber?.let { putExtra("jump_to_child_period", it) }
        }
        val pi = PendingIntent.getActivity(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Heads-up + sound/vibrate defaults
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Check permission on Android 13+
        val canNotify = try {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        } catch (_: Throwable) { true }
        if (!canNotify) return
        try {
            NotificationManagerCompat.from(context).notify(notifyId, builder.build())
        } catch (_: SecurityException) {
            // Swallow if permission not granted
        }
    }
}
