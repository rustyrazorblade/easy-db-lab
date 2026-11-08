package com.rustyrazorblade.easydblab.compactorlease

import java.time.Instant

/**
 * What the lease agent tells the compactor containers. The gates run each compactor only while the
 * granted time is in the future, so they stop it on their own when the agent dies or hangs.
 */
interface Gate {
    /** Lets the compactors run until [validUntil], a time on this node's wall clock. */
    fun grant(validUntil: Instant)

    /** Stops the compactors now. */
    fun revoke()

    /** Marks the agent ready: it has read the lease once, so the store and credentials work. */
    fun ready()
}
