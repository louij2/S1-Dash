package com.louij2.s1dash

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns raw accelerometer readings (device axes, m/s², gravity included) into the g-forces
 * the driver feels: lateral (+ = pushed right, so a left turn) and longitudinal (+ = pushed
 * forward, so braking). Gravity is learned while the car stands still, so the screen can be
 * mounted at any tilt; the screen's left-right edge is taken as across the car.
 */
class GFrame {
    private var smooth: DoubleArray? = null
    private val window = ArrayDeque<Pair<Long, DoubleArray>>()
    var gravity: DoubleArray? = null
        private set

    /** Returns (lateral g, longitudinal g), or null until there's a gravity estimate. */
    fun feed(raw: DoubleArray, rotation: Int, speedKmh: Double?, nowMs: Long): Pair<Double, Double>? {
        val s = smooth?.let { p -> DoubleArray(3) { p[it] + ALPHA * (raw[it] - p[it]) } } ?: raw.copyOf()
        smooth = s
        window.addLast(nowMs to s)
        while (window.first().first < nowMs - STEADY_MS) window.removeFirst()
        learnGravity(speedKmh, nowMs)
        val g = gravity ?: return null

        val up = unit(g) ?: return null
        val r0 = screenRight(rotation)
        val right = unit(sub(r0, scale(up, dot(r0, up)))) ?: return null // screen edge pointing up: no across-car axis
        val forward = cross(up, right)
        val felt = sub(g, s) // minus the car's acceleration
        return dot(felt, right) / G to dot(felt, forward) / G
    }

    private fun learnGravity(speedKmh: Double?, nowMs: Long) {
        if (window.size < 5 || nowMs - window.first().first < STEADY_MS * 0.8) return
        val mean = DoubleArray(3) { i -> window.sumOf { it.second[i] } / window.size }
        val steady = window.all { (_, v) -> (0..2).all { abs(v[it] - mean[it]) < STEADY_SPREAD } }
        if (!steady) { if (gravity == null) gravity = mean; return }
        // Stopped, or (with no speed to go on) a reading that is gravity alone.
        val still = if (speedKmh != null) speedKmh < 1.0 else abs(norm(mean) - G) < STILL_TOLERANCE
        if (still || gravity == null) gravity = mean
    }

    companion object {
        const val G = 9.80665
        private const val ALPHA = 0.25
        private const val STEADY_MS = 1500L
        private const val STEADY_SPREAD = 0.3   // m/s² either side of the mean
        private const val STILL_TOLERANCE = 0.1 // m/s², ~0.01 g

        /** The screen's rightward edge in device axes, for each display rotation. */
        fun screenRight(rotation: Int): DoubleArray = when (rotation) {
            Surface.ROTATION_90 -> doubleArrayOf(0.0, 1.0, 0.0)
            Surface.ROTATION_180 -> doubleArrayOf(-1.0, 0.0, 0.0)
            Surface.ROTATION_270 -> doubleArrayOf(0.0, -1.0, 0.0)
            else -> doubleArrayOf(1.0, 0.0, 0.0)
        }

        private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
        private fun scale(a: DoubleArray, k: Double) = DoubleArray(3) { a[it] * k }
        private fun norm(a: DoubleArray) = sqrt(dot(a, a))
        private fun unit(a: DoubleArray): DoubleArray? = norm(a).let { n -> if (n < 1e-3) null else scale(a, 1 / n) }
        private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    }
}

/** Feeds the device's accelerometer into the hub as gLat / gLon, 20 times a second. */
class GSensor(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val frame = GFrame()
    private var lastPut = 0L

    fun start() {
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc == null) { DataHub.status["g"] = "no motion sensor"; return }
        DataHub.status["g"] = "learning"
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sm.unregisterListener(this)

    @Suppress("DEPRECATION")
    private fun rotation(): Int = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    override fun onSensorChanged(e: SensorEvent) {
        val now = System.currentTimeMillis()
        val speed = DataHub.get("ecuSpeed") ?: DataHub.get("speed")
        val g = frame.feed(doubleArrayOf(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble()), rotation(), speed, now) ?: return
        if (now - lastPut < 50) return
        lastPut = now
        DataHub.status["g"] = "ok"
        DataHub.put("gLat", g.first)
        DataHub.put("gLon", g.second)
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
