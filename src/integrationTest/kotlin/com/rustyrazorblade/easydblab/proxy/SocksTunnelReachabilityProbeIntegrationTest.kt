package com.rustyrazorblade.easydblab.proxy

import com.rustyrazorblade.easydblab.Constants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * [SocksTunnelReachabilityProbe] through a real SOCKS5 listener whose far end works, never answers,
 * or answers slowly and then goes silent. One attempt, from the connect to the banner read, never
 * takes longer than its bound: a dead tunnel is noticed in about a second, not after several waits.
 */
internal class SocksTunnelReachabilityProbeIntegrationTest {
    private val probe = SocksTunnelReachabilityProbe(Constants.Proxy.PROBE_ATTEMPT_TIMEOUT_MS)

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

        /** The bound, plus room for thread scheduling on a busy CI runner. */
        const val BOUND_WITH_SLACK_MS = Constants.Proxy.PROBE_ATTEMPT_TIMEOUT_MS + 300L
    }
}
