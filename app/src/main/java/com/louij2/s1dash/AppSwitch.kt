package com.louij2.s1dash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** Helpers for the S1 Dash <-> CarPlay switch (#1060). */
object AppSwitch {
    private val HINTS = listOf("carplay", "car play", "zlink", "autokit", "carlink", "carbit", "tlink", "boxtohome", "phonelink", "easyconnect", "cplay")

    /** Whether an app's package or name looks like the one that shows CarPlay, to list it first in the picker. */
    fun looksLikeCarPlay(pkg: String, name: String): Boolean {
        val s = (pkg + " " + name).lowercase()
        return HINTS.any { it in s }
    }

    /** Of an app's own screens, the likeliest one to open it: names like main/home/CarPlay first. */
    fun bestActivity(names: List<String>): String? =
        names.sortedBy { n -> ACTIVITY_HINTS.indexOfFirst { it in n.lowercase() }.let { if (it < 0) ACTIVITY_HINTS.size else it } }.firstOrNull()

    private val ACTIVITY_HINTS = listOf("carplay", "main", "home", "launch", "splash", "start")

    /**
     * The apps for the picker as JSON. Normally those with a launcher icon. With [all], every installed
     * app that has a screen another app may open, so a built-in (system) CarPlay app with no icon, as on
     * some Tboxes, can be picked too. CarPlay-looking apps first.
     */
    fun list(ctx: Context, all: Boolean): String {
        val pm = ctx.packageManager
        val rows = if (!all) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { Triple(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.applicationInfo.isSystem()) }
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .filter { openable(pm, it.packageName).isNotEmpty() }
                .map { Triple(it.packageName, it.loadLabel(pm).toString(), it.isSystem()) }
        }
        val sorted = rows.filter { it.first != ctx.packageName }.distinctBy { it.first }
            .sortedWith(compareBy({ !looksLikeCarPlay(it.first, it.second) }, { it.third }, { it.second.lowercase() }))
        val arr = JSONArray()
        for ((pkg, name, system) in sorted) {
            arr.put(JSONObject().put("pkg", pkg).put("name", name).put("carplay", looksLikeCarPlay(pkg, name)).put("system", system))
        }
        return arr.toString()
    }

    /**
     * How to open [pkg]: its launcher entry, else its TV launcher entry, else its likeliest own screen
     * that other apps may open (system apps often have no launcher icon). Null if there's none.
     */
    fun launchIntent(ctx: Context, pkg: String): Intent? {
        val pm = ctx.packageManager
        pm.getLaunchIntentForPackage(pkg)?.let { return it }
        pm.getLeanbackLaunchIntentForPackage(pkg)?.let { return it }
        val name = bestActivity(openable(pm, pkg)) ?: return null
        return Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, name))
    }

    /** The app's exported, enabled activities. */
    private fun openable(pm: PackageManager, pkg: String): List<String> = try {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES).activities.orEmpty()
            .filter { it.exported && it.enabled && it.permission == null }
            .map { it.name }
    } catch (_: Exception) { emptyList() }

    private fun ApplicationInfo.isSystem() = flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
