package com.louij2.s1dash

import android.content.Context
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
class OpenHaldexSource(private val ctx: Context, setting: String) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val commands = LinkedBlockingQueue<() -> Unit>()
    private val address = OhAddress(setting, DataHub.prefs.getString(LAST_FOUND, null)) {
        DataHub.prefs.edit().putString(LAST_FOUND, it).apply()
    }
    private val finder = if (address.auto) OhFinder(ctx) { found ->
        address.found(found)
        showAddress()
    } else null
    private val host get() = address.host

    fun start() {
        running = true
        DataHub.holdLonger(KEYS, HOLD_MS)
        showAddress()
        finder?.search()
        thread = Thread({ loop() }, "openhaldex").apply { start() }
    }

    fun stop() {
        running = false
        finder?.stop()
        thread?.interrupt()
    }

    private fun showAddress() {
        DataHub.status["ohAt"] = "${address.host} (${address.source})"
    }

    fun setMode(mode: Int) = commands.offer {
        post("/api/mode", JSONObject().put("mode", mode).toString(), MODE_READ_MS)
        lastSettings = 0L // re-read the flags that can make the controller ignore it
    }

    @Volatile private var lastSettings = 0L

    fun readDtcs() = commands.offer { dtcJob(false) }

    fun clearDtcs() = commands.offer { dtcJob(true) }

    private fun loop() {
        var lastDtc = 0L
        var lastGood = System.currentTimeMillis()
        // Lost means no good reply for LOST_MS, not a count of misses: one slow C6 reply isn't a drop (#1061).
        fun lost(why: String) {
            if (System.currentTimeMillis() - lastGood > LOST_MS) DataHub.status["oh"] = why
        }
        while (running) {
            try {
                while (true) {
                    val cmd = commands.poll() ?: break
                    cmd()
                }
                val body = get("/api/dashboard")
                if (body == null) {
                    lost("no reply")
                    if (address.failed()) { // moved (bridge mode on a hotspot)? look for it by name
                        showAddress()
                        finder?.search()
                    }
                    sleep(1000)
                    continue
                }
                lastGood = System.currentTimeMillis()
                address.answered()
                DataHub.status["oh"] = "connected"
                parse(JSONObject(body))
                // Flags that make OpenHaldex ignore mode requests, on connect and every 30 s.
                if (System.currentTimeMillis() - lastSettings > 30_000L) {
                    lastSettings = System.currentTimeMillis()
                    get("/api/settings")?.let { readSettings(JSONObject(it)) }
                }
                // Haldex fault codes on connect and every 5 minutes.
                if (System.currentTimeMillis() - lastDtc > 5 * 60_000L) {
                    lastDtc = System.currentTimeMillis()
                    dtcJob(false)
                }
                commands.poll(200, TimeUnit.MILLISECONDS)?.invoke()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                lost("error: ${e.javaClass.simpleName}")
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
        // Force-mode triggers (#1049); null while OpenHaldex has no chassis CAN, so they go stale.
        fun flag(k: String, onWhen: Boolean) {
            if (o.has(k) && !o.isNull(k)) DataHub.put(FLAG_KEYS.getValue(k), if (o.optBoolean(k) == onWhen) 1.0 else 0.0)
        }
        flag("tcOn", false)
        flag("asrOn", false)
        flag("hazardActive", true)
        flag("extButtonActive", true)
        o.optJSONObject("driveMode")?.let { d -> if (!d.isNull("forcing")) DataHub.put("hxDriveForce", d.optDouble("forcing", -1.0)) }
    }

    /** With Disable controller on, OpenHaldex ignores every mode request (its own button still works);
     *  in standalone mode it turns Stock into "keep the last mode". The UI explains a tap that didn't take. */
    private fun readSettings(o: JSONObject) {
        DataHub.status["ohDisabled"] = if (o.optBoolean("disableController")) "1" else "0"
        DataHub.status["ohStandalone"] = if (o.optBoolean("isStandalone")) "1" else "0"
        // Which triggers force a mode, to what, and which wins: the UI works out the mode in use (#1049).
        DataHub.status["ohForce"] = JSONObject()
            .put("tc", org.json.JSONArray().put(o.optBoolean("tcForceMode")).put(o.optInt("tcForceModeValue", -1)))
            .put("hz", org.json.JSONArray().put(o.optBoolean("hazardForceMode")).put(o.optInt("hazardForceModeValue", -1)))
            .put("ext", org.json.JSONArray().put(o.optBoolean("extButtonForceMode")).put(o.optInt("extBtnForceModeValue", -1)))
            .put("prio", o.optInt("forceModesPriority", 0))
            .toString()
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

    private fun open(path: String, readMs: Int = READ_MS): HttpURLConnection {
        val h = host
        val url = URL("http://$h$path")
        // The OpenHaldex AP has no internet, so bind to the network that holds its address rather
        // than let requests go out over mobile data. None holds it on this device's own hotspot.
        val c = (OhAddress.networkFor(ctx, h)?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        c.connectTimeout = CONNECT_MS
        c.readTimeout = readMs
        return c
    }

    private fun get(path: String): String? = try {
        val c = open(path)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    } catch (_: Exception) { null }

    /** OpenHaldex never answers POST /api/mode (it applies the body), so don't wait long for it. */
    private fun post(path: String, json: String, readMs: Int = READ_MS): String? = try {
        val c = open(path, readMs)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        try { c.inputStream.bufferedReader().readText() } catch (_: Exception) { "" } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) { running = false } }

    private companion object {
        const val LAST_FOUND = "ohLastFound"
        /** The C6 shares one radio between Wi-Fi and BLE; about 1 in 40 replies takes 2-5 s (#1061). */
        const val CONNECT_MS = 1500
        const val READ_MS = 4000
        const val MODE_READ_MS = 1000
        const val LOST_MS = 8000L
        /** OpenHaldex values stay on screen this long, so a stalled reply doesn't blank them. */
        const val HOLD_MS = 10_000L
        val KEYS = listOf("hxMode", "hxLock", "hxLockTarget", "hxTempProt", "ohRpm", "ohBoost", "ohOil", "ohCoolant",
            "speed", "throttle", "hxVolts", "hxClutchTemp", "hxModuleTemp", "hxFinTemp", "hxClutchCurrent",
            "hxTcOff", "hxAsrOff", "hxHazard", "hxExtBtn", "hxDriveForce", "dtcHaldex")
        /** /api/dashboard flag -> hub key, each 1 when the override is on. */
        val FLAG_KEYS = mapOf("tcOn" to "hxTcOff", "asrOn" to "hxAsrOff", "hazardActive" to "hxHazard", "extButtonActive" to "hxExtBtn")
    }
}
