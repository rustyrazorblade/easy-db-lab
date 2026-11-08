package com.rustyrazorblade.easydblab.compactorlease

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * One node's clocks. Every lease decision uses [monotonic] only, so a wall clock that jumps never
 * moves a deadline; [wall] is for the gate file, which the node's own gates compare with the same
 * node's wall clock, and for the record `status` shows.
 */
interface LeaseClock {
    /** Time on a clock that never goes back, from an arbitrary origin. */
    fun monotonic(): Duration

    /** The node's wall-clock time. */
    fun wall(): Instant
}

/** The JVM's clocks: [System.nanoTime] and [Instant.now]. */
object SystemLeaseClock : LeaseClock {
    override fun monotonic(): Duration = System.nanoTime().nanoseconds

    override fun wall(): Instant = Instant.now()
}
