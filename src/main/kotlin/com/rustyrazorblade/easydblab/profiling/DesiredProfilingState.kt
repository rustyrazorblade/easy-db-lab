package com.rustyrazorblade.easydblab.profiling

/**
 * What reading a node's desired-state document found.
 *
 * Three outcomes, not two, because callers act differently on each. A node nobody has configured
 * and a node whose document is truncated both yield "no config", but only the second means the
 * operator's retention window and byte ceiling are about to be replaced by defaults — which is
 * exactly the outcome `profile stop` exists to prevent. Collapsing them into a nullable made that
 * substitution silent and unreportable.
 */
sealed interface DesiredProfilingState {
    /** The node has a readable desired state. */
    data class Configured(
        val config: ProfilingConfig,
    ) : DesiredProfilingState

    /** Nobody has configured profiling on this node; there is nothing to preserve. */
    data object Unconfigured : DesiredProfilingState

    /**
     * A document exists but could not be decoded — truncated by an interrupted write, or written by
     * a version this CLI cannot read.
     *
     * @property document the raw bytes as read, so a caller can report what it could not parse.
     */
    data class Unreadable(
        val document: String,
    ) : DesiredProfilingState
}
