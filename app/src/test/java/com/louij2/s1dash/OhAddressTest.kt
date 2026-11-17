package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OhAddressTest {
    private val saved = ArrayList<String>()

    @Test fun autoStartsAtTheOwnApWithNothingCached() {
        val a = OhAddress("auto", null) { saved += it }
        assertEquals("192.168.1.1", a.host)
        assertTrue(a.auto)
    }

    @Test fun autoStartsAtTheLastAddressFound() {
        assertEquals("10.42.0.17", OhAddress("", "10.42.0.17") {}.host)
    }

    @Test fun aTypedAddressIsUsedAsItIsAndNeverSearches() {
        val a = OhAddress("192.168.4.1", "10.42.0.17") {}
        assertEquals("192.168.4.1", a.host)
        repeat(9) { assertFalse(a.failed()) }
        a.found("10.0.0.9")
        assertEquals("192.168.4.1", a.host)
    }

    @Test fun threeFailedPollsSearchAgainAndFallBackToTheOwnAp() {
        val a = OhAddress("auto", "10.42.0.17") {}
        assertFalse(a.failed()); assertFalse(a.failed())
        assertTrue(a.failed())
        assertEquals("192.168.1.1", a.host)
    }

    @Test fun aFindIsUsedAndKept() {
        val a = OhAddress("auto", null) { saved += it }
        a.found("192.168.43.25")
        assertEquals("192.168.43.25", a.host)
        assertEquals(listOf("192.168.43.25"), saved)
        a.found("192.168.43.25")
        assertEquals("saved once", 1, saved.size)
    }

    @Test fun answeringResetsTheFailCount() {
        val a = OhAddress("auto", null) {}
        a.failed(); a.failed(); a.answered()
        assertFalse(a.failed())
    }

    @Test fun serviceNames() {
        assertTrue(OhAddress.isOpenHaldex("openhaldex"))
        assertTrue(OhAddress.isOpenHaldex("OpenHaldex (2)"))
        assertFalse(OhAddress.isOpenHaldex("openhaldex-tool"))
        assertFalse(OhAddress.isOpenHaldex("printer"))
        assertFalse(OhAddress.isOpenHaldex(null))
    }

    @Test fun subnets() {
        val ip = OhAddress.ipv4Of("192.168.43.25:8080")!!
        assertTrue(OhAddress.sameSubnet(ip, OhAddress.ipv4Of("192.168.43.1")!!, 24))
        assertFalse(OhAddress.sameSubnet(ip, OhAddress.ipv4Of("192.168.1.5")!!, 24))
        assertTrue(OhAddress.sameSubnet(OhAddress.ipv4Of("10.42.7.9")!!, OhAddress.ipv4Of("10.42.0.1")!!, 16))
        assertTrue(OhAddress.sameSubnet(OhAddress.ipv4Of("172.20.10.3")!!, OhAddress.ipv4Of("172.20.10.1")!!, 28))
        assertFalse(OhAddress.sameSubnet(OhAddress.ipv4Of("172.20.10.30")!!, OhAddress.ipv4Of("172.20.10.1")!!, 28))
        assertNull(OhAddress.ipv4Of("openhaldex.local"))
        assertNull(OhAddress.ipv4Of("300.1.1.1"))
    }
}
