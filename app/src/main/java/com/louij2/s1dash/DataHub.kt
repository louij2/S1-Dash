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
        "ohHost" to "auto",           // auto = find OpenHaldex by name (#992); or a typed address
        "ecuAdapter" to "none",       // none | bridge (SimosTools dongle) | elm (ELM327, e.g. the wheel's dongle)
        "ecuMac" to "",               // adapter MAC; for the bridge, empty = find it by name
        "bridgeName" to "BLE_TO_ISOTP20",
        "bridgePassword" to "",       // the bridge's adapter password as set in SimosTools; empty = its default
        "dtcIndicator" to "1",
        "boostTarget" to "1.8",       // expected peak boost, bar, marked on the dial
        "autoLog" to "off",           // off | cruise | pedal | either: record on its own, like SimosTools (#1038)
        "carplayPkg" to "",           // the head unit's CarPlay app, picked in Settings (#1060)
        "carplayName" to "",
        "returnBubble" to "1",        // floating "S1" button over other apps to come back
        "stayConnected" to "1",       // keep OpenHaldex and the dongle going behind CarPlay (#1061)
        "demo" to "0",
        "uiScale" to "100",           // percent of the largest size that fits the screen
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
        if (key == "autoLog") AutoLogger.restart()
        if (key == "stayConnected") KeepAliveService.apply(appContext)
        if (key in setOf("ohEnabled", "ohHost", "ecuAdapter", "ecuMac", "bridgeName", "bridgePassword", "demo")) restartSources()
    }

    fun put(key: String, v: Double?) {
        if (v == null || v.isNaN() || v.isInfinite()) return
        values[key] = v
        stamps[key] = System.currentTimeMillis()
        if (key == "boost" && v > boostPeak) boostPeak = v
    }

    /** Values OpenHaldex can stand in for when the ECU dongle isn't connected. The ECU wins when both exist. */
    private val FALLBACK = mapOf("oil" to "ohOil", "coolant" to "ohCoolant", "boost" to "ohBoost")

    /** Hold times for sources other than the ECU (OpenHaldex); dropEcuValues leaves these alone. */
    private val sourceHolds = ConcurrentHashMap<String, Long>()

    fun holdLonger(keys: List<String>, ms: Long) { for (k in keys) sourceHolds[k] = ms }

    private fun holdFor(key: String) = holds[key] ?: sourceHolds[key] ?: STALE_MS

    private fun fresh(key: String): Double? {
        val t = stamps[key] ?: return null
        return if (System.currentTimeMillis() - t <= holdFor(key)) values[key] else null
    }

    fun wantAllValues() = focusAll || Logger.active || AutoLogger.recording

    /** Values to read every cycle on top of the core dials: the auto-record trigger's, while it's armed. */
    fun extraFastKeys(): Set<String> = if (AutoLogger.armed) LogTrigger.KEYS else emptySet()

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
            .put("logging", Logger.active || AutoLogger.recording)
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

    private val sourceOwners = HashSet<String>()

    /** The phone screen and Android Auto both need the data, so it runs until the last of them lets go. */
    @Synchronized
    fun acquireSources(owner: String) {
        val first = sourceOwners.isEmpty()
        sourceOwners += owner
        if (first) startSources()
    }

    @Synchronized
    fun releaseSources(owner: String) {
        if (sourceOwners.remove(owner) && sourceOwners.isEmpty()) stopSources()
    }

    fun startSources() {
        ConnLog.start()
        AutoLogger.start()
        if (setting("demo") == "1") {
            status["g"] = "demo"
            demo = DemoSource().also { it.start() }
            status["oh"] = "demo"
            status["ecu"] = "demo"
            return
        }
        if (setting("ohEnabled") == "1") {
            openHaldex = OpenHaldexSource(appContext, setting("ohHost")).also { it.start() }
        } else status["oh"] = "off"
        gSensor = GSensor(appContext).also { it.start() }
        startEcu()
    }

    private var gSensor: GSensor? = null

    private fun startEcu() {
        val adapter = setting("ecuAdapter")
        if (CalFlash.holdsDongle()) {
            status["ecu"] = "paused for flash"
        } else if (adapter == "bridge" || adapter == "elm") {
            ecu = EcuPoller(appContext, adapter, setting("ecuMac"), setting("bridgeName")).also { it.start() }
        } else status["ecu"] = "off"
    }

    fun stopSources() {
        AutoLogger.stop()
        ConnLog.stop()
        openHaldex?.stop(); openHaldex = null
        ecu?.stop(); ecu = null
        demo?.stop(); demo = null
        gSensor?.stop(); gSensor = null
        ecuReleased = false
    }

    @Volatile private var ecuReleased = false

    /**
     * The phone screen went out of sight. The SimosTools bridge takes one connection, so let go of
     * the dongle for SimosTools (#967), unless Android Auto is showing the data or the logger is running.
     */
    @Synchronized
    fun screenHidden(owner: String) {
        if (sourceOwners != setOf(owner) || Logger.active || ecu == null) return
        ecu?.stop(); ecu = null
        ecuReleased = true
        dropEcuValues()
        status.remove("simos")
        status["ecu"] = "released while S1 Dash is in the background"
    }

    /** Back on screen: take the dongle again if it was let go in [screenHidden]. */
    @Synchronized
    fun screenShown() {
        if (!ecuReleased) return
        ecuReleased = false
        startEcu()
    }

    /** The notification's Disconnect button: everything stops until S1 Dash is opened again. */
    @Volatile var disconnectedByUser = false
        private set

    @Synchronized
    fun disconnectByUser() {
        disconnectedByUser = true
        stopSources()
        status["oh"] = "disconnected"
        status["ecu"] = "disconnected"
    }

    /** Back on screen after a Disconnect: connect again. */
    @Synchronized
    fun reconnectAfterUser() {
        if (!disconnectedByUser) return
        disconnectedByUser = false
        if (sourceOwners.isNotEmpty()) startSources()
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
        "putSp", "lambdaSp", "torqueReq", "fpDiSp", "fpMpiSp", "lpfpDuty", "cruise", "boostDev", "lambdaDev", "fpDiDev", "fpMpiDev",
        "hxMode", "hxLock", "hxLockTarget", "hxClutchTemp", "hxModuleTemp", "hxVolts", "hxTempProt",
        "gLat", "gLon",
    )

    private fun logHeader() = "time_ms," + LOG_COLUMNS.joinToString(",") + "\n"

    /** One CSV row of the logged columns, without the time. */
    private fun logValues(): String {
        val row = StringBuilder()
        for (c in LOG_COLUMNS) {
            row.append(',')
            get(c)?.let { row.append(String.format(Locale.UK, "%.3f", it)) }
        }
        return row.toString()
    }

    private fun logName(prefix: String): String = prefix + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.UK).format(Date()) + ".csv"

    /**
     * Records on its own when the [LogTrigger] says so (#1038). Keeps the last 2 s of rows in memory,
     * so a recording starts with the lead-in, and closes it 2 s after the trigger lets go.
     * Stands aside while a manual recording runs.
     */
    object AutoLogger {
        @Volatile var recording = false
            private set
        @Volatile var armed = false
            private set
        private var thread: Thread? = null

        @Volatile private var running = false

        @Synchronized
        fun start() { running = true; restart() }

        @Synchronized
        fun stop() { running = false; thread?.interrupt(); thread = null; recording = false; armed = false }

        @Synchronized
        fun restart() {
            thread?.interrupt(); thread = null
            recording = false
            armed = false
            if (!running) return
            val trigger = LogTrigger(setting("autoLog"))
            armed = trigger.armed
            if (!armed) return
            thread = Thread({ run(trigger) }, "auto-logger").also { it.start() }
        }

        private fun run(trigger: LogTrigger) {
            val lead = ArrayDeque<Pair<Long, String>>()
            var out: FileWriter? = null
            var t0 = 0L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val now = System.currentTimeMillis()
                    val row = logValues()
                    val on = !Logger.active && trigger.recording(now, get("cruise"), get("pedal"))
                    if (on && out == null) {
                        out = FileWriter(File(Logger.dir(), logName("s1dash_auto_")))
                        out.write(logHeader())
                        t0 = lead.firstOrNull()?.first ?: now
                        for ((t, r) in lead) out.write((t - t0).toString() + r + "\n")
                        lead.clear()
                        recording = true
                    }
                    if (out != null) {
                        out.write((now - t0).toString() + row + "\n")
                        if (!on) { out.close(); out = null; recording = false }
                    } else {
                        lead.addLast(now to row)
                        while (lead.isNotEmpty() && now - lead.first().first > LogTrigger.LEAD_MS) lead.removeFirst()
                    }
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
            } catch (_: java.io.IOException) {
            } finally {
                try { out?.close() } catch (_: Exception) {}
                recording = false
            }
        }
    }

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
            val f = File(dir(), logName("s1dash_"))
            file = f
            active = true
            thread = Thread {
                FileWriter(f).use { w ->
                    w.write(logHeader())
                    val t0 = System.currentTimeMillis()
                    while (active) {
                        w.write((System.currentTimeMillis() - t0).toString() + logValues() + "\n")
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
