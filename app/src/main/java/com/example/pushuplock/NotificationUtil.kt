package com.example.pushuplock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationUtil {
    const val CHANNEL_ID = "lock_overlay_channel"
    private const val CHANNEL_NAME = "App Lock Overlay"
    private const val CHANNEL_DESC = "Foreground service for app lock overlay and countdown."

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESC
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun foregroundNotification(ctx: Context, text: String = "Lock active"): Notification {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("PushUp Lock")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }
}
