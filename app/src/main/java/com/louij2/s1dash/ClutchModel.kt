package com.louij2.s1dash

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Clutch slip and an estimated clutch heat for a manual gearbox, which has no
 * clutch temperature sensor and no gearbox control unit. Everything comes from
 * engine rpm, vehicle speed, current gear and engine torque.
 *
 * Each gear's rpm per km/h is learned while cruising, so tyre size and final drive
 * never need typing in, and any fixed error in the speed scaling cancels out. The
 * ECU reports no clutch pedal or neutral, so anything that looks like slip with the
 * car stationary is ignored: free revs and a held hill start look the same.
 *
 * The heat figure is an estimate on an uncalibrated scale, not a temperature.
 * Synchronized because [update] runs on the source thread and [reset] on the UI's.
 */
class ClutchModel(
    initial: Map<Int, Double> = emptyMap(),
    private val onLearned: (Map<Int, Double>) -> Unit = {},
) {
    data class Output(val slipPct: Double?, val loadPct: Double, val warn: Boolean)

    private val ratios = HashMap(initial)
    private var lastMs = 0L
    private var lastGear = -1
    private var gearSince = 0L
    private var energyJ = 0.0
    private var windowStart = 0L
    private var windowFirst = 0.0
    private var windowSum = 0.0
    private var windowCount = 0
    private var slipSince = 0L
    private var warnUntil = 0L

    @Synchronized
    fun learned(): Map<Int, Double> = HashMap(ratios)

    /** Forget the learned ratios, for example after a tyre or gearbox change. */
    @Synchronized
    fun reset() {
        ratios.clear()
        windowCount = 0
        onLearned(HashMap(ratios))
    }

    @Synchronized
    fun update(nowMs: Long, rpm: Double, kmh: Double, gear: Int, torqueNm: Double): Output {
        val dt = if (lastMs == 0L) 0.0 else max(0.0, (nowMs - lastMs) / 1000.0)
        lastMs = nowMs
        // Cools on elapsed time, so a lost connection doesn't freeze the estimate.
        energyJ *= exp(-dt / COOL_TAU_S)

        if (gear != lastGear) {
            lastGear = gear
            gearSince = nowMs
            windowCount = 0
        }
        val settled = nowMs - gearSince >= GEAR_SETTLE_MS

        if (settled) learn(nowMs, rpm, kmh, gear, torqueNm) else windowCount = 0

        val ratio = ratios[gear]
        val slipRpm = if (settled && ratio != null && kmh >= MOVING_KMH && rpm > 0) max(0.0, rpm - ratio * kmh) else null
        val slipPct = slipRpm?.let { it / rpm * 100.0 }

        if (slipRpm != null && slipPct != null && slipPct >= DEADBAND_PCT && torqueNm >= HEAT_MIN_NM) {
            val watts = torqueNm * slipRpm * RPM_TO_RAD_S
            energyJ = min(MAX_ENERGY_J, energyJ + watts * min(dt, MAX_STEP_S))
        }

        val slipping = slipPct != null && slipPct >= WARN_SLIP_PCT && gear >= WARN_MIN_GEAR &&
            kmh >= WARN_MIN_KMH && torqueNm >= WARN_MIN_NM
        if (!slipping) slipSince = 0L
        else {
            if (slipSince == 0L) slipSince = nowMs
            if (nowMs - slipSince >= WARN_AFTER_MS) warnUntil = nowMs + WARN_HOLD_MS
        }
        return Output(slipPct, energyJ / FULL_SCALE_J * 100.0, nowMs < warnUntil)
    }

    /** Commits a window of steady, light-load cruising; slip would only ever push the ratio up. */
    private fun learn(nowMs: Long, rpm: Double, kmh: Double, gear: Int, torqueNm: Double) {
        val ok = gear in 1..MAX_GEAR && rpm >= LEARN_MIN_RPM && kmh >= LEARN_MIN_KMH &&
            torqueNm >= LEARN_MIN_NM && torqueNm <= LEARN_MAX_NM
        if (!ok) { windowCount = 0; return }
        val r = rpm / kmh
        if (windowCount == 0 || abs(r - windowFirst) / windowFirst > LEARN_STEADY_FRAC) {
            windowStart = nowMs
            windowFirst = r
            windowSum = r
            windowCount = 1
            return
        }
        windowSum += r
        windowCount++
        if (nowMs - windowStart < LEARN_WINDOW_MS) return
        val mean = windowSum / windowCount
        windowCount = 0
        val old = ratios[gear]
        if (old != null && abs(mean - old) / old > LEARN_OUTLIER_FRAC) return
        // A lower gear always has more rpm per km/h; anything else is a misread gear.
        if (ratios.any { (g, v) -> (g < gear && v <= mean) || (g > gear && v >= mean) }) return
        val next = if (old == null) mean else old + (mean - old) * LEARN_ALPHA
        ratios[gear] = next
        if (old == null || abs(next - old) / old > SAVE_CHANGE_FRAC) onLearned(HashMap(ratios))
    }

    companion object {
        const val MAX_GEAR = 7               // the gear offset is unproven, see EcuPids
        const val GEAR_SETTLE_MS = 700L      // ignores the shift itself and a late gear reading
        const val MOVING_KMH = 3.0
        const val DEADBAND_PCT = 2.0         // rpm and speed are read a moment apart during a pull
        const val HEAT_MIN_NM = 20.0

        const val COOL_TAU_S = 240.0
        const val FULL_SCALE_J = 300_000.0   // about two hard launches; tune against real drives
        const val MAX_ENERGY_J = 2 * FULL_SCALE_J
        const val MAX_STEP_S = 2.0
        const val LOAD_WARM_PCT = 70.0
        const val LOAD_HOT_PCT = 90.0

        const val WARN_SLIP_PCT = 4.0
        const val WARN_MIN_GEAR = 3
        const val WARN_MIN_KMH = 40.0        // below this, wheelspin looks the same
        const val WARN_MIN_NM = 120.0
        const val WARN_AFTER_MS = 1000L
        const val WARN_HOLD_MS = 5000L

        const val LEARN_MIN_RPM = 1200.0     // also rejects idling in neutral
        const val LEARN_MIN_KMH = 8.0
        const val LEARN_MIN_NM = 15.0        // rejects a coast with the clutch down
        const val LEARN_MAX_NM = 120.0
        const val LEARN_STEADY_FRAC = 0.015
        const val LEARN_WINDOW_MS = 3000L
        const val LEARN_OUTLIER_FRAC = 0.15
        const val LEARN_ALPHA = 0.1
        const val SAVE_CHANGE_FRAC = 0.002

        private const val RPM_TO_RAD_S = 2 * Math.PI / 60.0

        fun encode(m: Map<Int, Double>): String =
            m.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }

        fun decode(s: String): Map<Int, Double> = s.split(",").mapNotNull { e ->
            val p = e.split(":")
            val g = p.getOrNull(0)?.toIntOrNull()
            val r = p.getOrNull(1)?.toDoubleOrNull()
            if (g != null && r != null && r > 0) g to r else null
        }.toMap()
    }
}
