package com.rustyrazorblade.easydblab.compactorlease

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The safety margin [LeaseTiming] holds: a holder stops before any standby can take over. */
class LeaseTimingTest {
    private fun timing(
        ttl: Long = 120,
        renewDeadline: Long = 60,
        gatePoll: Long = 1,
        gateGrace: Long = 20,
        renewInterval: Long = 15,
        standbyPoll: Long = 15,
    ) = LeaseTiming(
        ttl = ttl.seconds,
        renewInterval = renewInterval.seconds,
        renewDeadline = renewDeadline.seconds,
        standbyPoll = standbyPoll.seconds,
        gatePoll = gatePoll.seconds,
        gateGrace = gateGrace.seconds,
    )

    @Test
    fun `the shipped timing stops a holder 39 seconds before a standby can take over`() {
        val timing = timing()

        assertThat(timing.worstCaseStop).isEqualTo(81.seconds)
        assertThat(timing.ttl - timing.worstCaseStop).isEqualTo(39.seconds)
    }

    @Test
    fun `a timing whose worst-case stop equals the TTL is refused`() {
        assertThatThrownBy { timing(ttl = 81) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("81s")
    }

    @Test
    fun `a timing whose worst-case stop passes the TTL is refused`() {
        assertThatThrownBy { timing(renewDeadline = 100) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a renew interval no shorter than the renew deadline is refused`() {
        assertThatThrownBy { timing(renewInterval = 60) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a zero gate poll is refused`() {
        assertThatThrownBy { timing(gatePoll = 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a timing is read from the environment the pod sets`() {
        val env =
            mapOf(
                "LEASE_TTL_SECONDS" to "120",
                "LEASE_RENEW_INTERVAL_SECONDS" to "15",
                "LEASE_RENEW_DEADLINE_SECONDS" to "60",
                "LEASE_STANDBY_POLL_SECONDS" to "15",
                "LEASE_GATE_POLL_SECONDS" to "1",
                "LEASE_GATE_GRACE_SECONDS" to "20",
            )

        assertThat(LeaseTiming.fromEnvironment(env)).isEqualTo(timing())
    }

    @Test
    fun `a missing timing variable is refused with its name`() {
        assertThatThrownBy { LeaseTiming.fromEnvironment(emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("LEASE_TTL_SECONDS")
    }
}
