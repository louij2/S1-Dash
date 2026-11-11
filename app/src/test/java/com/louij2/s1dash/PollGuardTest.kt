package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollGuardTest {
    private var clock = 1_000L
    private val g = PollGuard { clock }

    @Test fun warmsOnlyAfterTenSecondsRunning() {
        g.feedRpm(800.0)
        clock += 9_000; g.feedRpm(800.0)
        assertFalse(g.warm)
        clock += 1_000; g.feedRpm(800.0)
        assertTrue(g.warm)
    }

    @Test fun ignitionOnWithoutTheEngineStaysCold() {
        repeat(20) { clock += 2_000; g.feedRpm(0.0) }
        assertFalse(g.warm)
    }

    @Test fun aDipBelowRunningStartsTheCountAgain() {
        g.feedRpm(800.0)
        clock += 8_000; g.feedRpm(300.0)
        clock += 4_000; g.feedRpm(800.0)
        assertFalse(g.warm)
        clock += 10_000; g.feedRpm(800.0)
        assertTrue(g.warm)
    }

    @Test fun staysWarmUntilReset() {
        g.feedRpm(800.0); clock += 10_000; g.feedRpm(800.0)
        g.feedRpm(0.0)
        assertTrue(g.warm)
        g.reset()
        assertFalse(g.warm)
    }

    @Test fun keepsRequestsUnderTwentyASecond() {
        g.sent()
        assertEquals(50L, g.waitMs())
        clock += 30
        assertEquals(20L, g.waitMs())
        clock += 40
        assertEquals(0L, g.waitMs())
    }
}
