package com.louij2.s1dash

import com.louij2.s1dash.CalRules.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalRulesTest {
    private fun cal(size: Int = CalRules.CAL_SIZE, box: String = "8X0906259 B"): ByteArray {
        val b = ByteArray(size)
        if (size >= 0x6B) box.toByteArray().copyInto(b, 0x60)
        return b
    }

    private fun good(
        demo: Boolean = false, simos: Boolean = true, status: String? = "connected (SimosTools dongle)", rpm: Double? = 0.0,
        speed: Double? = 0.0, volts: Double? = 12.8, part: String? = "8X0906259 B", box: String = "8X0906259 B",
        codes: Boolean = true, intact: Boolean = true,
    ) = CalRules.preflight(demo, simos, status, rpm, speed, volts, part, box, codes, intact)

    private fun List<CalRules.Check>.state(label: String) = first { it.label == label }.state

    @Test fun inspectsACalFile() {
        val i = CalRules.inspect(cal())
        assertEquals("8X0906259 B", i.boxCode)
        assertNull(i.problem)
        assertNull(i.warning)
        assertEquals(64, i.sha256.length)
    }

    @Test fun refusesFullBinsAndTinyFiles() {
        assertNotNull(CalRules.inspect(cal(4_000_000)).problem)
        assertNotNull(CalRules.inspect(cal(1000)).problem)
    }

    @Test fun warnsOnOddSizeOrNoSoftwareNumber() {
        assertNotNull(CalRules.inspect(cal(600_000)).warning)
        val blank = CalRules.inspect(ByteArray(CalRules.CAL_SIZE))
        assertEquals("", blank.boxCode)
        assertNotNull(blank.warning)
    }

    @Test fun softwareNumberComparesTheFamily() {
        assertEquals(State.PASS, CalRules.softwareCheck("8X0906259 B", "8X0906259B").state)
        assertEquals(State.WARN, CalRules.softwareCheck("8X0906259 B", "8X0906259 H").state)
        assertEquals(State.FAIL, CalRules.softwareCheck("8X0906259 B", "8V0906259 H").state)
        assertEquals(State.WAIT, CalRules.softwareCheck(null, "8X0906259 B").state)
        assertEquals(State.WARN, CalRules.softwareCheck("8X0906259 B", "").state)
    }

    @Test fun goodCarCanFlash() {
        assertTrue(CalRules.canFlash(good()))
    }

    @Test fun anythingBadOrUnknownBlocks() {
        assertFalse(CalRules.canFlash(good(rpm = 800.0)))
        assertFalse(CalRules.canFlash(good(speed = 10.0)))
        assertFalse(CalRules.canFlash(good(volts = 11.5)))
        assertFalse(CalRules.canFlash(good(demo = true)))
        assertFalse(CalRules.canFlash(good(simos = false)))
        assertFalse(CalRules.canFlash(good(intact = false)))
        assertFalse(CalRules.canFlash(good(box = "8V0906259 H")))
        assertFalse(CalRules.canFlash(good(status = "no adapter")))
        assertFalse(CalRules.canFlash(good(status = "connected (ELM327)")))
        // No reading is never treated as safe.
        assertFalse(CalRules.canFlash(good(rpm = null)))
        assertFalse(CalRules.canFlash(good(speed = null)))
        assertFalse(CalRules.canFlash(good(volts = null)))
        assertFalse(CalRules.canFlash(good(part = null)))
        assertFalse(CalRules.canFlash(good(codes = false)))
    }

    @Test fun lowishBatteryWarnsButAllows() {
        val c = good(volts = 12.3)
        assertEquals(State.WARN, c.state("Battery"))
        assertTrue(CalRules.canFlash(c))
    }

    private fun feed(w: CalRules.IdleWatch, fromMs: Long, seconds: Int, rpm: Double?, trim: Double = 0.0, knock: Double = 0.0): Long {
        var t = fromMs
        repeat(seconds * 5) { t += 200; w.update(t, rpm, trim, trim, knock) }
        return t
    }

    @Test fun idleWaitsThenPasses() {
        val w = CalRules.IdleWatch()
        assertEquals(State.WAIT, w.check().state)
        var t = feed(w, 1000, 5, 0.0)
        assertEquals(State.WAIT, w.check().state)
        t = feed(w, t, 1, 250.0) // cranking
        assertEquals(State.WAIT, w.check().state)
        feed(w, t, 16, 850.0)
        assertEquals(State.PASS, w.check().state)
    }

    @Test fun aStallIsReported() {
        val w = CalRules.IdleWatch()
        var t = feed(w, 1000, 5, 850.0)
        t = feed(w, t, 2, 0.0)
        assertEquals(State.WARN, w.check().state)
        feed(w, t, 16, 850.0)
        assertEquals(State.WARN, w.check().state)
        assertEquals(1, w.stalls)
    }

    @Test fun revvingRestartsTheWatch() {
        val w = CalRules.IdleWatch()
        var t = feed(w, 1000, 10, 850.0)
        t = feed(w, t, 1, 3000.0)
        assertEquals(State.WAIT, w.check().state)
        assertEquals(0L, w.stableMs)
        feed(w, t, 16, 850.0)
        assertEquals(State.PASS, w.check().state)
    }

    @Test fun badTrimOrKnockWarn() {
        val a = CalRules.IdleWatch()
        feed(a, 1000, 16, 850.0, trim = 30.0)
        assertEquals(State.WARN, a.check().state)
        val b = CalRules.IdleWatch()
        feed(b, 1000, 16, 850.0, knock = -4.0)
        assertEquals(State.WARN, b.check().state)
    }

    private val pass = CalRules.Check("Idle", State.PASS, "")

    private fun after(
        status: String? = "connected (SimosTools dongle)", partB: String? = "P", partA: String? = "P", logB: String? = "A", logA: String? = "B",
        codesB: Set<String>? = setOf("P3019-00"), codesA: Set<String>? = emptySet(),
    ) = CalRules.afterFlash(status, partB, partA, logB, logA, codesB, codesA, pass)

    @Test fun cleanFlashAllPasses() {
        assertTrue(after().all { it.state == State.PASS })
    }

    @Test fun anUnchangedFlashRecordIsFlagged() {
        assertEquals(State.WARN, after(logA = "A").state("Flash recorded"))
        assertEquals(State.WARN, after(logA = "").state("Flash recorded"))
        assertEquals(State.WARN, after(logB = "").state("Flash recorded"))
    }

    @Test fun onlyNewCodesAreFlagged() {
        val same = after(codesB = setOf("P3019-00"), codesA = setOf("P3019-00"))
        assertEquals(State.PASS, same.state("Fault codes"))
        val fresh = after(codesB = setOf("P3019-00"), codesA = setOf("P3019-00", "U0155-00")).first { it.label == "Fault codes" }
        assertEquals(State.WARN, fresh.state)
        assertTrue(fresh.detail.contains("U0155-00"))
        assertFalse(fresh.detail.contains("P3019-00"))
    }

    @Test fun aChangedSoftwareNumberFails() {
        assertEquals(State.FAIL, after(partA = "Q").state("Software number"))
    }

    @Test fun unreadValuesWait() {
        val c = after(status = "no adapter", partA = null, logA = null, codesA = null)
        listOf("ECU answers", "Software number", "Flash recorded", "Fault codes").forEach { assertEquals(State.WAIT, c.state(it)) }
    }
}
