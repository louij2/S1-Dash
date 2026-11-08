package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarRowsTest {
    private fun live(vararg v: Pair<String, Double>, status: String? = "connected") = CarRows.live({ mapOf(*v)[it] }, status)

    @Test fun showsValuesWithUnits() {
        val r = live("boost" to 1.274, "oil" to 92.4)
        assertEquals("Boost 1.27 bar", r.first { it.id == "v:Boost" }.title)
        assertEquals("Oil 92 °C", r.first { it.id == "v:Oil" }.title)
    }

    @Test fun missingValuesShowDashesNotZero() {
        assertEquals("Boost --", live().first { it.id == "v:Boost" }.title)
    }

    @Test fun clutchLoadIsLabelledAnEstimate() {
        assertEquals("Estimate", live("clutchLoad" to 22.0).first { it.id == "v:Clutch load" }.subtitle)
    }

    @Test fun warningsComeFirst() {
        assertEquals("Clutch slipping", live("clutchWarn" to 1.0, "clutchSlip" to 8.0)[0].title)
        assertEquals("Clutch hot", live("clutchLoad" to 95.0)[0].title)
        assertEquals("Oil hot", live("oil" to 131.0)[0].title)
        assertEquals("Coolant hot", live("coolant" to 111.0)[0].title)
        assertEquals("Boost --", live("oil" to 100.0, "coolant" to 90.0, "clutchLoad" to 20.0)[0].title)
    }

    @Test fun statusIsShown() {
        assertEquals("no adapter", live(status = "no adapter").first { it.id == "v:ecu" }.subtitle)
    }

    @Test fun rowsCompareEqualSoUnchangedListsAreNotResent() {
        assertEquals(live("oil" to 92.2), live("oil" to 91.9))
    }

    @Test fun modeIdsRoundTripAndRejectNonsense() {
        CarRows.MODES.indices.forEach { assertEquals(it, CarRows.modeFrom(CarRows.modeId(it))) }
        assertNull(CarRows.modeFrom("v:Boost"))
        assertNull(CarRows.modeFrom("mode:9"))
        assertNull(CarRows.modeFrom("mode:x"))
        assertNull(CarRows.modeFrom(null))
    }

    @Test fun activeModeIsMarked() {
        val r = CarRows.modes(2, "connected")
        assertEquals(6, r.size)
        assertEquals("50 : 50 · active", r[2].title)
        assertFalse(r[1].title.contains("active"))
        assertTrue(r[0].subtitle.contains("connected"))
    }
}
