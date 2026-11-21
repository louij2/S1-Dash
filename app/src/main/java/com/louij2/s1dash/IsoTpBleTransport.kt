package com.louij2.s1dash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The SimosTools dongle: an ESP32 "BLE_TO_ISOTP" bridge. Each BLE packet is an
 * 8-byte header (0xF1, flags, rx id LE, tx id LE, payload length LE) followed
 * by the raw UDS payload; the bridge does the ISO-TP framing, so multi-frame
 * replies work. Protocol from Switchleg1/SimosTools (BTService.kt, Constants.kt).
 */
@SuppressLint("MissingPermission")
class IsoTpBleTransport(
    private val ctx: Context,
    private val mac: String,
    private val name: String,
    private val password: String = "",
) : UdsTransport {
    override val label = "SimosTools dongle"
    override val multiFrame = true

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val replies = LinkedBlockingQueue<ByteArray>()
    private val framer = BridgeFramer()
    @Volatile private var why = ""
    override val lastError: String get() = why
    private var ready = CountDownLatch(1)
    @Volatile private var ok = false
    @Volatile private var linkDown = false

    override fun open(): Boolean {
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        if (!bt.isEnabled) return false
        val dev: BluetoothDevice = if (mac.isNotBlank()) {
            try { bt.getRemoteDevice(mac.trim().uppercase()) } catch (_: Exception) { return false }
        } else scanByName() ?: run { why = "bridge not found by name: is it powered and free?"; return false }
        if (dev.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            why = "That dongle is Bluetooth Classic (an ELM327?): choose 'ELM327 dongle' in Settings"
            return false
        }
        ready = CountDownLatch(1)
        ok = false
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(12, TimeUnit.SECONDS)) { why = "bridge didn't finish connecting"; return false }
        if (!ok) { why = "bridge connected but notifications couldn't be switched on"; return false }
        return sendPassword()
    }

    /**
     * Sends the adapter password the way SimosTools does on every connect. 0xFF back = accepted,
     * 0x00 = wrong password. No answer = firmware without the check (public v1.03), which is fine.
     */
    private fun sendPassword(): Boolean {
        replies.clear()
        if (!write(BridgeFramer.passwordPacket(password))) { why = "bridge refused the password write"; return false }
        val r = replies.poll(PASSWORD_WAIT_MS, TimeUnit.MILLISECONDS) ?: return true
        replies.clear()
        if (r.size == 1 && r[0] == 0x00.toByte()) {
            why = "bridge refused the password: set the same 'Adapter password' as in SimosTools"
            return false
        }
        return true
    }

    private fun scanByName(): BluetoothDevice? {
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner ?: return null
        val found = LinkedBlockingQueue<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == name || result.scanRecord?.deviceName == name) found.offer(result.device)
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(listOf(filter), settings, cb)
            found.poll(6, TimeUnit.SECONDS)
        } catch (_: Exception) { null } finally {
            try { scanner.stopScan(cb) } catch (_: Exception) {}
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) g.discoverServices()
            else {
                // After a good connect this is the link dropping: say why, so the connection log shows it (#1061).
                if (ok) { linkDown = true; why = "BLE link dropped (${gattReason(status)})" }
                ok = false
                ready.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || g.getService(SERVICE) == null) { ready.countDown(); return }
            g.requestMtu(512)
        }

        @Suppress("DEPRECATION")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            val svc = g.getService(SERVICE) ?: run { ready.countDown(); return }
            tx = svc.getCharacteristic(DATA_TX)
            val rx = svc.getCharacteristic(DATA_RX) ?: run { ready.countDown(); return }
            g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(CCCD) ?: run { ready.countDown(); return }
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ok = status == BluetoothGatt.GATT_SUCCESS && tx != null
            ready.countDown()
        }

        @Deprecated("Android 12 and older")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) received(c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            received(value)
        }
    }

    private fun received(chunk: ByteArray) {
        framer.feed(chunk).forEach { replies.offer(it) }
    }

    @Suppress("DEPRECATION")
    override fun request(req: ByteArray, timeoutMs: Long, singleFrame: Boolean): ByteArray? {
        if (gatt == null || tx == null) return null
        if (linkDown) return null // keep the drop reason for the status line
        replies.clear()
        framer.reset()
        why = ""
        val sent = write(BridgeFramer.packet(req, RX_ID, TX_ID))
        if (!sent) { why = "bridge refused the request (BLE write failed)"; return null }
        val wait = maxOf(timeoutMs, MIN_WAIT_MS)
        var deadline = System.currentTimeMillis() + wait
        var other: ByteArray? = null
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val r = if (left > 0) replies.poll(left, TimeUnit.MILLISECONDS) else null
            if (r == null) { why = nothingBack(wait, other); return null }
            if (r.isEmpty()) continue
            // 7F xx 78 = response pending: the real answer follows.
            if (r.size >= 3 && r[0] == 0x7F.toByte() && r[2] == 0x78.toByte()) { deadline += 3000; continue }
            if (r[0] == (req[0] + 0x40).toByte() || r[0] == 0x7F.toByte()) return r
            other = r
        }
    }

    @Suppress("DEPRECATION")
    private fun write(pkt: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, pkt, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = pkt
            g.writeCharacteristic(c)
        }
    }

    /** What the bridge did instead of answering, for the status line. */
    private fun nothingBack(waitMs: Long, other: ByteArray?): String = when {
        framer.unknown != null -> "bridge sent an unknown packet: ${framer.unknown}"
        other != null -> "bridge answered something else: ${BridgeFramer.hex(other, 8)}"
        framer.notifications == 0 -> "bridge sent nothing back since connecting"
        else -> "no answer from the ECU through the bridge in ${waitMs} ms (last from bridge: ${framer.lastSeen})"
    }

    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    companion object {
        /** Android's GATT status codes on a dropped link, in words. */
        fun gattReason(status: Int): String = when (status) {
            8 -> "status 8: link timeout, the radio lost the dongle"
            19 -> "status 19: the dongle closed the connection"
            22 -> "status 22: this device closed the connection"
            62 -> "status 62: the connection never completed"
            133 -> "status 133: Android GATT error"
            else -> "status $status"
        }

        /** Android knows when another app on this device holds the dongle: it stops advertising, so a scan can't tell. */
        fun heldElsewhere(ctx: Context, mac: String, name: String): Boolean = try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).getConnectedDevices(BluetoothProfile.GATT)
                .any { (mac.isNotBlank() && it.address.equals(mac.trim(), true)) || it.name == name }
        } catch (_: Exception) { false }

        val SERVICE: UUID = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
        val DATA_TX: UUID = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")
        val DATA_RX: UUID = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TX_ID = 0x7E0
        const val RX_ID = 0x7E8
        /** The bridge adds BLE and ISO-TP latency on top of the ECU's own. */
        const val MIN_WAIT_MS = 1000L
        const val PASSWORD_WAIT_MS = 1000L
    }
}
