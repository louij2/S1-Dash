package com.louij2.s1dash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packet layout checked against Switchleg1/esp32-isotp-ble-bridge (BridgeLEG branch, v1.03): ble_server.h, isotp_bridge.c. */
class BridgeFramerTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val rpmReply = b(0x62, 0xF4, 0x0C, 0x0B, 0xB8)
    private fun fromBridge(payload: ByteArray) = b(0xF1, 0x00, 0xE8, 0x07, 0xE0, 0x07, payload.size, 0x00) + payload

    @Test fun requestHeaderIsWhatTheFirmwareRoutesToTheEcuLink() {
        // isotp_bridge.c: msg.txID = header.rxID must be 0x7E8 and msg.rxID = header.txID 0x7E0.
        val p = BridgeFramer.packet(b(0x22, 0xF4, 0x0C), 0x7E8, 0x7E0)
        assertArrayEquals(b(0xF1, 0x00, 0xE8, 0x07, 0xE0, 0x07, 0x03, 0x00, 0x22, 0xF4, 0x0C), p)
    }

    @Test fun oneNotificationOnePacket() {
        val f = BridgeFramer()
        val out = f.feed(fromBridge(rpmReply))
        assertEquals(1, out.size)
        assertArrayEquals(rpmReply, out[0])
    }

    @Test fun twoPacketsInOneNotification() {
        val f = BridgeFramer()
        assertEquals(2, f.feed(fromBridge(rpmReply) + fromBridge(b(0x62, 0x20, 0x33, 0x00, 0x10))).size)
    }

    @Test fun aPacketSplitOverTwoNotifications() {
        val f = BridgeFramer()
        val whole = fromBridge(rpmReply)
        assertEquals(0, f.feed(whole.copyOfRange(0, 6)).size)
        val out = f.feed(whole.copyOfRange(6, whole.size))
        assertArrayEquals(rpmReply, out.single())
    }

    @Test fun anUnknownFormatIsReportedWithItsBytes() {
        val f = BridgeFramer()
        assertEquals(0, f.feed(b(0xF2, 0x01, 0x62, 0xF4, 0x0C, 0x0B, 0xB8)).size)
        assertTrue(f.unknown!!, f.unknown!!.startsWith("F2 01 62"))
        f.reset()
        assertNull(f.unknown)
        assertEquals(1, f.notifications)
    }
}
