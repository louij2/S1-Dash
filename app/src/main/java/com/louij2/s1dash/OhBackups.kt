package com.louij2.s1dash

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps OpenHaldex config backups on this device (the last [KEEP]) and, when a backup
 * server is set, a copy there. A new backup is made when the settings change a little;
 * a big change (another generation, or many settings at once, as after a factory reset)
 * isn't saved over the good one: S1 Dash warns and offers to restore instead.
 */
object OhBackups {
    const val KEEP = 30
    private const val UPLOADED = "ohBackupsUploaded"
    private const val TIMEOUT_MS = 4000

    class Wifi(val apSsid: String?, val apPwSet: Boolean, val staSsid: String?, val staPwSet: Boolean)

    @Volatile private var keepNext = false
    @Volatile private var triedServer = false
    @Volatile private var serverRows: JSONArray = JSONArray()
    private val uploading = AtomicBoolean(false)

    private val ctx: Context get() = DataHub.appContext
    private fun dir() = File(ctx.filesDir, "openhaldex").also { it.mkdirs() }
    private fun server() = DataHub.setting("backupServer").trim().trimEnd('/')

    fun files(): List<File> =
        dir().listFiles { f -> f.name.startsWith("openhaldex-") && f.name.endsWith(".json") }?.sortedByDescending { it.name } ?: emptyList()

    private fun read(f: File): JSONObject? = try { JSONObject(f.readText()).takeIf { OhConfig.valid(it) } } catch (_: Exception) { null }

    fun latest(): JSONObject? = files().firstNotNullOfOrNull { read(it) }

    /**
     * After each read of /api/settings. [wifi] reads the Wi-Fi names, asked for only when a
     * backup is being made. Sets status ohCfg to a warning, or "" when the config is as backed up.
     */
    fun check(settings: JSONObject, wifi: () -> Wifi?) {
        val last = latest() ?: fromServer()
        if (last == null) { save(settings, wifi()); clearWarning(); return }
        val ch = OhConfig.changes(last.getJSONObject("settings"), settings)
        when {
            keepNext -> { keepNext = false; save(settings, wifi()); clearWarning() }
            OhConfig.verdict(ch) == OhConfig.Verdict.SAME -> clearWarning()
            OhConfig.verdict(ch) == OhConfig.Verdict.SMALL -> { save(settings, wifi()); clearWarning() }
            else -> DataHub.status["ohCfg"] = JSONObject()
                .put("at", last.optString("_exportedAt"))
                .put("changes", OhConfig.describe(ch))
                .put("count", ch.size)
                .put("factory", settings.optInt("haldexGeneration", -1) == OhConfig.FACTORY_GENERATION)
                .toString()
        }
        upload()
    }

    /** The user says the controller's settings now are right: the next read is saved as the backup. */
    fun keepNext() { keepNext = true }

    private fun clearWarning() { DataHub.status["ohCfg"] = "" }

    /** Saves a backup; returns its file. */
    fun save(settings: JSONObject, wifi: Wifi?): File {
        val now = Date()
        val b = OhConfig.backup(settings, wifi?.apSsid, wifi?.apPwSet == true, wifi?.staSsid, wifi?.staPwSet == true,
            iso(now, "yyyy-MM-dd'T'HH:mm:ss'Z'"), "S1 Dash on ${Build.MANUFACTURER} ${Build.MODEL}")
        val f = File(dir(), "openhaldex-" + iso(now, "yyyy-MM-dd'T'HH-mm-ss'Z'") + ".json")
        val tmp = File(f.path + ".tmp")
        tmp.writeText(b.toString(2))
        tmp.renameTo(f)
        files().drop(KEEP).forEach { it.delete() }
        ConnLog.add("OpenHaldex config backed up (${f.name})")
        upload()
        return f
    }

    /** A copy in Downloads, for the OpenHaldex page's Import. Returns where it went. */
    fun toDownloads(f: File): String {
        val bytes = f.readBytes()
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
            }) ?: throw IllegalStateException("Downloads is unavailable")
            r.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Downloads/${f.name}"
        }
        @Suppress("DEPRECATION")
        val out = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), f.name)
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        return out.absolutePath
    }

    /** Backups here and on the server, newest first, for the restore list. */
    fun listJson(): String {
        val up = uploaded()
        val out = JSONArray()
        val seen = HashSet<String>()
        for (f in files()) {
            val b = read(f) ?: continue
            seen += f.name
            out.put(row(f.name, b.optString("_exportedAt"), b.optString("_fwVersion"),
                b.getJSONObject("settings").optInt("haldexGeneration", -1), if (f.name in up) "both" else "here"))
        }
        val rows = serverRows
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (r.optString("name") in seen) continue
            out.put(row(r.optString("name"), r.optString("exportedAt"), r.optString("fw"), r.optInt("generation", -1), "server"))
        }
        val sorted = (0 until out.length()).map { out.getJSONObject(it) }.sortedByDescending { it.optString("at") }
        return JSONObject().put("server", server().isNotEmpty()).put("list", JSONArray(sorted)).toString()
    }

    private fun row(name: String, at: String, fw: String, gen: Int, where: String) =
        JSONObject().put("name", name).put("at", at).put("fw", fw).put("gen", gen).put("where", where)

    /** A backup by name, from this device or the server. */
    fun load(name: String): JSONObject? {
        if (!name.matches(Regex("openhaldex-[0-9TZ-]+(-[0-9a-f]{12})?\\.json"))) return null
        File(dir(), name).takeIf { it.exists() }?.let { return read(it) }
        val body = http("GET", "${server()}/api/openhaldex/$name", null) ?: return null
        return try { JSONObject(body.second).takeIf { OhConfig.valid(it) } } catch (_: Exception) { null }
    }

    /** Reads the server's list in the background, for the restore list. */
    fun refreshServer() {
        if (server().isEmpty()) return
        Thread({
            http("GET", "${server()}/api/openhaldex", null)?.let { (code, body) ->
                if (code == 200) try { serverRows = JSONArray(body) } catch (_: Exception) {}
            }
        }, "oh-backup-list").start()
    }

    /** With nothing on this device (a new install), the newest backup on the server is the one to compare with. */
    private fun fromServer(): JSONObject? {
        if (triedServer || server().isEmpty()) return null
        triedServer = true
        val (code, body) = http("GET", "${server()}/api/openhaldex", null) ?: return null
        if (code != 200) return null
        val name = try { JSONArray(body).optJSONObject(0)?.optString("name") } catch (_: Exception) { null } ?: return null
        val b = load(name) ?: return null
        File(dir(), name).writeText(b.toString(2))
        markUploaded(name)
        return b
    }

    /** Sends backups the server doesn't have yet. Quietly tries again on the next settings read. */
    fun upload() {
        if (server().isEmpty() || files().all { it.name in uploaded() } || !uploading.compareAndSet(false, true)) return
        Thread({
            try {
                val up = uploaded()
                for (f in files().reversed()) {
                    if (f.name in up) continue
                    val r = http("POST", "${server()}/api/openhaldex", f.readText()) ?: break
                    if (r.first == 200 || r.first == 201) markUploaded(f.name)
                    else if (r.first == 422) markUploaded(f.name) // the server won't ever take it; don't keep trying
                }
            } finally { uploading.set(false) }
        }, "oh-backup-upload").start()
    }

    private fun uploaded(): Set<String> = DataHub.prefs.getStringSet(UPLOADED, emptySet()) ?: emptySet()

    private fun markUploaded(name: String) {
        val keep = files().map { it.name }.toSet()
        DataHub.prefs.edit().putStringSet(UPLOADED, (uploaded() + name).filter { it in keep || it == name }.toSet()).apply()
    }

    /** Over a network that has the internet: the OpenHaldex Wi-Fi doesn't. */
    private fun http(method: String, url: String, body: String?): Pair<Int, String>? = try {
        val u = URL(url)
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val net = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
        }
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = c.responseCode
            code to (try { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() } catch (_: Exception) { null }).orEmpty()
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun iso(d: Date, pattern: String) = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(d)
}
