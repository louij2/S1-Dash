package com.louij2.s1dash

import kotlin.math.max

/**
 * Start-up and rate rules for polling the engine ECU (#907). The car's CAN drops
 * happen around ignition on, so after each connect only rpm is asked for, every 2 s,
 * until the engine has run above 400 rpm for 10 s. After that requests are spaced so
 * there are never more than 20 a second, however quick the adapter is.
 */
class PollGuard(private val now: () -> Long = System::currentTimeMillis) {
    private var runningSince = 0L
    private var lastRequest = 0L
    var warm = false
        private set

    /** A new connection starts cold again. */
    fun reset() {
        warm = false
        runningSince = 0L
    }

    /** Once warm it stays warm for this connection, so a stall at the lights doesn't stop the dials. */
    fun feedRpm(rpm: Double?) {
        if (warm) return
        val t = now()
        if (rpm != null && rpm > RUNNING_RPM) {
            if (runningSince == 0L) runningSince = t
            if (t - runningSince >= WARM_MS) warm = true
        } else {
            runningSince = 0L
        }
    }

    /**
     * What to read while the engine isn't warm. Normally only rpm. The ECU files check
     * is done with the engine off, by design, and needs speed and battery too, or its
     * Flash button would wait forever.
     */
    fun coldKeys(flashCheck: Boolean): List<String> =
        if (flashCheck) listOf("rpm", "ecuSpeed", "battery") else listOf("rpm")

    /** How long to wait before the next request to stay under the ceiling. */
    fun waitMs(): Long = max(0L, lastRequest + MIN_GAP_MS - now())

    fun sent() {
        lastRequest = now()
    }

    companion object {
        const val RUNNING_RPM = 400.0
        const val WARM_MS = 10_000L
        const val PROBE_EVERY_MS = 2_000L
        const val MIN_GAP_MS = 50L // 20 requests a second
    }
}
