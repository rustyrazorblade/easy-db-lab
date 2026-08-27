package com.rustyrazorblade.easydblab.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Tests for [SocketTcpReachabilityProbe], the probe `up` trusts when it decides whether this
 * machine has a route to the cluster's private network.
 *
 * Both cases use an ephemeral loopback port, so nothing here depends on a fixed port or on the
 * host's wider network being in any particular state.
 */
class TcpReachabilityProbeTest {
    private val probe = SocketTcpReachabilityProbe(connectTimeoutMs = 500)

    @Test
    fun `reports reachable when something is listening`() {
        ServerSocket(0).use { listener ->
            assertThat(probe.isReachable("127.0.0.1", listener.localPort)).isTrue()
        }
    }

    @Test
    fun `reports unreachable when nothing is listening`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        assertThat(probe.isReachable("127.0.0.1", closedPort)).isFalse()
    }
}
