package com.louij2.s1dash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcuPidsTest {
    private fun pid(key: String) = EcuPids.ALL.first { it.key == key }
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun keysAndDidsAreUnique() {
        assertEquals(EcuPids.ALL.size, EcuPids.ALL.map { it.key }.toSet().size)
        assertEquals(EcuPids.ALL.size, EcuPids.ALL.map { it.did }.toSet().size)
    }

    @Test fun buildsOneRequestForSeveralDids() {
        assertArrayEquals(bytes(0x22, 0x39, 0xC0, 0xF4, 0x0C), EcuPids.request(listOf(pid("map"), pid("rpm"))))
    }

    @Test fun splitsAMultiDidReply() {
        // MAP 0x39C0 = 2000 (200.0 kPa), coolant 0xF405 = 130 (90 degC), lambda target 0xF444 = 0x8000 (1.0)
        val r = bytes(0x62, 0x39, 0xC0, 0x07, 0xD0, 0xF4, 0x05, 130, 0xF4, 0x44, 0x80, 0x00)
        val got = EcuPids.decodeMulti(listOf(pid("map"), pid("coolant"), pid("lambdaSp")), r)!!
        assertEquals(200.0, got["map"]!!, 1e-9)
        assertEquals(90.0, got["coolant"]!!, 1e-9)
        assertEquals(1.0, got["lambdaSp"]!!, 1e-9)
    }

    @Test fun aLeftOutDidIsFine() {
        val r = bytes(0x62, 0xF4, 0x05, 130)
        val got = EcuPids.decodeMulti(listOf(pid("map"), pid("coolant")), r)!!
        assertEquals(setOf("coolant"), got.keys)
    }

    @Test fun refusalsAndMisalignedRepliesAreRejected() {
        assertNull(EcuPids.decodeMulti(listOf(pid("map")), bytes(0x7F, 0x22, 0x31)))
        assertNull(EcuPids.decodeMulti(listOf(pid("map")), bytes(0x62, 0x39, 0xC0, 0x07))) // cut short
        assertNull(EcuPids.decodeMulti(listOf(pid("map")), bytes(0x62, 0x12, 0x34, 0x00, 0x00))) // DID not asked for
        assertNull(EcuPids.decodeMulti(listOf(pid("map")), bytes(0x62)))
    }

    @Test fun signedTorqueRequest() {
        assertEquals(-12.5, EcuPids.decode(pid("torqueReq"), bytes(0xFF, 0x83), 0)!!, 1e-9)
    }
}
