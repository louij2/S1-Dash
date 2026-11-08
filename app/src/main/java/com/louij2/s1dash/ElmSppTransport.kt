package com.louij2.s1dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID

/**
 * ELM327 over Bluetooth Classic (SPP), found by MAC address. This is how the
 * steering wheel's OBD dongle can be used while the wheel itself is switched off.
 * Bluetooth LE and Wi-Fi ELMs can't connect this way. The protocol is in [ElmProtocol].
 */
@SuppressLint("MissingPermission")
class ElmSppTransport(private val ctx: Context, private val mac: String) : UdsTransport {
    override val label = "ELM327"
    override val multiFrame = false
    override val lastError: String get() = error.ifEmpty { elm?.lastError ?: "" }

    private var error = ""
    private var socket: BluetoothSocket? = null
    private var elm: ElmProtocol? = null

    override fun open(): Boolean {
        if (mac.isBlank()) { error = "Choose the dongle in Settings"; return false }
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        if (!bt.isEnabled) { error = "Bluetooth is off"; return false }
        val dev = try { bt.getRemoteDevice(mac.trim().uppercase()) } catch (_: Exception) { error = "Bad dongle address"; return false }
        try { bt.cancelDiscovery() } catch (_: Exception) {}
        val s = connect(dev)
        if (s == null) { error = "Dongle won't connect. Is it Bluetooth Classic, and free?"; return false }
        socket = s
        val p = ElmProtocol(s.inputStream, s.outputStream)
        elm = p
        return p.open()
    }

    /** The usual ways in, then the fixed channel that many clones need because their service record is wrong. */
    private fun connect(dev: BluetoothDevice): BluetoothSocket? {
        val ways = listOf<() -> BluetoothSocket>(
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
        )
        for (way in ways) {
            var s: BluetoothSocket? = null
            try {
                s = way()
                s.connect()
                return s
            } catch (_: Exception) {
                try { s?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    override fun request(req: ByteArray, timeoutMs: Long, singleFrame: Boolean): ByteArray? =
        elm?.request(req, timeoutMs, singleFrame)

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        elm = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
