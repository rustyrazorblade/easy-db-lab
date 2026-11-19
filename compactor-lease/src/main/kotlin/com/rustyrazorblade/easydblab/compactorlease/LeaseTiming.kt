package com.rustyrazorblade.easydblab.compactorlease

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The lease's timing, and the margin that keeps two compactors from working at once.
 *
 * A holder's lease is valid until the send time of its last successful renewal plus
 * [renewDeadline]. Its gates notice an expired lease within [gatePoll] and kill the binary
 * [gateGrace] after that, so it stops by [worstCaseStop] after the send. A standby's first read of
 * that renewal comes after the send, and it takes over only once it has seen the same version for
 * [ttl] on its own clock. So the holder stops before a standby can take over exactly when
 * [worstCaseStop] is less than [ttl], and a timing that breaks that cannot be built.
 *
 * The holder's side of that margin is on the wall clock: [GateFile] writes `valid-until` as wall
 * epoch seconds and the gates compare it with the node's `date`. So the margin holds only while the
 * node's clock is slewed, as chrony does, and is never stepped forward or back by more than the
 * slack, `ttl - worstCaseStop`, during one lease window. The standby's side is on its monotonic
 * clock and does not depend on this.
 *
 * @property ttl how long a standby waits on one version before it takes over.
 * @property renewInterval how often the holder renews; shorter than [renewDeadline], so a renewal
 *   that is a little late does not expire the lease.
 * @property renewDeadline how long after a renewal's send the holder's lease stays valid.
 * @property standbyPoll how often a standby reads the lease.
 * @property gatePoll how often each gate reads the valid-until file.
 * @property gateGrace how long a gate waits after SIGTERM before SIGKILL.
 * @throws IllegalArgumentException when any value is not positive, the renew interval is not
 *   shorter than the renew deadline, or [worstCaseStop] is not less than [ttl].
 */
data class LeaseTiming(
    val ttl: Duration,
    val renewInterval: Duration,
    val renewDeadline: Duration,
    val standbyPoll: Duration,
    val gatePoll: Duration,
    val gateGrace: Duration,
) {
    /** The latest a holder's compactors stop after the send of its last successful renewal. */
    val worstCaseStop: Duration get() = renewDeadline + gatePoll + gateGrace

    /**
     * The longest one store call may take, retries included: half the margin between [worstCaseStop]
     * and [ttl]. A standby counts the TTL from when a read returns, so a read that answers with a
     * version written while the read was in flight is safe only while the read takes less than that
     * margin. The other half covers the pause between the read returning and the clock being read.
     */
    val storeCallTimeout: Duration get() = (ttl - worstCaseStop) / 2

    /** The longest one attempt of a store call may take, so all [STORE_ATTEMPTS] fit [storeCallTimeout]. */
    val storeAttemptTimeout: Duration get() = storeCallTimeout / STORE_ATTEMPTS

    init {
        listOf(ttl, renewInterval, renewDeadline, standbyPoll, gatePoll, gateGrace).forEach {
            require(it.isPositive()) { "Every lease timing must be positive: $this" }
        }
        require(
            renewInterval < renewDeadline,
        ) { "The renew interval $renewInterval must be shorter than the renew deadline $renewDeadline" }
        require(worstCaseStop < ttl) {
            "The renew deadline plus the gate poll plus the gate grace (${worstCaseStop.inWholeSeconds}s) must be less than " +
                "the TTL (${ttl.inWholeSeconds}s), " +
                "or a standby could take over while the holder still compacts"
        }
    }

    companion object {
        /**
         * How many attempts of a read fit in [storeCallTimeout]: the S3 client's default retry
         * strategy makes four. The call timeout is the bound that matters; it ends a call whatever
         * the retry strategy does.
         */
        const val STORE_ATTEMPTS = 4

        const val TTL_ENV = "LEASE_TTL_SECONDS"
        const val RENEW_INTERVAL_ENV = "LEASE_RENEW_INTERVAL_SECONDS"
        const val RENEW_DEADLINE_ENV = "LEASE_RENEW_DEADLINE_SECONDS"
        const val STANDBY_POLL_ENV = "LEASE_STANDBY_POLL_SECONDS"
        const val GATE_POLL_ENV = "LEASE_GATE_POLL_SECONDS"
        const val GATE_GRACE_ENV = "LEASE_GATE_GRACE_SECONDS"

        /**
         * The timing the pod sets in [env], in whole seconds.
         *
         * @throws IllegalArgumentException naming a variable that is missing or not a number, or a
         *   timing that breaks the margin.
         */
        fun fromEnvironment(env: Map<String, String>): LeaseTiming {
            fun seconds(name: String): Duration =
                requireNotNull(env[name]?.toLongOrNull()) { "$name must be set to a whole number of seconds" }.seconds
            return LeaseTiming(
                ttl = seconds(TTL_ENV),
                renewInterval = seconds(RENEW_INTERVAL_ENV),
                renewDeadline = seconds(RENEW_DEADLINE_ENV),
                standbyPoll = seconds(STANDBY_POLL_ENV),
                gatePoll = seconds(GATE_POLL_ENV),
                gateGrace = seconds(GATE_GRACE_ENV),
            )
        }
    }
}
