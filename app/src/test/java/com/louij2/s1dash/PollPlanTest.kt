package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPlanTest {
    private fun plan(reduced: Boolean = false) = PollPlan(EcuPids.ALL, reduced)

    @Test fun everyCoreKeyIsARealValue() {
        val keys = EcuPids.ALL.map { it.key }
        (PollPlan.CORE_FAST + PollPlan.CORE_SLOW).forEach { assertTrue(it, it in keys) }
    }

    @Test fun clutchInputsAreFastInBothPlans() {
        listOf(true, false).forEach { r ->
            val fast = plan(r).fast.map { it.key }
            DataHub.CLUTCH_INPUTS.forEach { assertTrue("$it reduced=$r", it in fast) }
        }
    }

    @Test fun rpmAndSpeedStayNextToEachOther() {
        listOf(true, false).forEach { r ->
            val fast = plan(r).fast.map { it.key }
            assertEquals(fast.indexOf("rpm") + 1, fast.indexOf("ecuSpeed"))
        }
    }

    @Test fun slowAdapterDropsToReducedAndRecoversWithoutFlapping() {
        val p = plan(false)
        repeat(20) { p.record(10, 2000) }
        assertTrue(p.reduced)
        repeat(30) { p.record(10, 600) } // 60 ms: between the limits, so it stays
        assertTrue(p.reduced)
        repeat(30) { p.record(10, 300) }
        assertFalse(p.reduced)
    }

    @Test fun reducedSkipsExtrasUntilAScreenWantsThem() {
        val p = plan(true)
        val core = HashSet<String>()
        repeat(50) { p.nextSlow(false).forEach { core += it.key } }
        assertEquals(PollPlan.CORE_SLOW, core)
        val every = HashSet<String>()
        repeat(100) { p.nextSlow(true).forEach { every += it.key } }
        assertTrue("wastegate" in every)
        assertTrue(every.containsAll(PollPlan.CORE_SLOW))
    }

    @Test fun holdsFollowTheAdapterWithinLimits() {
        val p = plan(true)
        assertEquals(DataHub.STALE_MS, p.fastHoldMs()) // nothing measured yet
        repeat(20) { p.record(7, 2000) }
        assertEquals(5000L, p.fastHoldMs())
        assertEquals(25000L, p.slowHoldMs(false)) // 10 values, 2 a cycle, 2 s a cycle, x 2.5
        assertEquals(52500L, p.slowHoldMs(true)) // 21 values
        repeat(20) { p.record(7, 8000) }
        assertEquals(PollPlan.MAX_HOLD_MS, p.slowHoldMs(true))
        val q = plan(false)
        repeat(20) { q.record(12, 480) }
        assertEquals(DataHub.STALE_MS, q.fastHoldMs())
    }
}
