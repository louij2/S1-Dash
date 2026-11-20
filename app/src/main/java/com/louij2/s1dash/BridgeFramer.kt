package com.louij2.s1dash

/**
 * Splits the SimosTools bridge's notifications into packets: 8-byte header (0xF1, flags,
 * rx id LE, tx id LE, payload length LE), then the UDS payload. Notifications can carry
 * several packets back to back, or part of one. Also keeps what it saw, so the status line
 * can say what the bridge actually sent when no answer turns up (#1037).
 */
class BridgeFramer {
    private var buf = ByteArray(0)
    var packets = 0
        private set
    var notifications = 0
        private set
    /** First bytes of the latest notification, as hex. */
    var lastSeen = ""
        private set
    /** Set when a notification didn't start with a known header. */
    var unknown: String? = null
        private set

    @Synchronized
    fun feed(chunk: ByteArray): List<ByteArray> {
        notifications++
        lastSeen = hex(chunk, 12)
        buf += chunk
        val out = ArrayList<ByteArray>()
        while (buf.isNotEmpty()) {
            if ((buf[0].toInt() and 0xFF) != HEADER_ID) {
                // 0xF2 = a split packet (the bridge's MTU was too small to send it whole), or a format we don't know.
                unknown = hex(buf, 12)
                buf = ByteArray(0)
                break
            }
            if (buf.size < HEADER) break
            val size = (buf[6].toInt() and 0xFF) or ((buf[7].toInt() and 0xFF) shl 8)
            if (buf.size < HEADER + size) break
            out += buf.copyOfRange(HEADER, HEADER + size)
            packets++
            buf = buf.copyOfRange(HEADER + size, buf.size)
        }
        return out
    }

    @Synchronized
    fun reset() { buf = ByteArray(0); unknown = null }

    companion object {
        const val HEADER = 8
        const val HEADER_ID = 0xF1

        fun hex(b: ByteArray, max: Int): String =
            b.take(max).joinToString(" ") { "%02X".format(it) } + if (b.size > max) " …" else ""

        /** The request packet: header with rx 0x7E8 / tx 0x7E0 (the ECU link the bridge firmware routes), then the UDS bytes. */
        fun packet(req: ByteArray, rxId: Int, txId: Int): ByteArray {
            val pkt = ByteArray(HEADER + req.size)
            pkt[0] = HEADER_ID.toByte()
            pkt[1] = 0
            pkt[2] = (rxId and 0xFF).toByte(); pkt[3] = (rxId shr 8).toByte()
            pkt[4] = (txId and 0xFF).toByte(); pkt[5] = (txId shr 8).toByte()
            pkt[6] = (req.size and 0xFF).toByte(); pkt[7] = (req.size shr 8).toByte()
            System.arraycopy(req, 0, pkt, HEADER, req.size)
            return pkt
        }
    }
}
