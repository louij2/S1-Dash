package com.louij2.s1dash

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Full-screen WebView running the MMI-style UI from assets/ui. The UI polls
 * [Bridge.snapshot] and calls the other bridge methods for actions.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DataHub.init(this)
        CalFlash.init(this)

        web = WebView(this)
        web.setBackgroundColor(0xFF000000.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "S1")
        setContentView(web)
        web.loadUrl("file:///android_asset/ui/index.html")

        askPermissions()
        DataHub.acquireSources(OWNER)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Back from SimosTools: take the dongle again. Not after a flash hand-off,
        // which says "paused for flash" until the user says the flash is over.
        if (DataHub.status["ecu"] == "paused for SimosTools") DataHub.restartSources()
    }

    @Deprecated("Fine for a single file pick")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null && requestCode in PICK_SLOT until PICK_SLOT + CalFlash.SLOTS) {
            Thread({ CalFlash.addFile(requestCode - PICK_SLOT, uri) }, "cal-add").start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        web.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    @Deprecated("Back goes up one screen in the UI first")
    override fun onBackPressed() {
        web.evaluateJavascript("window.goBack && window.goBack()") { r ->
            @Suppress("DEPRECATION")
            if (r != "true") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        DataHub.Logger.stop()
        DataHub.releaseSources(OWNER)
        web.destroy()
        super.onDestroy()
    }

    private fun askPermissions() {
        val want = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else if (Build.VERSION.SDK_INT < 29) arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) DataHub.restartSources()
    }

    inner class Bridge {
        @JavascriptInterface fun snapshot(): String = DataHub.snapshot()
        @JavascriptInterface fun settings(): String = DataHub.settingsJson()
        @JavascriptInterface fun setSetting(key: String, value: String) = DataHub.setSetting(key, value)
        @JavascriptInterface fun version(): String = BuildConfigInfo.version(this@MainActivity)

        @JavascriptInterface fun setMode(mode: Int) { DataHub.openHaldex()?.setMode(mode) }
        @JavascriptInterface fun resetPeak() { DataHub.boostPeak = 0.0 }
        @JavascriptInterface fun pollFocus(all: Boolean) { DataHub.focusAll = all }
        @JavascriptInterface fun clutchInfo(): String = DataHub.clutchInfoJson()
        @JavascriptInterface fun clutchReset() = DataHub.clutch.reset()

        @JavascriptInterface fun haldexDtcs(): String = DataHub.haldexDtcJson
        @JavascriptInterface fun haldexDtcRead() { DataHub.openHaldex()?.readDtcs() }
        @JavascriptInterface fun haldexDtcClear() { DataHub.openHaldex()?.clearDtcs() }
        @JavascriptInterface fun ecuDtcs(): String = DataHub.ecuDtcJson
        @JavascriptInterface fun ecuDtcRead() { DataHub.ecu()?.readDtcs() }
        @JavascriptInterface fun ecuDtcClear() { DataHub.ecu()?.clearDtcs() }

        @JavascriptInterface fun logStart() = DataHub.Logger.start()
        @JavascriptInterface fun logStop() = DataHub.Logger.stop()
        @JavascriptInterface fun logInfo(): String = JSONObject()
            .put("active", DataHub.Logger.active)
            .put("sessions", DataHub.Logger.sessions())
            .put("dir", DataHub.Logger.dir().absolutePath)
            .put("file", DataHub.Logger.file?.name ?: "")
            .toString()

        /** Bonded Bluetooth devices, so the user can pick the dongle instead of typing a MAC. */
        @SuppressLint("MissingPermission")
        @JavascriptInterface fun pairedDevices(): String {
            val arr = JSONArray()
            try {
                val bt = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                bt?.bondedDevices?.forEach { arr.put(JSONObject().put("name", it.name ?: "?").put("mac", it.address)) }
            } catch (_: SecurityException) {}
            return arr.toString()
        }

        /** Frees the dongle (only one app can use it) and opens SimosTools. */
        @JavascriptInterface fun openSimosTools(): Boolean {
            val intent = packageManager.getLaunchIntentForPackage("com.app.simostools") ?: return false
            DataHub.ecu()?.stop()
            DataHub.status["ecu"] = "paused for SimosTools"
            runOnUiThread { startActivity(intent) }
            return true
        }

        @JavascriptInterface fun calState(): String = CalFlash.json()
        @Suppress("DEPRECATION")
        @JavascriptInterface fun calPick(slot: Int) = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_SLOT + slot)
        }
        @JavascriptInterface fun calClear(slot: Int) = CalFlash.clearSlot(slot)
        @JavascriptInterface fun calPrepare(slot: Int) = CalFlash.prepare(slot)
        @JavascriptInterface fun calStart(): String = CalFlash.start() ?: ""
        @JavascriptInterface fun calReopen() = CalFlash.reopenSimos()
        @JavascriptInterface fun calFinished() = CalFlash.finished()
        @JavascriptInterface fun calCancel() = CalFlash.cancel()
        @JavascriptInterface fun calFinalise() = CalFlash.finalise()

        /** Reconnect the dongle after coming back from SimosTools. */
        @JavascriptInterface fun reconnect() = DataHub.restartSources()
    }
}

private const val PICK_SLOT = 100
private const val OWNER = "screen"

object BuildConfigInfo {
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }
}
