package com.louij2.s1dash

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min

/** The decisions behind the ECU files screen, kept free of Android so they can be unit tested. */
object CalRules {
    const val CAL_SIZE = 0x7FC00          // Simos18.1 CAL block
    const val MIN_SIZE = 500_000
    const val MAX_SIZE = 1_000_000        // a full bin is about 4 MB, and isn't a slot
    const val MIN_VOLTS = 12.0
    const val GOOD_VOLTS = 12.6

    enum class State { PASS, WARN, FAIL, WAIT }
    data class Check(val label: String, val state: State, val detail: String)
    data class CalInfo(val size: Int, val sha256: String, val boxCode: String, val problem: String?, val warning: String?)

    fun inspect(b: ByteArray): CalInfo {
        val sha = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        val box = if (b.size >= 0x6B) String(b, 0x60, 0x0B, Charsets.ISO_8859_1).trim() else ""
        val readable = box.isNotEmpty() && box.all { it in ' '..'~' }
        val problem = when {
            b.size > MAX_SIZE -> "Too big for a CAL file. Slots hold a CAL only, not a full bin."
            b.size < MIN_SIZE -> "Too small to be a CAL file."
            else -> null
        }
        val warning = when {
            problem != null -> null
            b.size != CAL_SIZE -> "Not the usual CAL size."
            !readable -> "No software number found in the file."
            else -> null
        }
        return CalInfo(b.size, sha, if (readable) box else "", problem, warning)
    }

    /** "8X0906259 B" -> "8X0906259". SimosTools allows a CAL across suffixes of one family, so only this part must match. */
    fun family(code: String): String = code.replace(" ", "").take(9).uppercase()

    fun softwareCheck(ecu: String?, file: String): Check {
        val label = "Software number"
        if (ecu == null) return Check(label, State.WAIT, "Reading the ECU")
        if (file.isEmpty()) return Check(label, State.WARN, "ECU $ecu, none found in the file")
        val e = ecu.replace(" ", "").uppercase()
        val f = file.replace(" ", "").uppercase()
        return when {
            family(e) != family(f) -> Check(label, State.FAIL, "File is for $file, the ECU is $ecu")
            e != f -> Check(label, State.WARN, "File $file, ECU $ecu. SimosTools has the final say")
            else -> Check(label, State.PASS, ecu)
        }
    }

    fun preflight(
        demo: Boolean, simosInstalled: Boolean, ecuStatus: String?, rpm: Double?, speed: Double?, volts: Double?,
        ecuPart: String?, fileBox: String, codesSaved: Boolean, fileIntact: Boolean,
    ): List<Check> {
        val out = ArrayList<Check>()
        if (demo) out += Check("Demo mode", State.FAIL, "Turn off demo mode first")
        if (!simosInstalled) out += Check("SimosTools", State.FAIL, "Not installed on this head unit")
        if (!fileIntact) out += Check("Slot file", State.FAIL, "Changed since it was added. Add it again")
        out += when {
            ecuStatus == null || !ecuStatus.startsWith("connected") ->
                Check("ECU connection", State.WAIT, "Ignition on, dongle in: ${ecuStatus ?: "not connected"}")
            !ecuStatus.contains("SimosTools") -> Check("ECU connection", State.FAIL, "Flashing needs the SimosTools dongle")
            else -> Check("ECU connection", State.PASS, "Connected")
        }
        out += when {
            rpm == null -> Check("Engine off", State.WAIT, "No reading yet")
            rpm >= 50 -> Check("Engine off", State.FAIL, "Engine is running")
            else -> Check("Engine off", State.PASS, "Stopped")
        }
        out += when {
            speed == null -> Check("Parked", State.WAIT, "No reading yet")
            speed > 0.5 -> Check("Parked", State.FAIL, "Car is moving")
            else -> Check("Parked", State.PASS, "Not moving")
        }
        out += when {
            volts == null -> Check("Battery", State.WAIT, "No reading yet")
            volts < MIN_VOLTS -> Check("Battery", State.FAIL, "%.1f V. Connect a charger".format(volts))
            volts < GOOD_VOLTS -> Check("Battery", State.WARN, "%.1f V. A charger is safer".format(volts))
            else -> Check("Battery", State.PASS, "%.1f V".format(volts))
        }
        out += softwareCheck(ecuPart, fileBox)
        out += if (codesSaved) Check("Fault codes saved", State.PASS, "SimosTools clears them while flashing")
        else Check("Fault codes saved", State.WAIT, "Reading the ECU")
        return out
    }

    fun canFlash(checks: List<Check>) = checks.none { it.state == State.FAIL || it.state == State.WAIT }

    /** Watches the first idle after a flash. Fed from the UI's poll, so gaps in time are capped. */
    class IdleWatch {
        var stableMs = 0L; private set
        var stalls = 0; private set
        var ran = false; private set
        var worstTrim = 0.0; private set
        var worstKnock = 0.0; private set
        private var lastMs = 0L
        private var running = false

        fun update(nowMs: Long, rpm: Double?, stft: Double?, ltft: Double?, knock: Double?) {
            val dt = if (lastMs == 0L) 0L else min(nowMs - lastMs, 1000L)
            lastMs = nowMs
            if (rpm == null) return
            if (rpm >= RUNNING_RPM) {
                running = true
                ran = true
                if (rpm in IDLE_MIN..IDLE_MAX) stableMs += dt else stableMs = 0
                stft?.let { worstTrim = maxOf(worstTrim, abs(it)) }
                ltft?.let { worstTrim = maxOf(worstTrim, abs(it)) }
                knock?.let { worstKnock = minOf(worstKnock, it) }
            } else if (rpm < STOPPED_RPM) {
                if (running) stalls++
                running = false
                stableMs = 0
            }
        }

        fun check(): Check {
            val label = "Idle"
            return when {
                !ran -> Check(label, State.WAIT, "Start the engine and leave it idling")
                stableMs < STABLE_MS && stalls == 0 -> Check(label, State.WAIT, "Watching: ${stableMs / 1000} of ${STABLE_MS / 1000} s, don't touch the pedal")
                stableMs < STABLE_MS -> Check(label, State.WARN, "Stopped $stalls time(s). Watching again: ${stableMs / 1000} of ${STABLE_MS / 1000} s")
                stalls > 0 -> Check(label, State.WARN, "Steady now, but stopped $stalls time(s) first")
                worstTrim > MAX_TRIM -> Check(label, State.WARN, "Steady, but fuel trim reached %.0f %%".format(worstTrim))
                worstKnock <= MAX_KNOCK -> Check(label, State.WARN, "Steady, but knock reached %.1f°".format(worstKnock))
                else -> Check(label, State.PASS, "Steady for ${STABLE_MS / 1000} s")
            }
        }

        companion object {
            const val RUNNING_RPM = 400.0
            const val STOPPED_RPM = 100.0
            const val IDLE_MIN = 500.0
            const val IDLE_MAX = 1300.0
            const val STABLE_MS = 15_000L
            const val MAX_TRIM = 25.0
            const val MAX_KNOCK = -3.0
        }
    }

    fun afterFlash(
        ecuStatus: String?, partBefore: String?, partAfter: String?, logBefore: String?, logAfter: String?,
        codesBefore: Set<String>?, codesAfter: Set<String>?, idle: Check,
    ): List<Check> {
        val out = ArrayList<Check>()
        val connected = ecuStatus != null && ecuStatus.startsWith("connected")
        out += if (connected) Check("ECU answers", State.PASS, "Connected")
        else Check("ECU answers", State.WAIT, "Close SimosTools so it lets go of the dongle: ${ecuStatus ?: "not connected"}")
        out += when {
            partAfter == null -> Check("Software number", State.WAIT, "Reading the ECU")
            partBefore != null && partBefore != partAfter -> Check("Software number", State.FAIL, "Was $partBefore, now $partAfter")
            else -> Check("Software number", State.PASS, partAfter)
        }
        out += when {
            logAfter == null -> Check("Flash recorded", State.WAIT, "Reading the ECU")
            logAfter.isEmpty() || logBefore.isNullOrEmpty() -> Check("Flash recorded", State.WARN, "The ECU's flash record couldn't be read")
            logAfter == logBefore -> Check("Flash recorded", State.WARN, "Same record as before. Did the flash finish?")
            else -> Check("Flash recorded", State.PASS, "New record on the ECU")
        }
        out += when {
            codesAfter == null -> Check("Fault codes", State.WAIT, "Reading the ECU")
            else -> {
                val fresh = codesAfter - (codesBefore ?: emptySet())
                if (fresh.isEmpty()) Check("Fault codes", State.PASS, "None new")
                else Check("Fault codes", State.WARN, "New: " + fresh.sorted().joinToString(", "))
            }
        }
        out += idle
        return out
    }
}
