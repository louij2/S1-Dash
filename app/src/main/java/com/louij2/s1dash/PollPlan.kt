package com.louij2.s1dash

import kotlin.math.max
import kotlin.math.min

/**
 * Which values to ask for, based on how fast the adapter really answers rather than
 * what it's called. A quick adapter gets everything. A slow one gets the core dials,
 * plus the rest only where a screen or the logger needs them. How long a reading
 * stays on screen follows how long it takes to come round again.
 */
class PollPlan(private val all: List<Pid>, startReduced: Boolean) {
    var reduced = startReduced
        private set
    private var msPerRequest = 0.0
    private var cycleMs = 0.0
    private var slowIdx = 0

    private val fullFast = all.filter { it.fast }
    private val fullSlow = all.filter { !it.fast }
    private val coreFast = all.filter { it.key in CORE_FAST }
    private val coreSlow = all.filter { it.key in CORE_SLOW }
    private val extras = all.filter { it.key !in CORE_FAST && it.key !in CORE_SLOW }

    private fun slowList(wantAll: Boolean) = when {
        !reduced -> fullSlow
        wantAll -> coreSlow + extras
        else -> coreSlow
    }

    val fast: List<Pid> get() = if (reduced) coreFast else fullFast

    /** The few less urgent values for this cycle, taking turns. */
    fun nextSlow(wantAll: Boolean): List<Pid> {
        val list = slowList(wantAll)
        return List(if (reduced) 2 else 3) { list[(slowIdx++) % list.size] }
    }

    fun record(requests: Int, elapsedMs: Long) {
        if (requests <= 0) return
        val per = elapsedMs.toDouble() / requests
        msPerRequest = if (msPerRequest == 0.0) per else msPerRequest + (per - msPerRequest) * 0.2
        cycleMs = if (cycleMs == 0.0) elapsedMs.toDouble() else cycleMs + (elapsedMs - cycleMs) * 0.2
        val was = reduced
        if (msPerRequest >= REDUCED_ABOVE_MS) reduced = true else if (msPerRequest <= FULL_BELOW_MS) reduced = false
        if (was != reduced) slowIdx = 0
    }

    val updatesPerSecond: Double get() = if (cycleMs > 0) 1000.0 / cycleMs else 0.0

    /** A dropped connection clears these values straight away, so a long hold can't show old numbers. */
    fun fastHoldMs(): Long = hold(cycleMs)
    fun slowHoldMs(wantAll: Boolean): Long = hold(cycleMs * slowList(wantAll).size / (if (reduced) 2 else 3))

    private fun hold(everyMs: Double): Long = min(MAX_HOLD_MS, max(DataHub.STALE_MS, (everyMs * 2.5).toLong()))

    companion object {
        const val REDUCED_ABOVE_MS = 70.0
        const val FULL_BELOW_MS = 50.0
        const val MAX_HOLD_MS = 60_000L
        val CORE_FAST = setOf("map", "rpm", "ecuSpeed", "gear", "torque")
        val CORE_SLOW = setOf("ambient", "coolant", "oil", "knock1", "knock2", "knock3", "knock4", "iat", "timing", "battery")
    }
}
