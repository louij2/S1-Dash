package com.louij2.s1dash

import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every change of the OpenHaldex and ECU status lines, with the time, so "it keeps reconnecting"
 * comes with the reasons (#1061). The last [KEEP] lines are shown in Settings; all of them go to
 * logs/connections.log next to the data logs.
 */
object ConnLog {
    private const val KEEP = 60
    private val lines = ArrayDeque<String>()
    private var thread: Thread? = null
    private val watched = listOf("oh" to "OpenHaldex", "ohAt" to "OpenHaldex address", "ecu" to "ECU")

    @Synchronized
    fun start() {
        if (thread != null) return
        thread = Thread({ watch() }, "conn-log").also { it.start() }
    }

    @Synchronized
    fun stop() {
        thread?.interrupt()
        thread = null
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun add(what: String) {
        val line = SimpleDateFormat("dd MMM HH:mm:ss", Locale.UK).format(Date()) + "  " + what
        lines.addLast(line)
        while (lines.size > KEEP) lines.removeFirst()
        try {
            FileWriter(File(DataHub.Logger.dir(), "connections.log"), true).use { it.write(line + "\n") }
        } catch (_: Exception) {}
    }

    private fun watch() {
        val last = HashMap<String, String?>()
        add("sources started")
        try {
            while (!Thread.currentThread().isInterrupted) {
                for ((key, name) in watched) {
                    val now = DataHub.status[key]
                    if (last.containsKey(key) && last[key] == now) continue
                    if (now != null || last[key] != null) add("$name: ${now ?: "(none)"}")
                    last[key] = now
                }
                Thread.sleep(250)
            }
        } catch (_: InterruptedException) {
        }
        add("sources stopped")
    }
}
