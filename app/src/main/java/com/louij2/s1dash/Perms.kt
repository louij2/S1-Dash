package com.louij2.s1dash

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

/**
 * The Android permissions the optional features need, so Settings can show them and the UI can
 * prompt once (#1060, #1061). Each is true (granted), false (missing) or absent (not needed now).
 */
object Perms {
    fun state(ctx: Context): String {
        val o = JSONObject()
        if (DataHub.setting("returnBubble") == "1") o.put("overlay", Settings.canDrawOverlays(ctx))
        if (DataHub.setting("stayConnected") == "1") {
            o.put("notif", (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled())
            o.put("battery", (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName))
        }
        if (DataHub.setting("ecuAdapter") != "none" && Build.VERSION.SDK_INT >= 31) {
            o.put("bluetooth", ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        }
        return o.toString()
    }

    /** Opens the place to grant [which]: Android's own screen, or the runtime prompt where there is one. */
    fun ask(a: Activity, which: String) {
        val pkg = Uri.parse("package:" + a.packageName)
        val intent: Intent? = when (which) {
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)
            "notif" -> {
                if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    a.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not() && !asked(a, which)) {
                    markAsked(a, which)
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ)
                    null
                } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
            }
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            "bluetooth" -> {
                if (Build.VERSION.SDK_INT >= 31 && !asked(a, which)) {
                    markAsked(a, which)
                    a.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), REQ)
                    null
                } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            }
            else -> null
        }
        if (intent != null) {
            try { a.startActivity(intent) } catch (_: Exception) {
                // Some head-unit ROMs drop these screens: the app's own settings page always exists.
                try { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)) } catch (_: Exception) {}
            }
        }
    }

    // A runtime prompt Android has already shown (and the user refused) won't show again: go to the settings page instead.
    private fun asked(ctx: Context, which: String) = DataHub.prefs.getBoolean("permAsked_$which", false)
    private fun markAsked(ctx: Context, which: String) = DataHub.prefs.edit().putBoolean("permAsked_$which", true).apply()

    private const val REQ = 7
}
