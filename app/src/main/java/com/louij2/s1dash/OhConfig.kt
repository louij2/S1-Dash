package com.louij2.s1dash

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * OpenHaldex config backups. A backup is the same file as the OpenHaldex page's
 * Export Config, so that page's Import can load one made here and the other way round.
 * The BLE pairing code is never kept; Wi-Fi passwords aren't readable from the controller.
 */
object OhConfig {
    /** The fork's BACKUP_GENERAL_KEYS (data/app.js): what POST /api/settings takes back. */
    val GENERAL_KEYS = listOf(
        "haldexGeneration", "isStandalone", "useCANifAvailable", "broadcastOpenHaldexOverCAN", "disableController",
        "espNowEnabled", "espNowControl", "canRelay",
        "disengageUnderSpeed", "disengageAboveSpeed", "disableThrottle",
        "tcForceMode", "tcForceModeValue", "hazardForceMode", "hazardForceModeValue",
        "extButtonForceMode", "extBtnForceModeValue", "forceModesPriority", "driveModeForce", "driveModeForceValues",
        "disableOnboardButton", "disableExternalButton",
        "followBrake", "invertBrake", "followHandbrake", "invertHandbrake",
        "fixHunting", "dangerZoneEnabled", "esp14MinFloorPct", "bpkCeilingNm",
        "steeringScaleEnabled", "lockReleaseEnabled", "lockReleaseRatePerSec", "liveDiagEnabled", "ledBrightness",
        "slipWheelbaseMm", "slipTrackFrontMm", "slipTrackRearMm", "slipSteeringRatio",
        "canSleepEnabled", "canSleepAggressive", "benchMode", "lpWakeThresholdFps",
        "longLearnNotes", "bleEnabled",
    )
    val TUNE_KEYS = listOf("throttleArray", "speedArray", "lockArray", "steeringArray", "steeringLockScaleArray")
    val SECRET_KEYS = listOf("blePasskey")
    const val PRODUCT = "OpenHaldex-C6"
    /** Firmware default after a reset (EEP: getUChar("haldexGen", 1)). */
    const val FACTORY_GENERATION = 1
    /** This many settings changing between two reads is a reset or a different controller, not someone tweaking. */
    const val MANY = 6

    data class Change(val key: String, val was: String, val now: String)
    enum class Verdict { SAME, SMALL, SUSPECT }

    /** The settings that make up the config, as comparable strings. Frame edits are one entry per bit. */
    fun fingerprint(s: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (k in GENERAL_KEYS + TUNE_KEYS) if (s.has(k)) out[k] = canon(s.get(k))
        val fb = s.optJSONArray("frameBlocks")
        if (fb != null) for (i in 0 until fb.length()) {
            val o = fb.optJSONObject(i) ?: continue
            if (o.has("bit")) out[("frameEdit " + o.optInt("bit") + " " + o.optString("name")).trim()] = o.optBoolean("enabled").toString()
        }
        return out
    }

    /** What differs, looking only at settings both have (a firmware update adds settings; that isn't a change). */
    fun changes(backup: JSONObject, live: JSONObject): List<Change> {
        val a = fingerprint(backup)
        val b = fingerprint(live)
        return a.keys.filter { it in b && a[it] != b[it] }.map { Change(it, a.getValue(it), b.getValue(it)) }
    }

    fun verdict(ch: List<Change>): Verdict = when {
        ch.isEmpty() -> Verdict.SAME
        ch.any { it.key == "haldexGeneration" } || ch.size >= MANY -> Verdict.SUSPECT
        else -> Verdict.SMALL
    }

    /** The backup file, laid out like the OpenHaldex page's Export Config. */
    fun backup(settings: JSONObject, apSsid: String?, apPwSet: Boolean, staSsid: String?, staPwSet: Boolean,
               at: String, device: String): JSONObject {
        val s = JSONObject(settings.toString())
        SECRET_KEYS.forEach { s.remove(it) }
        return JSONObject()
            .put("_product", PRODUCT)
            .put("_exportedAt", at)
            .put("_fwVersion", settings.opt("FW_VERSION") ?: JSONObject.NULL)
            .put("_by", "S1 Dash")
            .put("_device", device)
            .put("settings", s)
            .put("wifi", JSONObject().put("ssid", apSsid ?: JSONObject.NULL).put("passwordSet", apPwSet))
            .put("wifiSta", JSONObject().put("ssid", staSsid ?: JSONObject.NULL).put("passwordSet", staPwSet))
    }

    fun valid(b: JSONObject?): Boolean {
        val s = b?.optJSONObject("settings") ?: return false
        return b.optString("_product") == PRODUCT && TUNE_KEYS.take(3).all { s.optJSONArray(it) != null }
    }

    /**
     * The POSTs that put a backup back, in the OpenHaldex page's order: generation first
     * (so frame edits land on the right table), the tune, the settings, then frame edits one
     * bit at a time. Wi-Fi is separate ([wifiSteps]) because it restarts the controller's Wi-Fi.
     */
    fun restoreSteps(b: JSONObject): List<Pair<String, String>> {
        val s = b.getJSONObject("settings")
        val out = ArrayList<Pair<String, String>>()
        if (s.has("haldexGeneration")) out += "/api/settings" to JSONObject().put("haldexGeneration", s.get("haldexGeneration")).toString()
        val tune = JSONObject()
        TUNE_KEYS.take(3).forEach { tune.put(it, s.get(it)) }
        if (s.optJSONArray("steeringArray") != null && s.optJSONArray("steeringLockScaleArray") != null) {
            tune.put("steeringArray", s.get("steeringArray")).put("steeringLockScaleArray", s.get("steeringLockScaleArray"))
        }
        out += "/api/tune" to tune.toString()
        val general = JSONObject()
        GENERAL_KEYS.forEach { if (s.has(it)) general.put(it, s.get(it)) }
        out += "/api/settings" to general.toString()
        val fb = s.optJSONArray("frameBlocks")
        if (fb != null) for (i in 0 until fb.length()) {
            val o = fb.optJSONObject(i) ?: continue
            if (o.has("bit") && o.has("enabled")) out += "/api/settings" to JSONObject().put("frameEditBit", o.getInt("bit")).put("frameEditOn", o.getBoolean("enabled")).toString()
        }
        return out
    }

    /**
     * Wi-Fi names back, only where they differ from the controller now. A home Wi-Fi (bridge)
     * name needs its password with it; the access point keeps its password unless one is given.
     * A backup without a home Wi-Fi doesn't switch bridge mode off.
     */
    fun wifiSteps(b: JSONObject, liveAp: String?, liveSta: String?, apPw: String, staPw: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val sta = b.optJSONObject("wifiSta")?.let { if (it.isNull("ssid")) null else it.optString("ssid") }.orEmpty()
        if (sta.isNotEmpty() && (sta != liveSta || staPw.isNotEmpty())) out += "/api/wifi/sta" to JSONObject().put("ssid", sta).put("password", staPw).toString()
        val ap = b.optJSONObject("wifi")?.let { if (it.isNull("ssid")) null else it.optString("ssid") }.orEmpty()
        if (ap.isNotEmpty() && ap != liveAp) out += "/api/wifi/ssid" to JSONObject().put("ssid", ap).toString()
        if (apPw.isNotEmpty()) out += "/api/wifi" to JSONObject().put("password", apPw).toString()
        return out
    }

    /** One line per change for the warning, most telling first. */
    fun describe(ch: List<Change>, max: Int = 4): String {
        val sorted = ch.sortedBy { if (it.key == "haldexGeneration") 0 else if (it.key in TUNE_KEYS) 1 else 2 }
        val shown = sorted.take(max).joinToString(", ") {
            if (it.key in TUNE_KEYS) "${it.key} changed" else "${it.key} ${short(it.now)} (was ${short(it.was)})"
        }
        return if (ch.size > max) "$shown, and ${ch.size - max} more" else shown
    }

    private fun short(v: String) = if (v.length > 16) v.take(14) + "…" else v

    private fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { "$it:" + canon(v.opt(it)) }
        is Number -> try { BigDecimal(v.toString()).stripTrailingZeros().toPlainString() } catch (_: NumberFormatException) { v.toString() }
        else -> v.toString()
    }
}
