package com.louij2.s1dash

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the ELM code against simulated adapters. A simulation only shows what was modelled; it isn't a car. */
class ElmSimTest {
    private val clock = SimClock()
    private fun rig(p: FakeElm.Profile): Pair<FakeElm, ElmProtocol> {
        val f = FakeElm(clock, p)
        return f to ElmProtocol(f.input, f.output, clock)
    }
    private fun did(d: Int) = byteArrayOf(0x22, (d shr 8).toByte(), d.toByte())
    private val rpm = byteArrayOf(0x62, 0xF4.toByte(), 0x0C, 0x10, 0x20)
    private val genuine = FakeElm.Profile("genuine")

    @Test fun genuineReadsAValue() {
        val (_, e) = rig(genuine)
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun echoThatWontTurnOffIsIgnored() {
        val (_, e) = rig(genuine.copy(echoStuck = true))
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun spacesThatWontTurnOffAreIgnored() {
        val (_, e) = rig(genuine.copy(spacesStuck = true))
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun nullCharactersAreIgnored() {
        val (_, e) = rig(genuine.copy(nulNoise = true))
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun slowResetDoesNotKnockRepliesOutOfStep() {
        val (_, e) = rig(genuine.copy(atzMs = 3500))
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun cloneWithoutReceiveFilterStillWorks() {
        val (_, e) = rig(genuine.copy(rejects = setOf("CRA", "FC", "AT1")))
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun refusingTheEcuAddressIsAnError() {
        val (_, e) = rig(genuine.copy(rejects = setOf("SH")))
        assertEquals(false, e.open())
        assertTrue(e.lastError, e.lastError.contains("SH"))
    }
    @Test fun cloneWithoutResponseCountStillWorks() {
        val (_, e) = rig(genuine.copy(hint = false, version = "ELM327 v1.2"))
        assertTrue(e.open())
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
        assertArrayEquals(rpm, e.request(did(0xF40C), 600, true))
    }
    @Test fun multiFrameReplyIsComplete() {
        val (_, e) = rig(genuine)
        assertTrue(e.open())
        assertEquals(21, assertNotNullAnd(e.request(did(0xF15B), 1500)).size)
    }
    private fun assertNotNullAnd(b: ByteArray?): ByteArray { assertNotNull(b); return b!! }
    @Test fun cutOffMultiFrameIsRefusedNotTruncated() {
        val (_, e) = rig(genuine.copy(multiFrame = false))
        assertTrue(e.open())
        assertEquals(null, e.request(did(0xF15B), 1500))
    }
    @Test fun pendingThenAnswerInOneBlock() {
        val (_, e) = rig(genuine.copy(pendingFirst = true))
        assertTrue(e.open())
        val r = assertNotNullAnd(e.request(did(0xF15B), 1500))
        assertEquals(0x62.toByte(), r[0])
        assertEquals(21, r.size)
    }
    @Test fun slowClearIsHeardWithoutPending() {
        val (_, e) = rig(genuine.copy(clearMs = 600))
        assertTrue(e.open())
        val r = e.request(byteArrayOf(0x14, -1, -1, -1), 3000)
        assertNotNull(r)
        assertEquals(0x54.toByte(), r!![0])
    }
    @Test fun aSlowClearDoesNotLeaveTheAdapterSlow() {
        val (f, e) = rig(genuine.copy(adaptive = false))
        assertTrue(e.open())
        e.request(byteArrayOf(0x14, -1, -1, -1), 3000)
        val t = clock.t
        e.request(did(0x202F))
        assertTrue("took ${clock.t - t} ms after ${f.requests}", clock.t - t < 400)
    }
    @Test fun lateRepliesNeverPassAsTheWrongValue() {
        val (_, e) = rig(genuine.copy(lateEvery = 5))
        assertTrue(e.open())
        var good = 0
        repeat(60) {
            val want = if (it % 2 == 0) 0xF40C else 0x2033 // alternating, so a late reply would show as the wrong one
            val r = e.request(did(want), 600, true)
            if (r != null) {
                assertEquals(want, (r[1].toInt() and 0xFF) shl 8 or (r[2].toInt() and 0xFF))
                good++
            }
        }
        assertTrue("only $good of 60 good", good >= 40)
    }

    @Test fun responseCountIsUsedAndSavesTime() {
        val (f, e) = rig(genuine.copy(adaptive = false))
        assertTrue(e.open())
        val t = clock.t
        e.request(did(0xF40C), 600, true)
        assertTrue(f.requests.last(), f.requests.last().endsWith("1"))
        assertTrue("took ${clock.t - t} ms", clock.t - t < 60)
    }

    @Test fun longRepliesAreNotHinted() {
        val (f, e) = rig(genuine)
        assertTrue(e.open())
        e.request(did(0xF15B), 1500)
        assertEquals("22F15B", f.requests.last())
    }

    @Test fun cutOffReplySaysWhy() {
        val (_, e) = rig(genuine.copy(multiFrame = false))
        assertTrue(e.open())
        e.request(did(0xF15B), 1500)
        assertTrue(e.lastError, e.lastError.contains("cut off"))
    }

    // ---------- whole-run simulation: how much of the time does each dial have a reading? ----------

    private class Run(val shown: Map<String, Double>, val updatesPerSecond: Double, val reduced: Boolean?)

    /** Dial availability over [seconds] of virtual time. A null plan means the old fixed lists and fixed 3 s hold. */
    private fun run(p: FakeElm.Profile, plan: PollPlan?, wantAll: Boolean = false, seconds: Int = 120): Run {
        val (_, e) = rig(p)
        assertTrue(e.open())
        val last = HashMap<String, Long>()
        val shown = HashMap<String, Long>()
        val oldFast = EcuPids.ALL.filter { it.fast }
        val oldSlow = EcuPids.ALL.filter { !it.fast }
        var idx = 0
        val start = clock.t
        var cycles = 0
        val watch = listOf("rpm", "map", "ambient", "oil", "coolant", "knock1", "wastegate")
        var fastHold = 3000L
        var slowHold = 3000L
        fun tick(from: Long) = watch.forEach { k ->
            val hold = if (plan != null && k in plan.fast.map { it.key }) fastHold else if (plan != null) slowHold else 3000L
            if (last[k]?.let { clock.t - it <= hold } == true) shown[k] = (shown[k] ?: 0L) + (clock.t - from)
        }
        while (clock.t - start < seconds * 1000L) {
            val c0 = clock.t
            val list = if (plan != null) plan.fast + plan.nextSlow(wantAll)
            else oldFast + List(3) { oldSlow[(idx++) % oldSlow.size] }
            for (pid in list) {
                val t0 = clock.t
                val r = e.request(did(pid.did), 600, plan != null)
                if (r != null && r.size >= 3 && r[0] == 0x62.toByte()) last[pid.key] = clock.t
                tick(t0)
            }
            plan?.record(list.size, clock.t - c0)
            if (plan != null) { fastHold = plan.fastHoldMs(); slowHold = plan.slowHoldMs(wantAll) }
            cycles++
        }
        val total = (clock.t - start).toDouble()
        return Run(watch.associateWith { 100.0 * (shown[it] ?: 0L) / total }, cycles * 1000.0 / total, plan?.reduced)
    }

    private val worstClone = FakeElm.Profile("clone", version = "ELM327 v1.2", adaptive = false, hint = false, echoStuck = true,
        rejects = setOf("CRA", "FC", "AT1"))

    private fun report(name: String, r: Run) = println(
        "SIM $name: ${"%.1f".format(r.updatesPerSecond)} cycles/s reduced=${r.reduced} " + r.shown.entries.joinToString(" ") { "${it.key}=${"%.0f".format(it.value)}%" })

    @Test fun oldPlanOnASlowCleanAdapterLosesTheSlowDials() {
        // Echo off so the old plan's numbers show timing alone.
        val r = run(worstClone.copy(echoStuck = false), null)
        report("old plan, slow adapter", r)
        assertTrue("oil ${r.shown["oil"]}", r.shown.getValue("oil") < 50)
    }

    @Test fun worstCloneKeepsTheCoreDials() {
        val r = run(worstClone, PollPlan(EcuPids.ALL, startReduced = true))
        report("new plan, worst clone", r)
        assertEquals(true, r.reduced)
        listOf("rpm", "map", "ambient", "oil", "coolant", "knock1").forEach { assertTrue("$it ${r.shown[it]}", r.shown.getValue(it) > 90) }
        assertEquals("not polled on the core dials screen", 0.0, r.shown.getValue("wastegate"), 0.01)
    }

    @Test fun worstCloneFetchesTheRestForTheEngineScreen() {
        val r = run(worstClone, PollPlan(EcuPids.ALL, startReduced = true), wantAll = true)
        report("new plan, worst clone, engine screen", r)
        assertTrue("wastegate ${r.shown["wastegate"]}", r.shown.getValue("wastegate") > 80)
    }

    @Test fun goodElmEarnsTheFullSet() {
        val r = run(genuine, PollPlan(EcuPids.ALL, startReduced = true))
        report("new plan, genuine ELM", r)
        assertEquals(false, r.reduced)
        listOf("rpm", "map", "oil", "wastegate").forEach { assertTrue("$it ${r.shown[it]}", r.shown.getValue(it) > 90) }
    }

    @Test fun lateRepliesPushAnAdapterToTheCoreDials() {
        val r = run(genuine.copy(lateEvery = 3), PollPlan(EcuPids.ALL, startReduced = false))
        report("new plan, unreliable adapter", r)
        assertEquals(true, r.reduced)
    }
}
