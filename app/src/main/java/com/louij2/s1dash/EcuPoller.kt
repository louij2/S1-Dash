package com.louij2.s1dash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue

/** Sends one UDS request to the engine ECU (0x7E0) and returns the reply, or null. */
interface UdsTransport {
    val label: String
    /** Whether multi-frame (ISO-TP) replies come back reliably, e.g. a full DTC list. */
    val multiFrame: Boolean
    fun open(): Boolean
    fun request(req: ByteArray, timeoutMs: Long = 600): ByteArray?
    fun close()
}

/**
 * Polls the Simos18 ECU through a dongle: the SimosTools BLE ISO-TP bridge, or an
 * ELM327 (for example the steering wheel's dongle, while the wheel is switched off).
 * Only one app can use a dongle at a time.
 */
class EcuPoller(
    private val ctx: Context,
    private val adapter: String,
    private val mac: String,
    private val bridgeName: String,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var transport: UdsTransport? = null
    private val commands = LinkedBlockingQueue<(UdsTransport) -> Unit>()

    fun start() {
        running = true
        thread = Thread({ loop() }, "ecu").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        transport?.close()
    }

    fun readDtcs() = commands.offer { t -> readDtcList(t) }

    fun clearDtcs() = commands.offer { t ->
        val r = t.request(byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 3000)
        if (r != null && r.isNotEmpty() && r[0] == 0x54.toByte()) readDtcList(t)
        else DataHub.ecuDtcJson = JSONObject().put("state", "error").put("message", nrc(r, "Clear refused")).toString()
    }

    private fun loop() {
        val fast = EcuPids.ALL.filter { it.fast }
        val slow = EcuPids.ALL.filter { !it.fast }
        var slowIdx = 0
        var lastDtcCount = 0L
        while (running) {
            try {
                var t = transport
                if (t == null) {
                    DataHub.status["ecu"] = "connecting"
                    t = if (adapter == "bridge") IsoTpBleTransport(ctx, mac, bridgeName) else ElmSppTransport(ctx, mac)
                    if (!t.open()) {
                        t.close()
                        DataHub.status["ecu"] = "no adapter"
                        Thread.sleep(5000)
                        continue
                    }
                    transport = t
                    DataHub.status["ecu"] = "connected (${t.label})"
                }
                while (true) {
                    val cmd = commands.poll() ?: break
                    cmd(t)
                }
                val readNow = fast.filter { readPid(t, it) }.map { it.key }
                var answered = readNow.size
                // A few slow ones per cycle, so temps still update about once a second.
                repeat(3) {
                    if (readPid(t, slow[slowIdx])) answered++
                    slowIdx = (slowIdx + 1) % slow.size
                }
                derive()
                if (readNow.containsAll(DataHub.CLUTCH_INPUTS)) DataHub.feedClutch(DataHub.clutch)
                if (answered == 0) {
                    DataHub.status["ecu"] = "no ECU reply"
                    t.close(); transport = null
                    Thread.sleep(2000)
                    continue
                }
                if (System.currentTimeMillis() - lastDtcCount > 30_000L) {
                    lastDtcCount = System.currentTimeMillis()
                    readDtcCount(t)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                DataHub.status["ecu"] = "error: ${e.javaClass.simpleName}"
                transport?.close(); transport = null
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }
        transport?.close()
        transport = null
    }

    private fun readPid(t: UdsTransport, p: Pid): Boolean {
        val req = byteArrayOf(0x22, (p.did shr 8).toByte(), p.did.toByte())
        val r = t.request(req) ?: return false
        if (r.size < 3 || r[0] != 0x62.toByte()) return false
        if ((r[1].toInt() and 0xFF) != (p.did shr 8) || (r[2].toInt() and 0xFF) != (p.did and 0xFF)) return false
        DataHub.put(p.key, EcuPids.decode(p, r, 3))
        return true
    }

    /** Boost is manifold pressure minus atmospheric, in bar. */
    private fun derive() {
        val map = DataHub.get("map")
        val amb = DataHub.get("ambient")
        if (map != null && amb != null) DataHub.put("boost", (map - amb) / 100.0)
        if (amb != null) DataHub.put("ambientBar", amb / 100.0)
        val k = listOf("knock1", "knock2", "knock3", "knock4").mapNotNull { DataHub.get(it) }
        if (k.size == 4) DataHub.put("knockAvg", k.average())
        DataHub.get("afr")?.let { DataHub.put("lambda", it / 14.7) }
    }

    /** ReadDTCInformation, reportNumberOfDTCByStatusMask (confirmed): a single-frame reply. */
    private fun readDtcCount(t: UdsTransport) {
        val r = t.request(byteArrayOf(0x19, 0x01, 0x08), 1500) ?: return
        if (r.size >= 6 && r[0] == 0x59.toByte()) {
            DataHub.put("dtcEcu", (((r[4].toInt() and 0xFF) shl 8) or (r[5].toInt() and 0xFF)).toDouble())
        }
    }

    /** ReadDTCInformation, reportDTCByStatusMask. Multi-frame when there are more than one or two codes. */
    private fun readDtcList(t: UdsTransport) {
        val r = t.request(byteArrayOf(0x19, 0x02, 0x0D), 3000)
        val out = JSONObject()
        if (r == null || r.size < 3 || r[0] != 0x59.toByte()) {
            out.put("state", "error").put("message", if (t.multiFrame) nrc(r, "No reply") else "This adapter can't read the full list; use the SimosTools dongle")
        } else {
            val arr = JSONArray()
            var i = 3
            while (i + 3 < r.size) {
                val code = ((r[i].toInt() and 0xFF) shl 16) or ((r[i + 1].toInt() and 0xFF) shl 8) or (r[i + 2].toInt() and 0xFF)
                val st = r[i + 3].toInt() and 0xFF
                arr.put(JSONObject().put("code", formatDtc(code)).put("raw", String.format("%06X", code))
                    .put("status", st).put("active", st and 0x01 != 0).put("confirmed", st and 0x08 != 0))
                i += 4
            }
            out.put("state", "done").put("dtcs", arr)
            DataHub.put("dtcEcu", arr.length().toDouble())
        }
        DataHub.ecuDtcJson = out.toString()
    }

    private fun nrc(r: ByteArray?, fallback: String): String =
        if (r != null && r.size >= 3 && r[0] == 0x7F.toByte()) String.format("ECU said no (NRC %02X)", r[2]) else fallback

    companion object {
        /** UDS 3-byte DTC -> "P3019" + failure-type byte, as VCDS shows it. */
        fun formatDtc(code: Int): String {
            val hi = (code shr 16) and 0xFF
            val letter = "PCBU"[(hi shr 6) and 3]
            return String.format("%c%01X%01X%02X-%02X", letter, (hi shr 4) and 3, hi and 0xF, (code shr 8) and 0xFF, code and 0xFF)
        }
    }
}
