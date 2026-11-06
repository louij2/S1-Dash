package com.louij2.s1dash

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Five CAL slots and the hand-off to SimosTools. S1 Dash never writes to the ECU:
 * it checks the car, gives SimosTools the file and the dongle, and afterwards
 * checks the result. SimosTools does the flash and has the final say on the file.
 *
 * Trap: the dongle must stay free for the whole flash. The state is saved, so even
 * if Android kills this app while SimosTools is open, [holdsDongle] keeps the ECU
 * source off until the user says the flash is over.
 */
object CalFlash {
    const val SLOTS = 5
    private const val RETRY_MS = 3000L

    private lateinit var ctx: Context
    private val prefs get() = DataHub.prefs
    @Volatile var message = ""
    private var lastAsk = 0L
    private var idle = CalRules.IdleWatch()
    private var intact: Boolean? = null // hashing a CAL is too slow to repeat on every poll

    fun init(c: Context) { ctx = c.applicationContext }

    private var state: String
        get() = prefs.getString("flashState", "") ?: ""
        set(v) { prefs.edit().putString("flashState", v).commit() } // commit: must survive being killed straight after

    fun holdsDongle(): Boolean = state == "handoff"

    private fun dir() = File(ctx.filesDir, "cal").also { it.mkdirs() }
    private fun slotFile(n: Int) = File(dir(), "slot$n.bin")
    private fun meta(n: Int): JSONObject? = prefs.getString("calSlot$n", null)?.let { try { JSONObject(it) } catch (_: Exception) { null } }

    /** Reads the file first and leaves the slot alone if it isn't a usable CAL. */
    fun addFile(n: Int, uri: Uri) {
        try {
            val r = ctx.contentResolver
            val bytes = r.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Couldn't open it")
            val info = CalRules.inspect(bytes)
            if (info.problem != null) { message = info.problem; return }
            var name = "Slot ${n + 1}"
            r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0).substringBeforeLast('.')
            }
            slotFile(n).writeBytes(bytes)
            prefs.edit().putString("calSlot$n", JSONObject().put("name", name).put("size", info.size)
                .put("sha", info.sha256).put("box", info.boxCode).toString()).apply()
            message = info.warning ?: ""
        } catch (e: Exception) {
            message = "Couldn't add the file: ${e.message}"
        }
    }

    fun clearSlot(n: Int) {
        if (state != "" && prefs.getInt("flashSlot", -1) == n) return
        slotFile(n).delete()
        prefs.edit().remove("calSlot$n").apply()
    }

    /** Opens the checks for a slot. */
    fun prepare(n: Int) {
        if (meta(n) == null || holdsDongle()) return
        prefs.edit().putInt("flashSlot", n).apply()
        forgetEcuReadings()
        intact = null
        state = "check"
        message = ""
    }

    private fun forgetEcuReadings() {
        DataHub.ecuPart = null
        DataHub.ecuFlashLog = null
        DataHub.ecuDtcJson = "{}"
        lastAsk = 0L
    }

    /** Keeps asking the ECU, as the dongle may still be connecting. */
    private fun askEcu() {
        val now = System.currentTimeMillis()
        if (now - lastAsk < RETRY_MS || DataHub.status["ecu"]?.startsWith("connected") != true) return
        lastAsk = now
        val ecu = DataHub.ecu() ?: return
        if (DataHub.ecuPart == null) ecu.readIdentity() // once it answers, a missing flash record just isn't supported
        if (codes(DataHub.ecuDtcJson) == null) ecu.readDtcs()
    }

    private fun codes(json: String): Set<String>? = try {
        val o = JSONObject(json)
        if (o.optString("state") != "done") null else {
            val a = o.getJSONArray("dtcs")
            (0 until a.length()).map { a.getJSONObject(it).getString("code") }.toSet()
        }
    } catch (_: Exception) { null }

    private fun simosIntent(): Intent? = ctx.packageManager.getLaunchIntentForPackage("com.app.simostools")

    private fun slotIntact(n: Int): Boolean {
        val m = meta(n) ?: return false
        val f = slotFile(n)
        return f.exists() && CalRules.inspect(f.readBytes()).sha256 == m.optString("sha")
    }

    private fun preflight(n: Int) = CalRules.preflight(
        DataHub.setting("demo") == "1", simosIntent() != null, DataHub.status["ecu"],
        DataHub.get("rpm"), DataHub.get("ecuSpeed"), DataHub.get("battery"),
        DataHub.ecuPart, meta(n)?.optString("box") ?: "", codes(DataHub.ecuDtcJson) != null,
        intact ?: slotIntact(n).also { intact = it },
    )

    /** Rechecks everything here rather than trusting the button. Returns an error, or null once SimosTools is opening. */
    fun start(): String? {
        if (state != "check") return "Open a slot first"
        val n = prefs.getInt("flashSlot", -1)
        intact = null
        if (!CalRules.canFlash(preflight(n))) return "The checks aren't all good"
        val intent = simosIntent() ?: return "SimosTools isn't installed"
        val bytes = slotFile(n).readBytes()
        val fileName = "S1Dash-slot${n + 1}-" + (meta(n)?.optString("name") ?: "cal").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".bin"
        val exported = try { export(fileName, bytes) } catch (e: Exception) { return "Couldn't save the file for SimosTools: ${e.message}" }
        prefs.edit()
            .putString("flashFile", fileName)
            .putString("flashExport", exported)
            .putString("flashPart", DataHub.ecuPart)
            .putString("flashLog", DataHub.ecuFlashLog)
            .putString("flashCodes", DataHub.ecuDtcJson)
            .apply()
        state = "handoff"
        DataHub.restartSources() // holdsDongle() keeps the ECU source off from here
        openSimos(intent)
        return null
    }

    fun reopenSimos() { simosIntent()?.let { openSimos(it) } }

    private fun openSimos(intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves to Downloads, reads it back to prove the copy, and returns where it went. */
    private fun export(name: String, bytes: ByteArray): String {
        removeExport()
        val r = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }) ?: throw IllegalStateException("Downloads is unavailable")
            r.openOutputStream(uri)!!.use { it.write(bytes) }
            val back = r.openInputStream(uri)!!.use { it.readBytes() }
            if (!back.contentEquals(bytes)) { r.delete(uri, null, null); throw IllegalStateException("The saved copy differs") }
            return uri.toString()
        }
        @Suppress("DEPRECATION")
        val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        if (!f.readBytes().contentEquals(bytes)) { f.delete(); throw IllegalStateException("The saved copy differs") }
        return f.absolutePath
    }

    private fun removeExport() {
        val e = prefs.getString("flashExport", null) ?: return
        try {
            if (e.startsWith("content:")) ctx.contentResolver.delete(Uri.parse(e), null, null) else File(e).delete()
        } catch (_: Exception) {}
        prefs.edit().remove("flashExport").apply()
    }

    /** The user says the flash is over: only now does S1 Dash take the dongle back. */
    fun finished() {
        if (!holdsDongle()) return
        removeExport()
        forgetEcuReadings()
        idle = CalRules.IdleWatch()
        state = "verify"
        message = ""
        DataHub.restartSources()
    }

    /** Backs out. After a hand-off this means nothing was flashed. */
    fun cancel() {
        val held = holdsDongle()
        removeExport()
        state = ""
        message = ""
        if (held) DataHub.restartSources()
    }

    private fun verifyChecks(): List<CalRules.Check> {
        idle.update(System.currentTimeMillis(), DataHub.get("rpm"), DataHub.get("stft"), DataHub.get("ltft"), DataHub.get("knockAvg"))
        return CalRules.afterFlash(
            DataHub.status["ecu"], prefs.getString("flashPart", null), DataHub.ecuPart,
            prefs.getString("flashLog", null), DataHub.ecuFlashLog,
            codes(prefs.getString("flashCodes", "{}") ?: "{}"), codes(DataHub.ecuDtcJson), idle.check(),
        )
    }

    /** Records what the user confirmed. The ECU can't say which file it holds, so this is the only "last flashed" there is. */
    fun finalise() {
        if (state != "verify") return
        val n = prefs.getInt("flashSlot", -1)
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK).format(Date())
        val checks = verifyChecks()
        val summary = checks.joinToString("; ") { "${it.label}: ${it.state} ${it.detail}" }
        File(dir(), "history.txt").appendText("$when_  ${meta(n)?.optString("name")}  ${meta(n)?.optString("sha")}  $summary\n")
        prefs.edit().putString("calLastSha", meta(n)?.optString("sha")).putString("calLastWhen", when_).apply()
        state = ""
    }

    private fun checksJson(c: List<CalRules.Check>) = JSONArray().also { a ->
        c.forEach { a.put(JSONObject().put("label", it.label).put("state", it.state.name.lowercase()).put("detail", it.detail)) }
    }

    fun json(): String {
        val s = state
        val lastSha = prefs.getString("calLastSha", null)
        val slots = JSONArray()
        for (n in 0 until SLOTS) {
            val m = meta(n)
            val o = JSONObject().put("slot", n)
            if (m != null) {
                o.put("name", m.optString("name")).put("box", m.optString("box")).put("sha", m.optString("sha").take(8))
                if (m.optString("sha") == lastSha) o.put("last", prefs.getString("calLastWhen", ""))
            }
            slots.put(o)
        }
        val out = JSONObject().put("state", s).put("slots", slots).put("message", message)
        val n = prefs.getInt("flashSlot", -1)
        if (s != "") out.put("slot", n).put("name", meta(n)?.optString("name") ?: "")
        when (s) {
            "check" -> {
                askEcu()
                val c = preflight(n)
                out.put("checks", checksJson(c)).put("canFlash", CalRules.canFlash(c))
            }
            "handoff" -> out.put("file", prefs.getString("flashFile", ""))
            "verify" -> { askEcu(); out.put("checks", checksJson(verifyChecks())) }
        }
        return out.toString()
    }
}
