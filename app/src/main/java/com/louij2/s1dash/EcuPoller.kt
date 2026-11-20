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
    /** Why [open] failed, if it knows. */
    val lastError: String get() = ""
    fun request(req: ByteArray, timeoutMs: Long = 600, singleFrame: Boolean = false): ByteArray?
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
    private val guard = PollGuard()
    /** Whether this ECU takes several DIDs per request; cleared after repeated refusals. */
    private var multiOk = true
    private var multiFails = 0
    private val backoff = Backoff()
    /** Unanswered reads in a row on the current connection. */
    private var misses = 0

    /**
     * A read went unanswered. Ask again on the same connection a couple of times before
     * hanging up: reconnecting waits 2-30 s, and one lost request shouldn't cost that (#1037).
     * Returns true while it's still worth asking again.
     */
    private fun missedOnLink(t: UdsTransport): Boolean {
        misses++
        if (misses >= MISSES_BEFORE_RECONNECT) { misses = 0; return false }
        DataHub.status["ecu"] = "${noReplyReason(lastReply, t.lastError)}, asking again"
        Thread.sleep(MISS_RETRY_MS)
        return true
    }

    /** The ECU's last single-DID answer, to tell a refusal from silence. */
    private var lastReply: ByteArray? = null

    /** Lets go of the dongle and waits longer each time it fails again, so SimosTools can get in. */
    private fun waitAfterFailure(what: String) {
        val ms = backoff.next()
        DataHub.status["ecu"] = "$what, retrying in ${ms / 1000} s"
        Thread.sleep(ms)
    }

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

    /** The ECU's software number and flash record, for the checks around a flash. Multi-frame, so ELM adapters may not manage them. */
    fun readIdentity() = commands.offer { t ->
        readDid(t, 0xF187)?.let { DataHub.ecuPart = String(it, Charsets.ISO_8859_1).trim() }
        DataHub.ecuFlashLog = readDid(t, 0xF15B)?.joinToString("") { "%02x".format(it) } ?: ""
    }

    private fun readDid(t: UdsTransport, did: Int): ByteArray? {
        val r = send(t, byteArrayOf(0x22, (did shr 8).toByte(), did.toByte()), 1500) ?: return null
        if (r.size < 3 || r[0] != 0x62.toByte()) return null
        return r.copyOfRange(3, r.size)
    }

    fun clearDtcs() = commands.offer { t ->
        val r = send(t, byteArrayOf(0x14, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 3000)
        if (r != null && r.isNotEmpty() && r[0] == 0x54.toByte()) readDtcList(t)
        else DataHub.ecuDtcJson = JSONObject().put("state", "error").put("message", nrc(r, "Clear refused")).toString()
    }

    private fun loop() {
        val plan = PollPlan(EcuPids.ALL, startReduced = adapter != "bridge")
        var lastDtcCount = 0L
        while (running) {
            try {
                var t = transport
                if (t == null) {
                    // SimosTools has it, maybe logging. Say so and leave it alone rather than keep trying.
                    if (adapter == "bridge" && IsoTpBleTransport.heldElsewhere(ctx, mac, bridgeName)) {
                        DataHub.status["ecu"] = "in use by SimosTools"
                        DataHub.status["simos"] = "connected"
                        Thread.sleep(5000)
                        continue
                    }
                    DataHub.status.remove("simos")
                    DataHub.status["ecu"] = "connecting"
                    t = if (adapter == "bridge") IsoTpBleTransport(ctx, mac, bridgeName, DataHub.setting("bridgePassword")) else ElmSppTransport(ctx, mac)
                    if (!t.open()) {
                        t.close()
                        waitAfterFailure(t.lastError.ifEmpty { "no adapter" })
                        continue
                    }
                    transport = t
                    misses = 0
                    guard.reset()
                    multiOk = true
                    multiFails = 0
                    DataHub.status["ecu"] = "connected (${t.label})"
                }
                while (true) {
                    val cmd = commands.poll() ?: break
                    cmd(t)
                }
                if (!guard.warm) {
                    // Only rpm until the engine has been running a while (#767: drops at ignition on),
                    // plus speed and battery while the ECU files check needs them.
                    val keys = guard.coldKeys(CalFlash.checkingBeforeFlash())
                    val rpm = EcuPids.ALL.first { it.key == "rpm" }
                    val ok = readPid(t, rpm)
                    if (ok) EcuPids.ALL.filter { it.key in keys && it.key != "rpm" }.forEach { readPid(t, it) }
                    guard.feedRpm(if (ok) DataHub.get("rpm") else null)
                    if (!ok) {
                        if (missedOnLink(t)) continue
                        DataHub.dropEcuValues()
                        t.close(); transport = null
                        waitAfterFailure(noReplyReason(lastReply, t.lastError))
                        continue
                    }
                    misses = 0
                    backoff.reset()
                    DataHub.status["ecu"] = if (guard.warm) "connected (${t.label})" else "connected (${t.label}), waiting for the engine"
                    DataHub.status.remove("ecuRate")
                    if (!guard.warm) Thread.sleep(PollGuard.PROBE_EVERY_MS)
                    continue
                }
                val started = System.currentTimeMillis()
                val fast = plan.fast
                val slow = plan.nextSlow(DataHub.wantAllValues())
                val readNow = readGroup(t, fast)
                val answered = readNow.size + readGroup(t, slow).size
                plan.record(fast.size + slow.size, System.currentTimeMillis() - started)
                DataHub.setHolds(plan.fastHoldMs(), plan.slowHoldMs(DataHub.wantAllValues()), fast.map { it.key }, DERIVED)
                DataHub.status["ecuData"] = if (plan.reduced) "core" else "full"
                DataHub.status["ecuRate"] = String.format(java.util.Locale.UK, "%.1f", plan.updatesPerSecond)
                derive()
                if (readNow.containsAll(DataHub.CLUTCH_INPUTS)) DataHub.feedClutch(DataHub.clutch)
                if (answered == 0) {
                    if (missedOnLink(t)) continue
                    DataHub.dropEcuValues()
                    t.close(); transport = null
                    waitAfterFailure(noReplyReason(lastReply, t.lastError))
                    continue
                }
                misses = 0
                backoff.reset()
                if (System.currentTimeMillis() - lastDtcCount > 30_000L) {
                    lastDtcCount = System.currentTimeMillis()
                    readDtcCount(t)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                DataHub.dropEcuValues()
                transport?.close(); transport = null
                try { waitAfterFailure("error: ${e.javaClass.simpleName}") } catch (_: InterruptedException) { break }
            }
        }
        transport?.close()
        transport = null
        DataHub.status.remove("simos")
        DataHub.dropEcuValues()
    }

    /** Waits as long as the ceiling needs, then sends. Every request to the ECU goes through here. */
    private fun send(t: UdsTransport, req: ByteArray, timeoutMs: Long = 600, singleFrame: Boolean = false): ByteArray? {
        val wait = guard.waitMs()
        if (wait > 0) Thread.sleep(wait)
        guard.sent()
        return t.request(req, timeoutMs, singleFrame)
    }

    /**
     * Reads [pids] and returns the keys that answered. Adapters with reliable multi-frame
     * replies (the BLE bridge) get up to 8 DIDs per request; ELM adapters ask one at a time.
     */
    private fun readGroup(t: UdsTransport, pids: List<Pid>): List<String> {
        if (!t.multiFrame || !multiOk || pids.size < 2) return pids.filter { readPid(t, it) }.map { it.key }
        val keys = ArrayList<String>()
        for (chunk in pids.chunked(EcuPids.MAX_PER_REQUEST)) {
            val got = send(t, EcuPids.request(chunk))?.let { EcuPids.decodeMulti(chunk, it) }
            if (got == null) {
                // Refused or didn't line up: ask one at a time, and stop trying after a few in a row.
                if (++multiFails >= 3) multiOk = false
                chunk.filter { readPid(t, it) }.mapTo(keys) { it.key }
                continue
            }
            multiFails = 0
            got.forEach { (k, v) -> DataHub.put(k, v) }
            keys.addAll(got.keys)
        }
        return keys
    }

    private fun readPid(t: UdsTransport, p: Pid): Boolean {
        val req = byteArrayOf(0x22, (p.did shr 8).toByte(), p.did.toByte())
        val r = send(t, req, singleFrame = true)
        lastReply = r
        if (r == null) return false
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
        val lambda = DataHub.get("afr")?.let { it / 14.7 }
        if (lambda != null) DataHub.put("lambda", lambda)
        // Actual minus target, so a gap shows at a glance.
        val mapSp = DataHub.get("mapSp")
        if (map != null && mapSp != null) DataHub.put("boostDev", (map - mapSp) / 100.0)
        DataHub.get("lambdaSp")?.let { sp -> if (lambda != null) DataHub.put("lambdaDev", lambda - sp) }
        dev("fpDi", "fpDiSp", "fpDiDev")
        dev("fpMpi", "fpMpiSp", "fpMpiDev")
    }

    private fun dev(actual: String, target: String, out: String) {
        val a = DataHub.get(actual) ?: return
        val sp = DataHub.get(target) ?: return
        DataHub.put(out, a - sp)
    }

    /** ReadDTCInformation, reportNumberOfDTCByStatusMask (confirmed): a single-frame reply. */
    private fun readDtcCount(t: UdsTransport) {
        val r = send(t, byteArrayOf(0x19, 0x01, 0x08), 1500) ?: return
        if (r.size >= 6 && r[0] == 0x59.toByte()) {
            DataHub.put("dtcEcu", (((r[4].toInt() and 0xFF) shl 8) or (r[5].toInt() and 0xFF)).toDouble())
        }
    }

    /** ReadDTCInformation, reportDTCByStatusMask. Multi-frame when there are more than one or two codes. */
    private fun readDtcList(t: UdsTransport) {
        val r = send(t, byteArrayOf(0x19, 0x02, 0x0D), 3000)
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
        const val MISSES_BEFORE_RECONNECT = 3
        const val MISS_RETRY_MS = 500L
        /** Worked out from polled values each cycle, so they stay as current as the fast list. */
        private val DERIVED = listOf("boost", "ambientBar", "knockAvg", "lambda", "boostDev", "lambdaDev", "fpDiDev", "fpMpiDev", "clutchSlip", "clutchLoad", "clutchWarn")

        /** UDS 3-byte DTC -> "P3019" + failure-type byte, as VCDS shows it. */
        fun formatDtc(code: Int): String {
            val hi = (code shr 16) and 0xFF
            val letter = "PCBU"[(hi shr 6) and 3]
            return String.format("%c%01X%01X%02X-%02X", letter, (hi shr 4) and 3, hi and 0xF, (code shr 8) and 0xFF, code and 0xFF)
        }
    }
}

/**
 * Why the ECU gave nothing usable. A negative reply (7F 22 xx) means something answered but
 * refused the VW mode-22 request: not a Simos18 engine, so S1 Dash can't read it.
 */
fun noReplyReason(lastReply: ByteArray?, transportError: String): String {
    val r = lastReply
    if (r != null && r.size >= 3 && r[0] == 0x7F.toByte() && r[1] == 0x22.toByte()) {
        return "ECU refused VW data (NRC %02X). Not a VW/Audi Simos18 engine?".format(r[2].toInt() and 0xFF)
    }
    return transportError.ifEmpty { "no ECU reply" }
}
