package com.louij2.s1dash

/**
 * Waits that grow while the dongle or the ECU keeps failing. The SimosTools bridge takes one
 * BLE connection, so a Dash retrying every few seconds keeps grabbing it from SimosTools (#967).
 * Longer gaps leave it free; a success starts again from the shortest wait.
 */
class Backoff(private val stepsMs: LongArray = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)) {
    private var failures = 0

    /** The wait after one more failure. */
    fun next(): Long = stepsMs[minOf(failures, stepsMs.size - 1)].also { failures++ }

    fun reset() { failures = 0 }
}
