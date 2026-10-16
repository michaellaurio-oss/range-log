package com.laurio.rangelog

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.laurio.rangelog.data.GunSummary

/** Posts a "time to clean" notification for a gun. Silently does nothing if notifications are off. */
object CleaningReminders {
    // New id: a channel's importance can't be raised after it was created, so "cleaning" is retired.
    private const val CHANNEL = "cleaning_due"

    fun notifyDue(context: Context, due: List<GunSummary>) {
        if (due.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Cleaning reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (s in due) {
            val interval = s.gun.cleanInterval ?: 0
            val over = s.roundsSinceCleaning - interval
            val text = "${s.roundsSinceCleaning} rds since the last cleaning (every $interval)" +
                if (over > 0) " - $over over" else ""
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Clean ${s.gun.name} now")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(s.gun.id.toInt(), notification)
        }
    }
}
