package com.louij2.s1dash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * "Stay connected in the background" (#1061): a foreground service with a persistent notification,
 * so Android keeps S1 Dash (OpenHaldex polling, the ECU dongle, logging, alerts) alive behind CarPlay
 * or any other app. The notification shows both connections and has a Disconnect button.
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build())
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            DataHub.disconnectByUser()
            stopSelf()
            return START_NOT_STICKY
        }
        channel()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, build())
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, REFRESH_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while S1 Dash stays connected in the background"
            setShowBadge(false)
        })
    }

    private fun build(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, KeepAliveService::class.java).setAction(ACTION_DISCONNECT), flags)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("S1 Dash connected")
            .setContentText(summary())
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    private fun summary(): String =
        "OpenHaldex: " + (DataHub.status["oh"] ?: "--") + " · ECU: " + (DataHub.status["ecu"] ?: "--")

    companion object {
        private const val ID = 1061
        private const val CHANNEL = "connection"
        private const val REFRESH_MS = 5000L
        const val ACTION_DISCONNECT = "com.louij2.s1dash.DISCONNECT"

        /** Starts or stops the service to match the setting. Called while S1 Dash is on screen. */
        fun apply(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (DataHub.setting("stayConnected") == "1") {
                try {
                    if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                } catch (_: Exception) {} // not allowed from the background: tried again on the next onStart
            } else ctx.stopService(i)
        }
    }
}
