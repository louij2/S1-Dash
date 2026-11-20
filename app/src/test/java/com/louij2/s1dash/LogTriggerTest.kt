package com.louij2.s1dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogTriggerTest {
    @Test fun eitherMatchesTheSimosToolsProfile() {
        val t = LogTrigger("either")
        assertTrue(t.fires(2.0, 10.0))      // cruise held
        assertTrue(t.fires(0.0, 71.0))      // a pull
        assertFalse(t.fires(1.0, 70.0))     // both strictly greater-than, as in "cruise > 1 | pedal > 70"
        assertFalse(t.fires(null, null))
    }

    @Test fun singleModes() {
        assertFalse(LogTrigger("cruise").fires(0.0, 99.0))
        assertFalse(LogTrigger("pedal").fires(5.0, 0.0))
        assertFalse(LogTrigger("off").fires(5.0, 99.0))
        assertFalse(LogTrigger("off").armed)
    }

    @Test fun keepsRecordingForTheTailThenStops() {
        val t = LogTrigger("pedal")
        assertFalse(t.recording(0, null, 20.0))
        assertTrue(t.recording(1000, null, 90.0))
        assertTrue(t.recording(2500, null, 30.0))   // 1.5 s after the pull: still in the tail
        assertTrue(t.recording(3000, null, 30.0))   // exactly 2 s
        assertFalse(t.recording(3100, null, 30.0))
        assertTrue(t.recording(4000, null, 80.0))   // a new pull starts again
    }
}
