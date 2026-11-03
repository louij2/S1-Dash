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
        Pid("ecuSpeed", 0x2033, 2, false, false) { it / 100.0 },             // km/h
        Pid("gear", 0x210F, 2, false, false) { it + 1.0 },
        Pid("misfires", 0x2904, 2, false, false) { it },
        Pid("turbo", 0x1040, 2, false, false) { it / 163.84 },               // krpm
    )

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
