package com.louij2.s1dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Polls the OpenHaldex-C6 controller's HTTP API over its Wi-Fi access point.
 * OpenHaldex stays the only thing that controls the Haldex; mode changes are
 * requests to it (POST /api/mode).
 */
class OpenHaldexSource(private val ctx: Context, private val host: String) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val commands = LinkedBlockingQueue<() -> Unit>()

    fun start() {
        running = true
        thread = Thread({ loop() }, "openhaldex").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
    }

    fun setMode(mode: Int) = commands.offer { post("/api/mode", JSONObject().put("mode", mode).toString()) }

    fun readDtcs() = commands.offer { dtcJob(false) }

    fun clearDtcs() = commands.offer { dtcJob(true) }

    private fun loop() {
        var lastDtc = 0L
        var fails = 0
        while (running) {
            try {
                while (true) {
                    val cmd = commands.poll() ?: break
                    cmd()
                }
                val body = get("/api/dashboard")
                if (body == null) {
                    fails++
                    if (fails >= 3) DataHub.status["oh"] = "no reply"
                    sleep(1000)
                    continue
                }
                fails = 0
                DataHub.status["oh"] = "connected"
                parse(JSONObject(body))
                // Haldex fault codes on connect and every 5 minutes.
                if (System.currentTimeMillis() - lastDtc > 5 * 60_000L) {
                    lastDtc = System.currentTimeMillis()
                    dtcJob(false)
                }
                commands.poll(200, TimeUnit.MILLISECONDS)?.invoke()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                DataHub.status["oh"] = "error: ${e.javaClass.simpleName}"
                sleep(1000)
            }
        }
    }

    private fun parse(o: JSONObject) {
        fun num(obj: JSONObject, k: String): Double? = if (obj.isNull(k)) null else obj.optDouble(k, Double.NaN)
        DataHub.put("hxMode", num(o, "mode"))
        DataHub.put("hxLock", num(o, "lockActual") ?: num(o, "haldexEngagement"))
        DataHub.put("hxLockTarget", num(o, "lockTarget"))
        if (!o.isNull("tempProtection")) DataHub.put("hxTempProt", if (o.optBoolean("tempProtection")) 1.0 else 0.0)
        DataHub.put("ohRpm", num(o, "rpm"))
        // OpenHaldex 11.x: boost is gauge mbar (clamped at 0), oil/coolant are whole °C from
        // Kombi_2 (null when stale). Used only when the ECU dongle gives nothing (DataHub.FALLBACK).
        num(o, "boost")?.let { DataHub.put("ohBoost", it / 1000.0) }
        DataHub.put("ohOil", num(o, "oilTemp"))
        DataHub.put("ohCoolant", num(o, "coolantTemp"))
        DataHub.put("speed", num(o, "speed"))
        DataHub.put("throttle", num(o, "throttle"))
        o.optJSONObject("uds")?.let { u ->
            DataHub.put("hxVolts", num(u, "terminalVoltage"))
            DataHub.put("hxClutchTemp", num(u, "clutchTemp"))
            DataHub.put("hxModuleTemp", num(u, "moduleTemp"))
            DataHub.put("hxFinTemp", num(u, "coolingFinTemp"))
            DataHub.put("hxClutchCurrent", num(u, "clutchCurrent"))
        }
        DataHub.status["ohDiagTool"] = if (o.optBoolean("diagToolActive")) "1" else "0"
    }

    /** Runs a Haldex DTC read (or clear) job on the controller and waits for the result. */
    private fun dtcJob(clear: Boolean) {
        val ok = if (clear) post("/api/dtc/clear", """{"confirm":"CLEAR"}""") else post("/api/dtc/read", "{}")
        if (ok == null) return
        for (i in 0 until 20) {
            sleep(500)
            val body = get("/api/dtc") ?: continue
            val o = JSONObject(body)
            val state = o.optString("state")
            if (state == "done" || state == "error") {
                DataHub.haldexDtcJson = body
                if (state == "done") DataHub.put("dtcHaldex", (o.optJSONArray("dtcs")?.length() ?: 0).toDouble())
                return
            }
        }
    }

    /** The OpenHaldex AP has no internet; make sure requests go out over Wi-Fi. */
    private fun wifiNetwork(): Network? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun open(path: String): HttpURLConnection {
        val url = URL("http://$host$path")
        val c = (wifiNetwork()?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = 800
        c.readTimeout = 1500
        return c
    }

    private fun get(path: String): String? = try {
        val c = open(path)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun post(path: String, json: String): String? = try {
        val c = open(path)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        try { c.inputStream.bufferedReader().readText() } catch (_: Exception) { "" } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false } }
}
