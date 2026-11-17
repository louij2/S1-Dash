package com.louij2.s1dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic accelerometer readings: what the sensor reads is the car's acceleration plus 1 g upwards. */
class GFrameTest {
    private val g = GFrame.G

    /** Device axes for a mount: right, up and forward of the car expressed in device coordinates. */
    private class Mount(val right: DoubleArray, val up: DoubleArray, val forward: DoubleArray)

    // A head unit standing upright in the dash, landscape: device +X right, +Y up, screen faces back (-Z forward).
    private val upright = Mount(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0))

    // The same, leaned back 30 degrees.
    private val tilted = run {
        val a = Math.toRadians(30.0)
        Mount(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(a), sin(a)), doubleArrayOf(0.0, sin(a), -cos(a)))
    }

    /** Raw reading for a car accelerating `lat` g to the right and `lon` g forwards. */
    private fun raw(m: Mount, lat: Double, lon: Double) =
        DoubleArray(3) { i -> (m.right[i] * lat + m.forward[i] * lon + m.up[i]) * g }

    /** Stands still for 2 s (learns gravity), then holds the given acceleration for 1 s. */
    private fun run(m: Mount, lat: Double, lon: Double, rotation: Int = 0, speedWhenMoving: Double? = 50.0): Pair<Double, Double> {
        val f = GFrame()
        var t = 0L
        repeat(40) { f.feed(raw(m, 0.0, 0.0), rotation, 0.0, t); t += 50 }
        var out: Pair<Double, Double>? = null
        repeat(20) { out = f.feed(raw(m, lat, lon), rotation, speedWhenMoving, t); t += 50 }
        assertNotNull(out)
        return out!!
    }

    @Test fun brakingPushesForward() {
        val (lat, lon) = run(upright, 0.0, -0.8) // car slows: acceleration points backwards
        assertEquals(0.0, lat, 0.01)
        assertEquals(0.8, lon, 0.01)
    }

    @Test fun rightTurnPushesLeftOnATiltedMount() {
        val (lat, lon) = run(tilted, 0.9, 0.0)
        assertEquals(-0.9, lat, 0.01)
        assertEquals(0.0, lon, 0.01)
    }

    @Test fun aLongSteadyCornerIsNotTakenForGravity() {
        val f = GFrame()
        var t = 0L
        repeat(40) { f.feed(raw(upright, 0.0, 0.0), 0, 0.0, t); t += 50 }
        var out: Pair<Double, Double>? = null
        repeat(200) { out = f.feed(raw(upright, 0.6, 0.0), 0, 80.0, t); t += 50 } // 10 s on a long bend
        assertEquals(-0.6, out!!.first, 0.01)
    }

    @Test fun aPhoneOnItsSideUsesTheScreensEdges() {
        // Rotation 90: the screen's right edge is the device's +Y; device +X points down.
        val side = Mount(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, -1.0))
        val (lat, lon) = run(side, -0.5, 0.3, rotation = android.view.Surface.ROTATION_90)
        assertEquals(0.5, lat, 0.01)
        assertEquals(-0.3, lon, 0.01)
    }
}
