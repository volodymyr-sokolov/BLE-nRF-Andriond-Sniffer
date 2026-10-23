package com.blesniffer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps a running capture alive while the app is not on screen.
 *
 * Without this the capture is only as durable as the activity. Android is free
 * to suspend an app whose screen has slept, and it does: a bench session left
 * running lost its capture partway through, and because the packets simply
 * stopped arriving rather than anything failing, the affected scenario recorded
 * a long run of zeros that looked exactly like a channel under total jamming.
 * The measurement was believed before the gap was noticed.
 *
 * A capture is a long-running thing the user explicitly started and can see in
 * the notification shade, which is precisely what a foreground service is for.
 * The notification is not decoration - it is the only indication, once the app
 * is out of sight, that the radio is still recording.
 */
class CaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY would have the system restart this with a null intent
        // after a kill, resurrecting the notification while the ViewModel that
        // owns the radio is gone - a service claiming to capture nothing.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Packet capture",
                    // Low: this must not interrupt anything. It exists to be
                    // present, not to be noticed.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while the sniffer is capturing packets."
                    setShowBadge(false)
                },
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Capturing BLE packets")
            .setContentText("Tap to return to the capture.")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}
