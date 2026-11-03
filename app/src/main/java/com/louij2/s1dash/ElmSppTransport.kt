package com.louij2.s1dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ELM327 over Bluetooth Classic (SPP), found by MAC address. This is how the
 * steering wheel's OBD dongle can be used while the wheel itself is switched off.
 * Clone ELMs often mangle multi-frame replies, so live data asks for one
 * identifier at a time (single-frame replies only).
 */
@SuppressLint("MissingPermission")
class ElmSppTransport(private val ctx: Context, private val mac: String) : UdsTransport {
    override val label = "ELM327"
    override val multiFrame = false

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open(): Boolean {
        if (mac.isBlank()) return false
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        if (!bt.isEnabled) return false
        val dev = try { bt.getRemoteDevice(mac.trim().uppercase()) } catch (_: Exception) { return false }
        try { bt.cancelDiscovery() } catch (_: Exception) {}
        val s = try {
            dev.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (_: Exception) {
            try { dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() } } catch (_: Exception) { return false }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
        command("ATZ", 2000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP6", "ATSH7E0", "ATCRA7E8", "ATST32", "ATCAF1")) {
            if (command(c, 1000) == null) return false
        }
        return true
    }

    override fun request(req: ByteArray, timeoutMs: Long): ByteArray? {
        val hex = req.joinToString("") { String.format("%02X", it) }
        var deadline = System.currentTimeMillis() + timeoutMs
        var lines = command(hex, timeoutMs) ?: return null
        // 7F xx 78 = "response pending": keep listening for the real answer.
        while (true) {
            val data = parse(lines) ?: return null
            if (data.size >= 3 && data[0] == 0x7F.toByte() && data[2] == 0x78.toByte() && System.currentTimeMillis() < deadline + 3000) {
                deadline += 3000
                lines = readUntilPrompt(3000) ?: return null
                continue
            }
            return data
        }
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun command(cmd: String, timeoutMs: Long): List<String>? {
        val out = output ?: return null
        return try {
            input?.let { while (it.available() > 0) it.read() }
            out.write((cmd + "\r").toByteArray())
            out.flush()
            readUntilPrompt(timeoutMs)
        } catch (_: Exception) { null }
    }

    private fun readUntilPrompt(timeoutMs: Long): List<String>? {
        val inp = input ?: return null
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val c = inp.read()
                if (c < 0) return null
                if (c == '>'.code) return sb.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                sb.append(c.toChar())
            } else Thread.sleep(5)
        }
        return null
    }

    /** ELM text -> bytes. Handles single frames and the "len / 0: / 1:" multi-frame format. */
    private fun parse(lines: List<String>): ByteArray? {
        val clean = lines.filter { !it.startsWith("SEARCHING") && it != "OK" }
        if (clean.any { it.contains("NO DATA") || it.contains("ERROR") || it == "?" || it.contains("STOPPED") }) return null
        var expected = -1
        val hex = StringBuilder()
        for (l in clean) {
            val s = l.replace(" ", "")
            when {
                s.length == 3 && s.all { it.isHexDigitChar() } && expected < 0 && clean.size > 1 -> expected = s.toInt(16)
                s.length > 2 && s[1] == ':' -> hex.append(s.substring(2))
                s.all { it.isHexDigitChar() } -> hex.append(s)
            }
        }
        if (hex.length < 2) return null
        var bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        if (expected in 1 until bytes.size) bytes = bytes.copyOf(expected)
        return bytes
    }

    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
