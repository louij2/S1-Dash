package com.louij2.s1dash

/**
 * When to record on its own, like the S1 SimosTools profile (LogEquation = cruise > 1 | pedal > 70,
 * LogEndDelay 2000): while cruise is held, or on any pull over 70 % pedal, and for [tailMs] after.
 * The 2 s lead-in comes from the rows the auto recorder keeps in memory (#1038).
 */
class LogTrigger(val mode: String, private val tailMs: Long = TAIL_MS) {
    private var lastFired = -1L

    val armed get() = mode in MODES

    fun fires(cruise: Double?, pedal: Double?): Boolean {
        val c = (cruise ?: 0.0) > CRUISE_ABOVE
        val p = (pedal ?: 0.0) > PEDAL_ABOVE
        return when (mode) {
            "cruise" -> c
            "pedal" -> p
            "either" -> c || p
            else -> false
        }
    }

    /** True while it should be recording: the trigger is on, or went off less than [tailMs] ago. */
    fun recording(nowMs: Long, cruise: Double?, pedal: Double?): Boolean {
        if (fires(cruise, pedal)) lastFired = nowMs
        return lastFired >= 0 && nowMs - lastFired <= tailMs
    }

    companion object {
        val MODES = setOf("cruise", "pedal", "either")
        const val CRUISE_ABOVE = 1.0
        const val PEDAL_ABOVE = 70.0
        const val LEAD_MS = 2000L
        const val TAIL_MS = 2000L
        /** Read every cycle while armed, so a pull starts the recording straight away. */
        val KEYS = setOf("cruise", "pedal")
    }
}
