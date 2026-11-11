package com.rustyrazorblade.easydblab.proxy

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * [SocksTunnelReachabilityProbe] through a real SOCKS5 listener whose far end works, never answers,
 * or answers slowly and then goes silent. One attempt, from the connect to the banner read, never
 * takes longer than its bound: a dead tunnel is noticed after one bound, not after several waits.
 *
 * The probe here has a longer bound, [ATTEMPT_MS], than the CLI's, so that a correct attempt (about
 * one bound) and a wrong one (a banner read with its own full timeout, about the slow reply plus one
 * bound) are far apart, and a busy machine's scheduling delay cannot carry one into the other.
 */
internal class SocksTunnelReachabilityProbeIntegrationTest {
    private val probe = SocksTunnelReachabilityProbe(ATTEMPT_MS)

    @Test
    fun `a working tunnel is reachable`() {
        FakeSocksTunnel().use { tunnel ->
            assertThat(probe.isReachable(tunnel.port, TARGET, SSH_PORT)).isTrue()
        }
    }

    @Test
    fun `a tunnel that never answers fails within one attempt bound`() {
        FakeSocksTunnel(FakeSocksTunnel.Behaviour.SILENT).use { tunnel ->
            val (reachable, millis) = timed { probe.isReachable(tunnel.port, TARGET, SSH_PORT) }

            assertThat(reachable).isFalse()
            assertThat(millis).isLessThan(BOUND_WITH_SLACK_MS)
        }
    }

    @Test
    fun `a tunnel that answers slowly and then sends nothing fails within one attempt bound, not two`() {
        // The CONNECT reply comes after most of the bound; a banner read with its own full timeout
        // would wait about a second more.
        FakeSocksTunnel(FakeSocksTunnel.Behaviour.SLOW_THEN_SILENT).use { tunnel ->
            val (reachable, millis) = timed { probe.isReachable(tunnel.port, TARGET, SSH_PORT) }

            assertThat(reachable).isFalse()
            assertThat(millis).isLessThan(BOUND_WITH_SLACK_MS)
        }
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    private companion object {
        const val TARGET = "10.0.1.5"

        /** The probe's bound for one attempt in these tests; longer than [FakeSocksTunnel.SLOW_REPLY_MS]. */
        const val ATTEMPT_MS = 3000

        /**
         * The bound, plus room for thread scheduling on a busy machine (1.5s). It stays as far below the
         * bound plus [FakeSocksTunnel.SLOW_REPLY_MS], what a banner read with its own full timeout would
         * take (about 5.5s).
         */
        const val BOUND_WITH_SLACK_MS = ATTEMPT_MS + 1500L
    }
}
