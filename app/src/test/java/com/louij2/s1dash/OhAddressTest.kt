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
        repeat(9) { assertFalse(a.failed()) }  // typed: never searches, whatever the time
        a.found("10.0.0.9")
        assertEquals("192.168.4.1", a.host)
    }

    @Test fun aSearchOnlyAfter20sWithoutAGoodReply() {
        var now = 0L
        val a = OhAddress("auto", "10.42.0.17", { now }) {}
        repeat(19) { now += 1000; assertFalse("at $now ms", a.failed()) }  // C6 stalls of 2-5 s aren't a loss (#1061)
        assertEquals("10.42.0.17", a.host)
        now += 1000
        assertTrue(a.failed())
        assertEquals("192.168.1.1", a.host)
        now += 1000; assertFalse("not again straight away", a.failed())
        now += 20_000; assertTrue("again after another 20 s", a.failed())
    }

    @Test fun aFindIsUsedAndKept() {
        val a = OhAddress("auto", null) { saved += it }
        a.found("192.168.43.25")
        assertEquals("192.168.43.25", a.host)
        assertEquals(listOf("192.168.43.25"), saved)
        a.found("192.168.43.25")
        assertEquals("saved once", 1, saved.size)
    }

    @Test fun aGoodReplyRestartsTheClock() {
        var now = 0L
        val a = OhAddress("auto", null, { now }) {}
        now = 15_000; a.answered()
        now = 30_000; assertFalse("only 15 s since the last good reply", a.failed())
        now = 35_001; assertTrue(a.failed())
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
