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
import java.io.ByteArrayOutputStream
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
) : UdsTransport {
    override val label = "SimosTools dongle"
    override val multiFrame = true

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val replies = LinkedBlockingQueue<ByteArray>()
    private val rxBuf = ByteArrayOutputStream()
    private var ready = CountDownLatch(1)
    @Volatile private var ok = false

    override fun open(): Boolean {
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        if (!bt.isEnabled) return false
        val dev: BluetoothDevice = if (mac.isNotBlank()) {
            try { bt.getRemoteDevice(mac.trim().uppercase()) } catch (_: Exception) { return false }
        } else scanByName() ?: return false
        ready = CountDownLatch(1)
        ok = false
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(12, TimeUnit.SECONDS)) return false
        return ok
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
            else { ok = false; ready.countDown() }
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

    /** Notifications can carry several packets back to back, or part of one. */
    @Synchronized
    private fun received(chunk: ByteArray) {
        rxBuf.write(chunk)
        var buf = rxBuf.toByteArray()
        while (buf.size >= 8) {
            if ((buf[0].toInt() and 0xFF) != HEADER_ID) { buf = ByteArray(0); break }
            val size = (buf[6].toInt() and 0xFF) or ((buf[7].toInt() and 0xFF) shl 8)
            if (buf.size < 8 + size) break
            replies.offer(buf.copyOfRange(8, 8 + size))
            buf = buf.copyOfRange(8 + size, buf.size)
        }
        rxBuf.reset()
        rxBuf.write(buf)
    }

    @Suppress("DEPRECATION")
    override fun request(req: ByteArray, timeoutMs: Long, singleFrame: Boolean): ByteArray? {
        val g = gatt ?: return null
        val c = tx ?: return null
        replies.clear()
        val pkt = ByteArray(8 + req.size)
        pkt[0] = HEADER_ID.toByte()
        pkt[1] = 0
        pkt[2] = (RX_ID and 0xFF).toByte(); pkt[3] = (RX_ID shr 8).toByte()
        pkt[4] = (TX_ID and 0xFF).toByte(); pkt[5] = (TX_ID shr 8).toByte()
        pkt[6] = (req.size and 0xFF).toByte(); pkt[7] = (req.size shr 8).toByte()
        System.arraycopy(req, 0, pkt, 8, req.size)
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val sent = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, pkt, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            c.writeType = type
            c.value = pkt
            g.writeCharacteristic(c)
        }
        if (!sent) return null
        var deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val r = replies.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (r.isEmpty()) continue
            // 7F xx 78 = response pending: the real answer follows.
            if (r.size >= 3 && r[0] == 0x7F.toByte() && r[2] == 0x78.toByte()) { deadline += 3000; continue }
            if (r[0] == (req[0] + 0x40).toByte() || r[0] == 0x7F.toByte()) return r
        }
    }

    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("0000abf0-0000-1000-8000-00805f9b34fb")
        val DATA_TX: UUID = UUID.fromString("0000abf1-0000-1000-8000-00805f9b34fb")
        val DATA_RX: UUID = UUID.fromString("0000abf2-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val HEADER_ID = 0xF1
        const val TX_ID = 0x7E0
        const val RX_ID = 0x7E8
    }
}
