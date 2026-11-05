package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClutchModelTest {
    private val third = 30.0 // rpm per km/h
    private var now = 1_000L

    /** Feeds [seconds] at 5 Hz and returns the last output. */
    private fun run(m: ClutchModel, seconds: Double, rpm: Double, kmh: Double, gear: Int, nm: Double): ClutchModel.Output {
        var out = m.update(now, rpm, kmh, gear, nm)
        repeat((seconds * 5).toInt()) {
            now += 200
            out = m.update(now, rpm, kmh, gear, nm)
        }
        return out
    }

    private fun seeded() = ClutchModel(mapOf(1 to 120.0, 2 to 60.0, 3 to third, 4 to 22.0))

    @Test fun learnsRatioWhileCruising() {
        var saved: Map<Int, Double> = emptyMap()
        val m = ClutchModel(onLearned = { saved = it })
        run(m, 6.0, 2400.0, 80.0, 3, 60.0)
        assertEquals(30.0, m.learned()[3]!!, 0.01)
        assertEquals(30.0, saved[3]!!, 0.01)
    }

    @Test fun doesNotLearnUnderLoadOrWhileAccelerating() {
        val m = ClutchModel()
        run(m, 6.0, 3000.0, 80.0, 3, 300.0)
        assertTrue(m.learned().isEmpty())
        var rpm = 1500.0
        repeat(40) { now += 200; rpm += 60; m.update(now, rpm, 80.0, 3, 60.0) }
        assertTrue(m.learned().isEmpty())
    }

    @Test fun doesNotLearnNeutralIdleOrCoasting() {
        val m = ClutchModel()
        run(m, 6.0, 800.0, 50.0, 1, 20.0)
        run(m, 6.0, 2000.0, 50.0, 3, -10.0)
        assertTrue(m.learned().isEmpty())
    }

    @Test fun rejectsARatioOutOfGearOrder() {
        val m = ClutchModel(mapOf(4 to 22.0))
        run(m, 6.0, 2000.0, 100.0, 3, 60.0) // 20 rpm per km/h can't be below 4th's 22
        assertNull(m.learned()[3])
    }

    @Test fun noSlipWithoutALearnedRatio() {
        assertNull(ClutchModel().update(now, 3000.0, 80.0, 3, 200.0).slipPct)
    }

    @Test fun gripIsZeroSlipAndNoHeat() {
        val out = run(seeded(), 30.0, 3000.0, 100.0, 3, 300.0)
        assertEquals(0.0, out.slipPct!!, 0.001)
        assertEquals(0.0, out.loadPct, 0.001)
        assertFalse(out.warn)
    }

    @Test fun slipPercentIsAgainstEngineSpeed() {
        val m = seeded()
        run(m, 1.0, 3000.0, 90.0, 3, 300.0)
        val out = run(m, 0.2, 3000.0, 90.0, 3, 300.0)
        assertEquals(10.0, out.slipPct!!, 0.001)
    }

    @Test fun heatIsTorqueTimesSlipSpeed() {
        val m = seeded()
        run(m, 1.0, 3000.0, 90.0, 3, 300.0)
        val before = m.update(now, 3000.0, 90.0, 3, 300.0).loadPct
        now += 1000
        val after = m.update(now, 3000.0, 90.0, 3, 300.0).loadPct
        // 300 Nm x 300 rpm = 9.4 kW for 1 s, of a 300 kJ scale.
        assertEquals(3.14, after - before, 0.05)
    }

    @Test fun sustainedSlipUnderLoadWarnsThenHolds() {
        val m = seeded()
        assertFalse(run(m, 0.8, 3000.0, 95.0, 3, 300.0).warn) // settling, then under 1 s
        assertTrue(run(m, 1.2, 3000.0, 95.0, 3, 300.0).warn)
        assertTrue(run(m, 3.0, 2850.0, 95.0, 3, 300.0).warn)  // grip again: held for a glance
        assertFalse(run(m, 3.0, 2850.0, 95.0, 3, 300.0).warn)
    }

    @Test fun briefSlipDoesNotWarn() {
        val m = seeded()
        run(m, 1.0, 2850.0, 95.0, 3, 300.0)
        repeat(5) {
            assertFalse(run(m, 0.4, 3000.0, 95.0, 3, 300.0).warn)
            run(m, 0.2, 2850.0, 95.0, 3, 300.0)
        }
    }

    @Test fun noWarningInLowGearsOrLightLoad() {
        val m = seeded()
        assertFalse(run(m, 4.0, 3000.0, 45.0, 2, 300.0).warn) // 2nd, wheelspin territory
        assertFalse(run(m, 4.0, 3000.0, 95.0, 3, 80.0).warn)  // light load
    }

    @Test fun stationaryRevsAddNoHeat() {
        val out = run(seeded(), 20.0, 4000.0, 0.0, 1, 100.0)
        assertNull(out.slipPct)
        assertEquals(0.0, out.loadPct, 0.001)
    }

    @Test fun slipIsHeldOffWhileANewGearSettles() {
        val m = seeded()
        run(m, 2.0, 2200.0, 100.0, 4, 80.0)
        // Now really in 3rd, but the ECU still reads 4th for one sample.
        assertEquals(26.6, m.update(now + 200, 3000.0, 100.0, 4, 80.0).slipPct!!, 0.1)
        now += 400
        assertNull(m.update(now, 3000.0, 100.0, 3, 80.0).slipPct)
        now += 1000
        assertNotNull(m.update(now, 3000.0, 100.0, 3, 80.0).slipPct)
    }

    @Test fun launchEnergyAndCooling() {
        val m = seeded()
        // A launch in 1st: 300 Nm, 2800 rpm slip speed, 2 s = 176 kJ of a 300 kJ scale.
        run(m, 0.8, 4000.0, 10.0, 1, 0.0)
        val hot = run(m, 2.0, 4000.0, 10.0, 1, 300.0).loadPct
        assertEquals(58.6, hot, 1.0)
        now += 240_000 // one time constant, connection lost in between
        val cooled = m.update(now, 0.0, 0.0, 1, 0.0).loadPct
        assertEquals(hot / Math.E, cooled, 1.0)
    }

    @Test fun energyIsBounded() {
        val out = run(seeded(), 600.0, 6000.0, 10.0, 1, 370.0)
        assertTrue(out.loadPct <= 200.0)
    }

    @Test fun resetForgetsAndSaves() {
        var saved: Map<Int, Double>? = null
        val m = ClutchModel(mapOf(3 to third), onLearned = { saved = it })
        m.reset()
        assertTrue(m.learned().isEmpty())
        assertTrue(saved!!.isEmpty())
    }

    @Test fun encodeRoundTripsAndToleratesJunk() {
        val r = mapOf(1 to 120.5, 3 to 30.25)
        assertEquals(r, ClutchModel.decode(ClutchModel.encode(r)))
        assertTrue(ClutchModel.decode("").isEmpty())
        assertEquals(mapOf(2 to 60.0), ClutchModel.decode("x:1,2:60.0,3:-1,4"))
    }
}
