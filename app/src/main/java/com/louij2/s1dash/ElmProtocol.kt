package com.louij2.s1dash

import java.io.InputStream
import java.io.OutputStream

interface ElmClock { fun now(): Long; fun sleep(ms: Long) }
object SystemClock : ElmClock {
    override fun now() = System.currentTimeMillis()
    override fun sleep(ms: Long) = Thread.sleep(ms)
}

/**
 * Talks to an ELM327 over any byte stream, and copes with what clones do: echo and
 * spaces that won't turn off, stray null characters, slow resets, missing commands,
 * late replies and multi-frame replies cut short. Tested against FakeElm.
 */
class ElmProtocol(private val input: InputStream, private val output: OutputStream, private val clock: ElmClock = SystemClock) {
    @Volatile var lastError = ""
        private set
    /** A digit after the request tells the ELM to stop at one frame, instead of waiting out its timeout. */
    var usesHint = true
        private set
    private var canSetTimeout = false
    private var outOfStep = false

    fun open(): Boolean {
        lastError = ""
        command("ATZ", RESET_MS)
        clock.sleep(200)
        drain()
        outOfStep = false
        listOf("ATE0", "ATL0", "ATS0", "ATH0").forEach { command(it, COMMAND_MS) }
        for (c in listOf("ATSP6", "ATSH7E0", "ATCAF1")) {
            val r = command(c, COMMAND_MS)
            if (r == null) { lastError = "Adapter stopped answering"; return false }
            if (r.none { it.contains("OK") }) { lastError = "Adapter refused ${c}"; return false }
        }
        // Useful, but the ELM still works without them.
        command("ATAT1", COMMAND_MS)
        command("ATCRA7E8", COMMAND_MS)
        canSetTimeout = accepted("ATST32")
        // Flow control to the ECU, for multi-frame replies. These only take effect as a set.
        if (accepted("ATFCSH7E0") && accepted("ATFCSD300000")) command("ATFCSM1", COMMAND_MS)
        return true
    }

    private fun accepted(c: String) = command(c, COMMAND_MS)?.any { it.contains("OK") } == true

    /** [singleFrame] promises the reply fits one CAN frame, which allows the much quicker hinted form. */
    fun request(req: ByteArray, timeoutMs: Long = 600, singleFrame: Boolean = false): ByteArray? {
        val hex = req.joinToString("") { String.format("%02X", it) }
        val long = timeoutMs > SHORT_MS && canSetTimeout
        if (long) command("ATSTFF", COMMAND_MS) // otherwise the ELM gives up after 200 ms, whatever we wait
        val r = exchange(hex, timeoutMs, singleFrame && usesHint)
        if (long) command("ATST32", COMMAND_MS)
        return r
    }

    private fun exchange(hex: String, timeoutMs: Long, hinted: Boolean): ByteArray? {
        val deadline = clock.now() + timeoutMs
        val lines = command(if (hinted) hex + "1" else hex, timeoutMs) ?: return null
        if (hinted && lines.any { it == "?" }) {
            usesHint = false
            return exchange(hex, timeoutMs, false)
        }
        val first = parse(lines)
        if (first.data != null || !first.pending) return first.data
        // Only "please wait" so far: keep listening.
        val left = deadline - clock.now()
        if (left <= 0) return null
        return parse(readUntilPrompt(left) ?: return null).data
    }

    private fun drain() {
        while (input.available() > 0) input.read()
    }

    private fun command(cmd: String, timeoutMs: Long): List<String>? = try {
        if (outOfStep) { // the last reply may still be coming: let it finish rather than read it as this one's
            readUntilPrompt(SETTLE_MS)
            outOfStep = false
        }
        drain()
        output.write((cmd + "\r").toByteArray())
        output.flush()
        readUntilPrompt(timeoutMs)?.filter { strip(it) != strip(cmd) }
    } catch (_: Exception) { null }

    private fun strip(s: String) = s.replace(" ", "").uppercase()

    private fun readUntilPrompt(timeoutMs: Long): List<String>? {
        val sb = StringBuilder()
        val end = clock.now() + timeoutMs
        while (clock.now() < end) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0) return null
                if (c == '>'.code) return sb.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (c == '\r'.code || c == '\n'.code || c in 0x20..0x7E) sb.append(c.toChar()) // ELMs may send stray nulls
            } else clock.sleep(5)
        }
        outOfStep = true
        return null
    }

    private class Parsed(val data: ByteArray?, val pending: Boolean)

    /** ELM text -> bytes: single frames, the "len / 0: / 1:" multi-frame format, and any "please wait" frames among them. */
    private fun parse(lines: List<String>): Parsed {
        val text = lines.map { strip(it) }.filter { it != "OK" && !it.startsWith("SEARCHING") }
        if (text.any { l -> l == "?" || BAD.any { l.contains(it) } }) return Parsed(null, false)
        val pendingRe = Regex("7F[0-9A-F]{2}78")
        val pending = text.any { pendingRe.matches(it) }
        val rest = text.filter { !pendingRe.matches(it) }
        val multi = rest.any { it.length > 2 && it[1] == ':' }
        var expected = -1
        val hex = StringBuilder()
        for (s in rest) {
            when {
                multi && s.length == 3 && s.all(::isHex) && expected < 0 -> expected = s.toInt(16)
                multi && s.length > 2 && s[1] == ':' && s.substring(2).all(::isHex) -> hex.append(s.substring(2))
                !multi && s.all(::isHex) && hex.isEmpty() -> hex.append(s)
            }
        }
        if (hex.length < 2 || hex.length % 2 != 0) return Parsed(null, pending)
        var bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        if (multi && expected > bytes.size) { // cut short: a partial fault list must not pass as the whole list
            lastError = "Adapter cut off a long reply"
            return Parsed(null, false)
        }
        if (expected in 1 until bytes.size) bytes = bytes.copyOf(expected)
        return Parsed(bytes, pending)
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'A'..'F'

    private companion object {
        const val RESET_MS = 6000L
        const val COMMAND_MS = 1000L
        const val SHORT_MS = 600L
        const val SETTLE_MS = 1000L
        val BAD = listOf("NODATA", "ERROR", "STOPPED", "UNABLE", "BUFFERFULL", "BUSBUSY", "BUSINIT")
    }
}
