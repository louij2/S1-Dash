package com.louij2.s1dash

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared state between the data sources and the UI. Sources call [put]; the
 * WebView polls [snapshot]. Values older than [STALE_MS] are left out so the
 * UI shows "--" instead of a frozen reading.
 */
object DataHub {
    const val STALE_MS = 3000L

    private val values = ConcurrentHashMap<String, Double>()
    private val stamps = ConcurrentHashMap<String, Long>()
    private val holds = ConcurrentHashMap<String, Long>()  // ECU values: how long this adapter's readings stay good
    @Volatile var focusAll = false                         // a screen is showing values beyond the core dials
    val status = ConcurrentHashMap<String, String>()

    @Volatile var haldexDtcJson: String = "{}"
    @Volatile var ecuDtcJson: String = "{}"
    @Volatile var boostPeak: Double = 0.0
    @Volatile var ecuPart: String? = null       // spare part number, 0xF187
    @Volatile var ecuFlashLog: String? = null   // flash record, 0xF15B; empty = couldn't be read

    lateinit var prefs: SharedPreferences
        private set
    private lateinit var appContext: Context

    private var openHaldex: OpenHaldexSource? = null
    private var ecu: EcuPoller? = null
    private var demo: DemoSource? = null

    val DEFAULTS = mapOf(
        "theme" to "s1",              // s1 | quattro | mmi
        "ohEnabled" to "1",
        "ohHost" to "192.168.1.1",    // OpenHaldex access point address
        "ecuAdapter" to "none",       // none | bridge (SimosTools dongle) | elm (ELM327, e.g. the wheel's dongle)
        "ecuMac" to "",               // adapter MAC; for the bridge, empty = find it by name
        "bridgeName" to "BLE_TO_ISOTP20",
        "dtcIndicator" to "1",
        "boostTarget" to "1.8",       // expected peak boost, bar, marked on the dial
        "demo" to "0",
    )

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = appContext.getSharedPreferences("s1dash", Context.MODE_PRIVATE)
    }

    fun setting(key: String): String = prefs.getString(key, DEFAULTS[key] ?: "") ?: ""

    fun settingsJson(): String {
        val o = JSONObject()
        for (k in DEFAULTS.keys) o.put(k, setting(k))
        return o.toString()
    }

    fun setSetting(key: String, value: String) {
        if (key !in DEFAULTS) return
        prefs.edit().putString(key, value).apply()
        if (key in setOf("ohEnabled", "ohHost", "ecuAdapter", "ecuMac", "bridgeName", "demo")) restartSources()
    }

    fun put(key: String, v: Double?) {
        if (v == null || v.isNaN() || v.isInfinite()) return
        values[key] = v
        stamps[key] = System.currentTimeMillis()
        if (key == "boost" && v > boostPeak) boostPeak = v
    }

    /** Values OpenHaldex can stand in for when the ECU dongle isn't connected. The ECU wins when both exist. */
    private val FALLBACK = mapOf("oil" to "ohOil", "coolant" to "ohCoolant", "boost" to "ohBoost")

    private fun holdFor(key: String) = holds[key] ?: STALE_MS

    private fun fresh(key: String): Double? {
        val t = stamps[key] ?: return null
        return if (System.currentTimeMillis() - t <= holdFor(key)) values[key] else null
    }

    fun wantAllValues() = focusAll || Logger.active

    fun setHolds(fastMs: Long, slowMs: Long, fastKeys: List<String>, derived: List<String>) {
        for (p in EcuPids.ALL) holds[p.key] = slowMs
        for (k in fastKeys) holds[k] = fastMs
        for (k in derived) holds[k] = fastMs
    }

    /** The ECU connection went: don't keep showing its values for a long hold. */
    fun dropEcuValues() {
        for (k in holds.keys) { stamps.remove(k); values.remove(k) }
        holds.clear()
        status.remove("ecuData")
        status.remove("ecuRate")
    }

    fun get(key: String): Double? = fresh(key) ?: FALLBACK[key]?.let { fresh(it) }

    fun snapshot(): String {
        val now = System.currentTimeMillis()
        val v = JSONObject()
        for ((k, value) in values) {
            val t = stamps[k] ?: continue
            if (now - t <= holdFor(k)) v.put(k, value)
        }
        for ((k, fb) in FALLBACK) if (!v.has(k)) fresh(fb)?.let { v.put(k, it) }
        val s = JSONObject()
        for ((k, value) in status) s.put(k, value)
        return JSONObject()
            .put("v", v)
            .put("s", s)
            .put("peak", boostPeak)
            .put("logging", Logger.active)
            .put("t", now)
            .toString()
    }

    /** Learned from real drives and kept across restarts. The demo uses its own model, so it can't touch these. */
    val clutch: ClutchModel by lazy {
        ClutchModel(ClutchModel.decode(prefs.getString(CLUTCH_RATIOS_KEY, "") ?: "")) { r ->
            prefs.edit().putString(CLUTCH_RATIOS_KEY, ClutchModel.encode(r)).apply()
        }
    }
    private const val CLUTCH_RATIOS_KEY = "clutchRatios"
    val CLUTCH_INPUTS = listOf("rpm", "ecuSpeed", "gear", "torque")

    /** Runs the clutch estimate on the latest readings and publishes the results. */
    fun feedClutch(model: ClutchModel) {
        val rpm = get("rpm") ?: return
        val kmh = get("ecuSpeed") ?: return
        val gear = get("gear") ?: return
        val nm = get("torque") ?: return
        val out = model.update(System.currentTimeMillis(), rpm, kmh, gear.toInt(), nm)
        put("clutchSlip", out.slipPct)
        put("clutchLoad", out.loadPct)
        put("clutchWarn", if (out.warn) 1.0 else 0.0)
    }

    fun clutchInfoJson(): String = JSONObject()
        .put("gears", org.json.JSONArray(clutch.learned().keys.sorted()))
        .toString()

    fun startSources() {
        if (setting("demo") == "1") {
            demo = DemoSource().also { it.start() }
            status["oh"] = "demo"
            status["ecu"] = "demo"
            return
        }
        if (setting("ohEnabled") == "1") {
            openHaldex = OpenHaldexSource(appContext, setting("ohHost")).also { it.start() }
        } else status["oh"] = "off"
        val adapter = setting("ecuAdapter")
        if (CalFlash.holdsDongle()) {
            status["ecu"] = "paused for flash"
        } else if (adapter == "bridge" || adapter == "elm") {
            ecu = EcuPoller(appContext, adapter, setting("ecuMac"), setting("bridgeName")).also { it.start() }
        } else status["ecu"] = "off"
    }

    fun stopSources() {
        openHaldex?.stop(); openHaldex = null
        ecu?.stop(); ecu = null
        demo?.stop(); demo = null
    }

    fun restartSources() {
        stopSources()
        startSources()
    }

    fun openHaldex(): OpenHaldexSource? = openHaldex
    fun ecu(): EcuPoller? = ecu

    /** Columns written by the data logger, in order. */
    val LOG_COLUMNS = listOf(
        "rpm", "boost", "map", "mapSp", "ambient", "iat", "coolant", "oil", "knockAvg",
        "knock1", "knock2", "knock3", "knock4", "timing", "wastegate", "put", "lambda", "afr", "stft", "ltft",
        "fpDi", "fpMpi", "pedal", "throttle", "speed", "ecuSpeed", "gear", "torque",
        "clutchSlip", "clutchLoad", "clutchWarn", "battery",
        "hxMode", "hxLock", "hxLockTarget", "hxClutchTemp", "hxModuleTemp", "hxVolts", "hxTempProt",
    )

    object Logger {
        @Volatile var active = false
            private set
        @Volatile var file: File? = null
            private set
        private var thread: Thread? = null

        fun dir(): File = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "logs").also { it.mkdirs() }

        fun sessions(): Int = dir().listFiles { f -> f.name.endsWith(".csv") }?.size ?: 0

        @Synchronized
        fun start() {
            if (active) return
            val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.UK).format(Date())
            val f = File(dir(), "s1dash_$name.csv")
            file = f
            active = true
            thread = Thread {
                FileWriter(f).use { w ->
                    w.write("time_ms," + LOG_COLUMNS.joinToString(",") + "\n")
                    val t0 = System.currentTimeMillis()
                    while (active) {
                        val row = StringBuilder().append(System.currentTimeMillis() - t0)
                        for (c in LOG_COLUMNS) {
                            row.append(',')
                            get(c)?.let { row.append(String.format(Locale.UK, "%.3f", it)) }
                        }
                        w.write(row.append('\n').toString())
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    }
                    w.flush()
                }
            }.also { it.name = "logger"; it.start() }
        }

        @Synchronized
        fun stop() {
            active = false
            thread?.interrupt()
            thread = null
        }
    }
}
