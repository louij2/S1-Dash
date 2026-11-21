package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OhSweepTest {
    private fun ip(s: String) = OhAddress.ipv4Of(s)!!

    @Test fun aHotspotSlash24IsSweptWithoutItselfOrTheEnds() {
        val h = OhSweep.hosts(ip("192.168.43.1"), 24)
        assertEquals(253, h.size)
        assertEquals("192.168.43.2", h.first())
        assertEquals("192.168.43.254", h.last())
        assertFalse("192.168.43.1" in h)
    }

    @Test fun aRandomisedAndroidHotspotSubnetWorksToo() {
        val h = OhSweep.hosts(ip("10.137.88.71"), 24)
        assertTrue("10.137.88.1" in h)
        assertFalse("10.137.88.71" in h)
        assertFalse("10.137.88.255" in h)
    }

    @Test fun smallSubnetsAndTooBigOnes() {
        assertEquals(listOf("172.20.10.2", "172.20.10.3", "172.20.10.4", "172.20.10.5", "172.20.10.6",
            "172.20.10.7", "172.20.10.8", "172.20.10.9", "172.20.10.10", "172.20.10.11", "172.20.10.12", "172.20.10.13", "172.20.10.14"),
            OhSweep.hosts(ip("172.20.10.1"), 28))
        assertTrue("a /16 is far too big to sweep", OhSweep.hosts(ip("10.0.0.5"), 16).isEmpty())
    }

    @Test fun onlyPrivateAddresses() {
        assertTrue(OhSweep.isPrivate(ip("192.168.1.10")))
        assertTrue(OhSweep.isPrivate(ip("172.31.0.1")))
        assertTrue(OhSweep.isPrivate(ip("10.1.2.3")))
        assertFalse(OhSweep.isPrivate(ip("172.32.0.1")))
        assertFalse(OhSweep.isPrivate(ip("100.64.1.1"))) // carrier NAT / Tailscale
    }

    @Test fun mobileDataAndVpnInterfacesAreSkipped() {
        assertTrue(OhSweep.skipInterface("rmnet_data0"))
        assertTrue(OhSweep.skipInterface("tun0"))
        assertFalse(OhSweep.skipInterface("ap0"))
        assertFalse(OhSweep.skipInterface("wlan1"))
        assertFalse(OhSweep.skipInterface("swlan0"))
    }

    @Test fun recognisesOpenHaldexByItsDashboard() {
        assertTrue(OhSweep.looksLikeOpenHaldex("""{"mode":2,"extButtonActive":false,"lockTarget":40}"""))
        assertFalse(OhSweep.looksLikeOpenHaldex("<html>router login</html>"))
    }
}
