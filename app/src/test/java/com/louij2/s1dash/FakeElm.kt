package com.louij2.s1dash

import java.io.InputStream
import java.io.OutputStream

class SimClock : ElmClock {
    var t = 1_000L
    override fun now() = t
    override fun sleep(ms: Long) { t += ms }
}

/**
 * A pretend ELM327 and Simos18 on virtual time, following the ELM327 datasheet: it
 * keeps listening for ATST after each reply unless adaptive timing or a response
 * count ends it sooner. Clone quirks are switched on one by one.
 */
class FakeElm(private val clock: SimClock, val p: Profile) {
    data class Profile(
        val name: String,
        val version: String = "ELM327 v2.1",
        val atzMs: Long = 500,
        val latencyMs: Long = 25,            // Bluetooth plus the adapter, each way together
        val adaptive: Boolean = true,        // cuts the wait after a reply; many clones don't
        val hint: Boolean = true,            // "22F40C1" = stop after one frame
        val rejects: Set<String> = emptySet(),
        val echoStuck: Boolean = false,
        val spacesStuck: Boolean = false,
        val nulNoise: Boolean = false,
        val multiFrame: Boolean = true,      // false = clone drops everything after the first frame
        val clearMs: Long = 100,
        val pendingFirst: Boolean = false,   // ECU sends 7F xx 78 before slow answers
        val lateEvery: Int = 0,              // every Nth reply turns up very late
    )

    val requests = ArrayList<String>()
    private val rx = ArrayDeque<Pair<Long, Int>>()
    private val cmd = StringBuilder()
    private var echo = true
    private var spaces = true
    private var st = 200L
    private var count = 0
    var codes = listOf("P3019-00", "U0155-00", "P0300-00")

    val input = object : InputStream() {
        override fun available(): Int = rx.count { it.first <= clock.t }
        override fun read(): Int {
            val h = rx.firstOrNull() ?: return -1
            if (h.first > clock.t) return -1
            rx.removeFirst()
            return h.second
        }
    }
    val output = object : OutputStream() {
        override fun write(b: Int) {
            if (b == '\r'.code) { val c = cmd.toString(); cmd.clear(); handle(c) } else cmd.append(b.toChar())
        }
    }

    private fun emit(at: Long, s: String) {
        val due = maxOf(at, rx.lastOrNull()?.first ?: 0L)
        s.forEach { rx.addLast(due to it.code) }
    }

    private fun handle(c: String) {
        val t0 = clock.t
        if (echo) emit(t0, "$c\r")
        val u = c.uppercase().replace(" ", "")
        if (u.startsWith("AT")) { at(u.substring(2), t0); return }
        requests += u
        var hex = u
        var want = 0
        if (hex.length % 2 == 1) {
            if (!p.hint) { emit(t0 + p.latencyMs, "?\r\r>"); return }
            want = hex.last().digitToInt(16)
            hex = hex.dropLast(1)
        }
        val req = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        count++
        val late = if (p.lateEvery > 0 && count % p.lateEvery == 0) 900L else 0L
        var at = t0 + p.latencyMs + late
        val answers = ecu(req)
        var delivered = 0
        var last = at
        for ((delay, bytes) in answers) {
            if (delay > st) break // the adapter stopped listening before this arrived
            at = last + delay
            frames(bytes).forEach { emit(at, (if (p.nulNoise) "\u0000" else "") + it + "\r") }
            last = at
            delivered++
            if (want == 1) break
        }
        if (delivered == 0) { emit(last + st, "NO DATA\r\r>"); return }
        val tail = if (want == 1) 0L else if (p.adaptive) 30L else st
        emit(last + tail, "\r>")
    }

    private fun at(c: String, t0: Long) {
        val at = t0 + p.latencyMs
        if (p.rejects.any { c.startsWith(it) }) { emit(at, "?\r\r>"); return }
        when {
            c == "Z" -> { echo = true; spaces = true; st = 200; emit(t0 + p.atzMs, "\r\r${p.version}\r\r>") }
            c == "I" -> emit(at, "${p.version}\r\r>")
            c == "E0" -> { if (!p.echoStuck) echo = false; emit(at, "OK\r\r>") }
            c == "S0" -> { if (!p.spacesStuck) spaces = false; emit(at, "OK\r\r>") }
            c.startsWith("ST") -> { st = c.substring(2).toInt(16) * 4L; emit(at, "OK\r\r>") }
            else -> emit(at, "OK\r\r>")
        }
    }

    private fun fmt(b: List<Byte>): String = b.joinToString(if (spaces) " " else "") { "%02X".format(it) } + if (spaces) " " else ""

    private fun frames(b: ByteArray): List<String> {
        if (b.size <= 7) return listOf(fmt(b.toList()))
        val out = arrayListOf("%03X".format(b.size), "0:" + fmt(b.take(6)))
        if (!p.multiFrame) return out
        var i = 6
        var n = 1
        while (i < b.size) { out += "${(n % 16).toString(16).uppercase()}:" + fmt(b.drop(i).take(7)); i += 7; n++ }
        return out
    }

    /** Returns (delay after the last frame, reply). */
    private fun ecu(r: ByteArray): List<Pair<Long, ByteArray>> {
        fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val pos = r[0].toInt() and 0xFF
        return when {
            pos == 0x22 && r.size == 3 -> {
                val did = ((r[1].toInt() and 0xFF) shl 8) or (r[2].toInt() and 0xFF)
                val slowly = did == 0xF15B && p.pendingFirst
                val v = when (did) {
                    0xF187 -> "8X0906259 B".toByteArray()
                    0xF15B -> ByteArray(18) { (it + 1).toByte() }
                    else -> EcuPids.ALL.firstOrNull { it.did == did }?.let { ByteArray(it.len) { i -> if (i == 0) 0x10 else 0x20 } }
                }
                if (v == null) listOf(0L to b(0x7F, 0x22, 0x31))
                else {
                    val reply = b(0x62, did shr 8, did and 0xFF) + v
                    if (slowly) listOf(0L to b(0x7F, 0x22, 0x78), 150L to reply) else listOf(0L to reply)
                }
            }
            pos == 0x19 && r[1].toInt() == 0x01 -> listOf(0L to b(0x59, 0x01, 0xFF, 0x01, 0x00, codes.size))
            pos == 0x19 && r[1].toInt() == 0x02 -> {
                var o = b(0x59, 0x02, 0xFF)
                codes.forEach { c ->
                    val n = c.substring(1, 5).toInt(16)
                    o += b(n shr 8, n and 0xFF, 0x00, 0x08)
                }
                val ans = listOf(0L to o)
                if (p.pendingFirst) listOf(0L to b(0x7F, 0x19, 0x78), 150L to o) else ans
            }
            pos == 0x14 -> if (p.pendingFirst) listOf(0L to b(0x7F, 0x14, 0x78), p.clearMs to b(0x54)) else listOf(p.clearMs to b(0x54))
            else -> listOf(0L to b(0x7F, pos, 0x11))
        }
    }
}
