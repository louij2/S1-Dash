package com.louij2.s1dash

/**
 * Simos18 live-data identifiers (UDS ReadDataByIdentifier 0x22 on the engine
 * ECU, 0x7E0 -> 0x7E8). Addresses and scaling come from the SimosTools mode-22
 * PID list (Switchleg1/SimosTools, PID/PIDList22-USA.csv), converted to metric.
 * Multi-byte values are big-endian.
 */
data class Pid(
    val key: String,
    val did: Int,
    val len: Int,
    val signed: Boolean,
    val fast: Boolean,          // polled every cycle; the rest round-robin
    val conv: (Double) -> Double,
)

object EcuPids {
    val ALL = listOf(
        Pid("map", 0x39C0, 2, false, true) { it / 10.0 },                    // kPa absolute
        Pid("rpm", 0xF40C, 2, false, true) { it / 4.0 },
        // Straight after rpm, so clutch slip compares readings taken close together.
        Pid("ecuSpeed", 0x2033, 2, false, true) { it / 100.0 },              // km/h
        Pid("gear", 0x210F, 2, false, true) { it + 1.0 },                    // offset unproven: SimosTools' lists differ
        Pid("torque", 0x437C, 2, true, true) { it / 10.0 },                  // Nm, from PIDs.kt (the USA csv's x / 7.37562 is wrong)
        Pid("knock1", 0x200A, 2, true, true) { it / 100.0 },                 // deg retard
        Pid("knock2", 0x200B, 2, true, true) { it / 100.0 },
        Pid("knock3", 0x200C, 2, true, true) { it / 100.0 },
        Pid("knock4", 0x200D, 2, true, true) { it / 100.0 },
        Pid("mapSp", 0x39C1, 2, false, false) { it / 10.0 },                 // kPa
        Pid("ambient", 0x13CA, 2, false, false) { it / 120.6017666543982 },  // kPa
        Pid("coolant", 0xF405, 1, false, false) { it - 40.0 },               // degC
        Pid("oil", 0x202F, 2, false, false) { it * 0.1 - 273.15 },           // degC (0.1 K)
        Pid("iat", 0x1001, 1, false, false) { it * 0.75 - 48.0 },            // degC
        Pid("timing", 0x2004, 2, true, false) { it / 100.0 },                // deg
        Pid("wastegate", 0x39A2, 2, false, false) { 100.0 - it / 100.0 },    // %
        Pid("put", 0x202A, 2, false, false) { it / 10.0 },                   // kPa, pressure upstream of throttle
        Pid("afr", 0x10C0, 2, false, false) { it / 69.6598639456 },
        Pid("stft", 0xF406, 1, false, false) { it / 1.28 - 100.0 },          // %
        Pid("ltft", 0xF407, 1, false, false) { it / 1.28 - 100.0 },          // %
        Pid("fpDi", 0x2027, 2, false, false) { it / 10.0 },                  // bar (x is kPa*0.1 -> psi*1.45038 in SimosTools)
        Pid("fpMpi", 0x2025, 2, false, false) { it / 1000.0 },               // bar
        Pid("pedal", 0x1070, 2, false, false) { it / 10.24 },                // %
        Pid("battery", 0x14A6, 1, false, false) { it * 0.1015625 },          // V
        Pid("misfires", 0x2904, 2, false, false) { it },
        Pid("turbo", 0x1040, 2, false, false) { it / 163.84 },               // krpm
        // Targets, to set against the actual values above. Unproven on SC8H65 until a drive (#909).
        Pid("putSp", 0x2029, 2, false, false) { it / 10.0 },                 // kPa
        Pid("lambdaSp", 0xF444, 2, false, false) { it / 32768.0 },           // lambda (SimosTools: x / 2229.1 = AFR)
        Pid("torqueReq", 0x4380, 2, true, false) { it / 10.0 },              // Nm, same scaling as 0x437C
        Pid("fpDiSp", 0x293B, 2, false, false) { it / 10.0 },                // bar
        Pid("fpMpiSp", 0x2932, 2, false, false) { it / 1000.0 },             // bar
        Pid("lpfpDuty", 0x2028, 2, false, false) { it / 100.0 },             // %
        Pid("cruise", 0x203C, 2, false, false) { it },                       // raw; SimosTools starts logging on it
    )

    /** SimosTools asks for up to 8 DIDs (16 bytes) in one ReadDataByIdentifier. */
    const val MAX_PER_REQUEST = 8

    fun request(pids: List<Pid>): ByteArray {
        val out = ByteArray(1 + pids.size * 2)
        out[0] = 0x22
        pids.forEachIndexed { i, p ->
            out[1 + i * 2] = (p.did shr 8).toByte()
            out[2 + i * 2] = p.did.toByte()
        }
        return out
    }

    /**
     * Splits a multi-DID reply: 0x62, then each DID followed by its value. The ECU may
     * leave out a DID it doesn't support, so walk the reply by DID rather than by
     * position. Returns null if it isn't a positive reply or doesn't line up.
     */
    fun decodeMulti(pids: List<Pid>, r: ByteArray): Map<String, Double?>? {
        if (r.isEmpty() || r[0] != 0x62.toByte()) return null
        val byDid = pids.associateBy { it.did }
        val out = LinkedHashMap<String, Double?>()
        var i = 1
        while (i + 2 <= r.size) {
            val did = ((r[i].toInt() and 0xFF) shl 8) or (r[i + 1].toInt() and 0xFF)
            val p = byDid[did] ?: return null
            if (i + 2 + p.len > r.size) return null
            out[p.key] = decode(p, r, i + 2)
            i += 2 + p.len
        }
        return if (i == r.size && out.isNotEmpty()) out else null
    }

    /** Decode the value bytes of one DID. */
    fun decode(p: Pid, b: ByteArray, off: Int): Double? {
        if (off + p.len > b.size) return null
        var raw = 0L
        for (i in 0 until p.len) raw = (raw shl 8) or (b[off + i].toLong() and 0xFF)
        if (p.signed) {
            val bits = p.len * 8
            if (raw and (1L shl (bits - 1)) != 0L) raw -= (1L shl bits)
        }
        return p.conv(raw.toDouble())
    }
}
